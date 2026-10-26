# showcase-motion

An animated export of a design - a loop for a launch post, a GIF for the website, a hero video - is not a redesign. The viewer must see **the exact design**, and see that **it is alive**:

**exact frame → start empty → load in → every visual acts out its story → a seamless loop → a deterministic capture → MP4 and GIF.**

This is its own mode, with its own timing: product rules such as "under 300ms" do not apply. Slow and smooth is the brief. On a live website, the same build plays once on scroll and keeps its stories running; only the exports dissolve and loop.

## 1. The exact replica

- **One source of truth.** A design file's own render (a Figma frame exported at 2x through its API, with the token read from the environment, never printed) or the running product. The finished still must match it.
- **Every moving element is its own layer**, placed at its exported bounds. In a showcase, text is an exported layer too - retyped text never matches the kerning. On a website, text stays live HTML in the site's font.
- **Keep the source's z order**; strokes above children; a group's shadow on the union of its shapes, not on each.
- **The background is sacred.** A photo or texture behind the UI never moves, blurs, fades or recolours, in any phase or variant. Use the original image, not a render of it. If it cannot be reproduced, say so before building.
- **Verify the still** before any motion: render the at-rest frame at 2x and compare with the reference by PSNR after a light blur (`ffmpeg ... -lavfi "gblur=sigma=1.2,psnr"`). Aim over 35 dB overall, 30 dB per container; look at an amplified difference image for structural errors. Do not chase sub-pixel offsets.

## 2. Start empty

The first frame of every load and every export is the **empty state**: for an app frame (joined surfaces, a sidebar and a body) the background plus those static surfaces; for separate cards, the background alone. Empty becomes loaded. Never open on the finished frame.

## 3. Entrance kinds

Everything rises from below and resolves from blur to sharp, staggered in reading order.

| Kind | For | From |
| --- | --- | --- |
| `card` | separate cards, with their shadow | `translateY(24px) scale(0.985)`, `blur(6px)`, opacity 0 |
| `up` | text, values, rows | `translateY(10px)`, `blur(4px)`, opacity 0 |
| `down` | top bars and toolbars | `translateY(-8px)`, `blur(4px)` |
| `left` | sidebar items, list rows | `translateX(-10px)`, `blur(4px)` |
| `pop` | icons, avatars, badges, buttons | `scale(0.82-0.88)` from its own centre, `blur(4px)` |
| `photo` | images | a skeleton shimmer, then `blur(14px) saturate(0.6)` to sharp |
| `wipe` | bars, fills, highlights | a left-to-right `clip-path` reveal, in pixels |
| `line` / `grow` | rules, ticks, timelines | `scaleX(0)` / `scaleY(0)` from the start edge - only lines draw from zero |
| `donut` | rings and gauges | a conic mask sweeping from 12 o'clock |
| `fade` | layers that sit under another | opacity only |

Containers 80-90ms apart; inside one, header then labels then values then visuals; items in a group 30-80ms apart. A covered layer arrives after its cover and leaves before it. Skeletons match the real shape, 1px inside it. No loading lines or shapes that are not in the design. Total intro about 2-2.5s.

## 4. A story per visual

The intro is the opening; the main act is every visual **doing what it does**, on a loop. Write each one's story in a sentence before building it:

| Visual | Story |
| --- | --- |
| A stack (leads, notifications) | a new item lands on top with a blur, the rest step back, the last leaves |
| A line or area chart | a scrubber glides across, the dot rides the line, the tooltip counts |
| Bars, an equaliser, cells | bars breathe like a live feed and settle back to the design's heights |
| A table or list | a highlight inspects rows one by one |
| A calendar | a highlight finds the open slot, the event glides in, then back |
| A flow or pipeline | pulses travel the connectors; nodes light as they pass |
| A prompt or form | the text deletes and retypes glyph by glyph (45-70ms a glyph, a caret blinking once a second) |
| Counters | count up with the chart that drives them, landing on the design's exact text |

Each story starts and ends on the exact design; only counters and typing change text; helpers (a cursor, a duplicate row) match the design's style and are hidden at rest. Something visibly happens every one to two seconds. Cards act out of step with each other, so attention travels in reading order. A story must still read in the 800×500 GIF: enlarge anything under about 4px there. Text-only cards rise in and rest.

## 5. The seamless cycle

| Phase | Default |
| --- | --- |
| Build in | 0 to 2.4s |
| Stories | 2.4 to 11.0s (every card's story at least once, all back at rest) |
| Dissolve | 11.0 to 11.6s, opacity over 0.5s |
| Empty beat | 11.6 to 12.0s |

Lengthen the cycle rather than cut a story short. At the end, everything - CSS and every JS-driven value - is reset to the empty state, so the last frame equals the first. The background never dissolves; an app frame's static surfaces stay.

Timing: transforms 1.05s on `cubic-bezier(0.16, 1, 0.3, 1)`; opacity and blur 0.7-0.8s on `cubic-bezier(0.33, 1, 0.68, 1)`; wipes and fills 0.7-1.1s on `cubic-bezier(0.65, 0, 0.35, 1)`; linear only for constant motion. Animated blur 14px at most. No zoom or pan unless asked. `prefers-reduced-motion` shows the still.

## 6. A deterministic capture

Never screen-record in real time. Build the page with three modes: `?still` (the exact frame, no motion), `?loop`, and `?capture`, which turns off `requestAnimationFrame` and exposes `window.__start()`, `window.__tick(dt)` and the loop time `window.__lt`. One JS clock drives everything time-based from loop time, never from the wall clock. Then step a fake clock and screenshot each frame:

- install a fake clock (Playwright's `page.clock`) at 0, load `?capture`, wait for fonts, pause;
- per frame, advance whole milliseconds that average exactly 1000/60 (17, 17, 16, ...), never `1000/60` itself, which drifts;
- call `__start()` and `__tick(dt)` inside the same evaluate as the seek, and seek every CSS animation to the fake clock (`for (const a of document.getAnimations()) { a.pause(); a.currentTime = now - mark; }`);
- drive endless loops (shimmer, marquee) from the JS clock, not infinite keyframes;
- record about 15 frames before the start to settle, and 10 past one cycle for the seam check;
- viewport at the frame's size, `deviceScaleFactor = min(1920 / W, 1200 / H)`.

## 7. Export

```sh
# MP4 for social: H.264, 60fps, CRF 15, even dimensions, faststart
ffmpeg -start_number $A -framerate 60 -i frames/%05d.png -frames:v $N \
  -vf "crop=trunc(iw/2)*2:trunc(ih/2)*2" -c:v libx264 -preset slow -crf 15 \
  -pix_fmt yuv420p -movflags +faststart name-x.mp4

# GIF for the website: 960 wide, 20fps, palette per run
ffmpeg -start_number $A -framerate 60 -i frames/%05d.png -filter_complex \
  "trim=end_frame=$N,fps=20,scale=960:-2:flags=lanczos,split[a][b];\
   [a]palettegen=max_colors=192:stats_mode=diff[p];\
   [b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" -loop 0 name-loop.gif
```

An 800×500 GIF too: crop to the content with about 40px margin at 1.6:1, or fit and pad with the page colour (`scale=800:500:force_original_aspect_ratio=decrease,pad=800:500:(ow-iw)/2:(oh-ih)/2:color=<bg>` before `split`). Keep bayer dithering whenever a photo is in frame. If a GIF is heavy, lower the frame rate or colours before the size.

## 8. Pixel checks before encoding

- **Seam**: the first frame of the export window equals the frame one cycle later.
- **Empty beat**: every frame of the beat equals the frame before `__start` - this catches JS state that never reset.
- **Rest**: the last frame before the dissolve equals `?still` - every story came home.
- **Overflow**: outside the containers (their real rounded shapes, grown 2px), nothing changes during the stories.
- **Contact sheet**: tile frames from empty, mid-intro, landed, through the stories, mid-dissolve and end, and look for ghosting, pops and stories too timid to notice.

**Report:** still fidelity (overall and worst container PSNR), the seam, empty-beat, rest and overflow results, the three files with size, dimensions, frame rate and duration, and anything not reproduced exactly.

Adapted from elayadesign's design-to-motion skill; see `CREDITS.md`.
