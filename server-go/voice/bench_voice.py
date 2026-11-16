#!/usr/bin/env python3
"""Bench's voice for calls (server-go/bench/meeting.go runs it; sh server-go/meeting-setup installs it).

Two jobs, each a long-running process so the models load once:

  bench_voice.py say --models DIR
      stdin:  one JSON request per line, {"id", "text", "voice", "speed"}
      stdout: one JSON answer per line,  {"id", "file", "secs"} or {"id", "error"}
      Kokoro (sherpa-onnx), kept loaded: a short sentence is ready in under a second.

  bench_voice.py listen --models DIR
      stdin:  the call's audio, 16 kHz mono 16-bit PCM
      stdout: {"type": "start"} when someone starts talking, {"type": "partial", "text"} as
              words come, and {"type": "final", "text"} when they finish a sentence.
      Two passes. A fast streaming model hears words as they are spoken and the end of a
      turn; then the whole utterance is heard again by an accurate one (Parakeet, if
      models/accurate is installed), and that text is the final, with the words it was
      unsure of marked [like this?] and a confidence for the utterance.
"""
import argparse
import json
import math
import os
import queue
import sys
import threading
import tempfile
import time
import wave

import numpy as np
import sherpa_onnx


_out_lock = threading.Lock()


def out(o):
    with _out_lock:
        sys.stdout.write(json.dumps(o) + "\n")
        sys.stdout.flush()


def accurate_recognizer(models, threads):
    a = os.path.join(models, "accurate")
    if not os.path.exists(os.path.join(a, "tokens.txt")):
        return None

    def f(name):
        for x in (name + ".int8.onnx", name + ".onnx"):
            if os.path.exists(os.path.join(a, x)):
                return os.path.join(a, x)
        raise FileNotFoundError(name)

    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f("encoder"), decoder=f("decoder"), joiner=f("joiner"),
        tokens=os.path.join(a, "tokens.txt"), num_threads=threads, model_type="nemo_transducer",
    )


def marked(result):
    """The text with the words the model was unsure of marked [like this?], and the
    utterance's confidence (0 to 1)."""
    toks, lps = list(result.tokens), list(getattr(result, "ys_log_probs", []) or [])
    if not toks or len(lps) != len(toks):
        return result.text.strip(), None
    words, cur, low = [], "", 0.0
    for t, lp in zip(toks, lps):
        if t.startswith(" ") and cur:
            words.append((cur, low))
            cur, low = "", 0.0
        cur += t
        low = min(low, lp)
    if cur:
        words.append((cur, low))
    text = ""
    for w, lp in words:
        core = w.strip()
        if math.exp(lp) < 0.4 and any(c.isalnum() for c in core):
            lead = " " if w.startswith(" ") else ""
            punct = ""
            while core and not core[-1].isalnum():
                punct = core[-1] + punct
                core = core[:-1]
            text += lead + "[" + core + "?]" + punct
        else:
            text += w
    conf = math.exp(sum(lps) / len(lps))
    return text.strip(), round(conf, 2)


def say(models, threads):
    k = os.path.join(models, "kokoro")
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=os.path.join(k, "model.onnx"),
                voices=os.path.join(k, "voices.bin"),
                tokens=os.path.join(k, "tokens.txt"),
                data_dir=os.path.join(k, "espeak-ng-data"),
                lexicon=os.path.join(k, "lexicon-us-en.txt"),
                dict_dir=os.path.join(k, "dict"),
                lang="en-us",
            ),
            num_threads=threads,
        ),
        max_num_sentences=1,
    ))
    out({"ready": True, "voices": tts.num_speakers})
    for line in sys.stdin:
        try:
            r = json.loads(line)
        except ValueError:
            continue
        rid = r.get("id")
        try:
            audio = tts.generate(r["text"], sid=int(r.get("voice", 3)), speed=float(r.get("speed", 1.0)))
            if len(audio.samples) == 0:
                out({"id": rid, "error": "nothing to say"})
                continue
            fd, path = tempfile.mkstemp(prefix="bench-say-", suffix=".wav")
            os.close(fd)
            pcm = (np.clip(np.array(audio.samples), -1, 1) * 32767).astype(np.int16)
            with wave.open(path, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(audio.sample_rate)
                w.writeframes(pcm.tobytes())
            out({"id": rid, "file": path, "secs": len(pcm) / audio.sample_rate})
        except Exception as e:  # one bad line must not end the voice
            out({"id": rid, "error": str(e)})


def listen(models, threads, pause):
    a = os.path.join(models, "asr")

    def pick(name):
        for f in sorted(os.listdir(a)):
            if f.startswith(name) and f.endswith(".int8.onnx"):
                return os.path.join(a, f)
        for f in sorted(os.listdir(a)):
            if f.startswith(name) and f.endswith(".onnx"):
                return os.path.join(a, f)
        raise FileNotFoundError(name)

    rec = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=os.path.join(a, "tokens.txt"),
        encoder=pick("encoder"),
        decoder=pick("decoder"),
        joiner=pick("joiner"),
        num_threads=threads,
        sample_rate=16000,
        feature_dim=80,
        enable_endpoint_detection=True,
        rule1_min_trailing_silence=2.4,
        rule2_min_trailing_silence=pause,
        rule3_min_utterance_length=30,
        decoding_method="greedy_search",
    )
    stream = rec.create_stream()
    accurate = accurate_recognizer(models, threads)
    # The second pass runs beside the live one, so hearing never stops while it works.
    jobs = queue.Queue()

    def second_pass():
        while True:
            audio, fast = jobs.get()
            try:
                st = accurate.create_stream()
                st.accept_waveform(16000, audio)
                accurate.decode_stream(st)
                text, conf = marked(st.result)
                out({"type": "final", "text": text or fast, "confidence": conf, "fast": fast})
            except Exception as e:  # fall back to what the live pass heard
                out({"type": "final", "text": fast, "error": str(e)})

    if accurate:
        threading.Thread(target=second_pass, daemon=True).start()
    utterance, before = [], []  # this utterance's audio, and the 0.5 s before it started
    out({"ready": True, "accurate": bool(accurate)})
    started, last, last_sent = False, "", 0.0
    chunk = 1600 * 2  # 0.1 s
    src = sys.stdin.buffer
    # Someone's voice, from its loudness: on at once, off after 0.4 s of quiet. Faster than
    # waiting for words, so Bench holds its own speech while they talk.
    voice, quiet = False, 0
    while True:
        b = src.read(chunk)
        if not b:
            break
        raw = np.frombuffer(b, dtype=np.int16)
        loud = float(np.sqrt(np.mean(raw.astype(np.float32) ** 2))) > 350 if len(raw) else False
        if loud:
            quiet = 0
            if not voice:
                voice = True
                out({"type": "voice", "on": True})
        elif voice:
            quiet += 1
            if quiet >= 4:
                voice = False
                out({"type": "voice", "on": False})
        samples = raw.astype(np.float32) / 32768
        if started or loud:
            if not utterance:
                utterance.extend(before)
            utterance.append(samples)
        else:
            before = (before + [samples])[-5:]
        stream.accept_waveform(16000, samples)
        while rec.is_ready(stream):
            rec.decode_stream(stream)
        text = rec.get_result(stream).strip()
        if text and not started:
            started = True
            out({"type": "start"})
        if text and text != last and time.time() - last_sent > 0.3:
            last, last_sent = text, time.time()
            out({"type": "partial", "text": text.lower()})
        if rec.is_endpoint(stream):
            if text:
                if accurate and utterance:
                    jobs.put((np.concatenate(utterance), text.lower()))
                else:
                    out({"type": "final", "text": text.lower()})
            rec.reset(stream)
            started, last = False, ""
            utterance, before = [], []


def main():
    p = argparse.ArgumentParser()
    p.add_argument("job", choices=["say", "listen"])
    p.add_argument("--models", required=True)
    p.add_argument("--threads", type=int, default=2)
    p.add_argument("--pause", type=float, default=0.6, help="seconds of quiet that end a turn")
    a = p.parse_args()
    if a.job == "say":
        say(a.models, a.threads)
    else:
        listen(a.models, a.threads, a.pause)


if __name__ == "__main__":
    main()
