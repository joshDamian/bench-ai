// @ts-check
// The browser: a Chromium on this server that sessions can use, with its own profile so
// sign-ins last (as cookies, never passwords). Sessions reach it through the Playwright MCP
// server over the DevTools protocol. When a site needs the person - a sign-in, a CAPTCHA -
// the session asks them, and the person takes over from their phone: the browser's page is
// streamed to them as frames, and their taps and typing are sent back as input.
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

export const PLAYWRIGHT_MCP = '@playwright/mcp@0.0.84';

/** Where Chromium is on this machine, or null. */
export function chromiumPath() {
  const env = process.env.BENCH_CHROMIUM;
  if (env) return fs.existsSync(env) ? env : null;
  for (const name of ['chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable']) {
    try {
      const p = execFileSync('sh', ['-c', `command -v ${name}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
      if (p) return p;
    } catch {}
  }
  // Playwright's own download, newest first.
  const cache = process.env.PLAYWRIGHT_BROWSERS_PATH || path.join(os.homedir(), '.cache', 'ms-playwright');
  try {
    for (const d of fs.readdirSync(cache).filter((n) => /^chromium-\d+$/.test(n)).sort().reverse()) {
      for (const sub of ['chrome-linux64/chrome', 'chrome-linux/chrome', 'chrome-mac/Chromium.app/Contents/MacOS/Chromium']) {
        const p = path.join(cache, d, sub);
        if (fs.existsSync(p)) return p;
      }
    }
  } catch {}
  return null;
}

/** A registrable-looking name for a cookie's domain: "www.linkedin.com" -> "linkedin.com". @param {string} d */
export function siteOf(d) {
  const parts = d.replace(/^\./, '').split('.').filter(Boolean);
  return parts.length <= 2 ? parts.join('.') : parts.slice(-2).join('.');
}

export class Browser {
  /** @param {string} dataDir */
  constructor(dataDir) {
    this.profile = path.join(dataDir, 'browser');
    this.port = Number(process.env.BENCH_BROWSER_PORT) || 9338;
    /** @type {import('node:child_process').ChildProcess|null} */
    this.proc = null;
  }

  get endpoint() {
    return `http://127.0.0.1:${this.port}`;
  }

  /** @returns {Promise<any>} */
  async #json(/** @type {string} */ p) {
    const r = await fetch(this.endpoint + p, { signal: AbortSignal.timeout(2000) });
    return r.json();
  }

  async running() {
    try {
      await this.#json('/json/version');
      return true;
    } catch {
      return false;
    }
  }

  /** Starts Chromium if it is installed and not already up. @returns {Promise<boolean>} running */
  async ensure() {
    if (await this.running()) return true;
    const bin = chromiumPath();
    if (!bin) return false;
    fs.mkdirSync(this.profile, { recursive: true });
    // Chromium will not start its sandbox as root, which a server often is.
    const root = process.getuid?.() === 0 ? ['--no-sandbox'] : [];
    this.proc = spawn(bin, [...root, '--headless=new', `--remote-debugging-port=${this.port}`, '--remote-debugging-address=127.0.0.1', `--user-data-dir=${this.profile}`, '--no-first-run', '--no-default-browser-check', '--window-size=1280,900', 'about:blank'], { stdio: 'ignore', detached: false });
    this.proc.on('exit', () => (this.proc = null));
    // It does not keep Bench running, and goes when Bench does.
    this.proc.unref();
    const proc = this.proc;
    process.once('exit', () => proc.kill());
    for (let i = 0; i < 50; i++) {
      if (await this.running()) return true;
      await new Promise((r) => setTimeout(r, 100));
    }
    return false;
  }

  stop() {
    this.proc?.kill();
    this.proc = null;
  }

  /** The MCP server sessions use to drive it, when it is installed. */
  mcp() {
    if (!chromiumPath()) return null;
    return { type: /** @type {const} */ ('stdio'), command: 'npx', args: ['-y', PLAYWRIGHT_MCP, '--cdp-endpoint', this.endpoint] };
  }

  /** One DevTools call on a target's socket. @param {string} wsUrl @param {string} method @param {any} [params] */
  async #call(wsUrl, method, params = {}) {
    const ws = new WebSocket(wsUrl);
    try {
      await new Promise((res, rej) => (ws.once('open', res), ws.once('error', rej)));
      return await new Promise((res, rej) => {
        ws.on('message', (raw) => {
          const m = JSON.parse(String(raw));
          if (m.id === 1) (m.error ? rej(new Error(m.error.message)) : res(m.result));
        });
        ws.send(JSON.stringify({ id: 1, method, params }));
      });
    } finally {
      ws.close();
    }
  }

  async #pageSocket() {
    const list = await this.#json('/json');
    return list.find((/** @type {any} */ t) => t.type === 'page')?.webSocketDebuggerUrl || null;
  }

  /** The sites it is signed in to: the ones it holds cookies for. */
  async sites() {
    if (!(await this.running())) return [];
    const ws = await this.#pageSocket();
    if (!ws) return [];
    const { cookies } = await this.#call(ws, 'Network.getAllCookies');
    return [...new Set(cookies.map((/** @type {any} */ c) => siteOf(c.domain)))].sort();
  }

  /** Signs out of a site: forgets its cookies. @param {string} site */
  async signOut(site) {
    if (!(await this.running())) return;
    const ws = await this.#pageSocket();
    if (!ws) return;
    const { cookies } = await this.#call(ws, 'Network.getAllCookies');
    for (const c of cookies.filter((/** @type {any} */ c) => siteOf(c.domain) === site)) {
      await this.#call(ws, 'Network.deleteCookies', { name: c.name, domain: c.domain, path: c.path });
    }
  }

  async status() {
    const installed = !!chromiumPath();
    const running = installed && (await this.running());
    return { installed, running, sites: running ? await this.sites().catch(() => []) : [] };
  }

  /**
   * Streams the browser's page to a person and their input back: the take-over view.
   * @param {import('ws').WebSocket} client
   */
  async view(client) {
    if (!(await this.ensure())) return client.close(1011, 'The browser is not installed on this server');
    const wsUrl = await this.#pageSocket();
    if (!wsUrl) return client.close(1011, 'The browser has no page open');
    const page = new WebSocket(wsUrl);
    let next = 100;
    const send = (/** @type {string} */ method, /** @type {any} */ params = {}) => page.readyState === WebSocket.OPEN && page.send(JSON.stringify({ id: next++, method, params }));
    /** @type {{deviceWidth:number, deviceHeight:number}} */
    let meta = { deviceWidth: 1280, deviceHeight: 900 };
    page.on('open', async () => {
      send('Page.enable');
      send('Page.startScreencast', { format: 'jpeg', quality: 60, maxWidth: 1280, maxHeight: 1600, everyNthFrame: 1 });
      const list = await this.#json('/json').catch(() => []);
      const t = list.find((/** @type {any} */ x) => x.webSocketDebuggerUrl === wsUrl);
      if (t) client.send(JSON.stringify({ type: 'url', url: t.url, title: t.title }));
    });
    page.on('message', (raw) => {
      const m = JSON.parse(String(raw));
      if (m.method === 'Page.screencastFrame') {
        meta = m.params.metadata;
        send('Page.screencastFrameAck', { sessionId: m.params.sessionId });
        if (client.readyState === WebSocket.OPEN) client.send(JSON.stringify({ type: 'frame', data: m.params.data, width: meta.deviceWidth, height: meta.deviceHeight }));
      } else if (m.method === 'Page.frameNavigated' && !m.params.frame.parentId) {
        client.send(JSON.stringify({ type: 'url', url: m.params.frame.url }));
      }
    });
    page.on('close', () => client.close());
    page.on('error', () => client.close());
    client.on('close', () => page.close());
    client.on('message', (raw) => {
      let m;
      try {
        m = JSON.parse(String(raw));
      } catch {
        return;
      }
      // Taps arrive as fractions of the frame, so the phone needs no idea of the page's size.
      if (m.type === 'tap') {
        const x = Number(m.x) * meta.deviceWidth;
        const y = Number(m.y) * meta.deviceHeight;
        send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
        send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
      } else if (m.type === 'scroll') {
        send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: meta.deviceWidth / 2, y: meta.deviceHeight / 2, deltaX: 0, deltaY: Number(m.dy) * meta.deviceHeight });
      } else if (m.type === 'text' && typeof m.text === 'string') {
        send('Input.insertText', { text: m.text.slice(0, 2000) });
      } else if (m.type === 'key' && KEYS[m.key]) {
        const k = KEYS[m.key];
        send('Input.dispatchKeyEvent', { type: 'keyDown', ...k });
        send('Input.dispatchKeyEvent', { type: 'keyUp', ...k });
      } else if (m.type === 'back') {
        send('Runtime.evaluate', { expression: 'history.back()' });
      }
    });
  }
}

/** @type {Record<string, {key:string, code:string, windowsVirtualKeyCode:number, text?:string}>} */
const KEYS = {
  Enter: { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' },
  Backspace: { key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 },
  Tab: { key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 },
};

/** What sessions are told about the browser. */
export const BROWSER_NOTE = `# The browser\n\nYou have a browser on this server (the "browser" tools), signed in to the sites the person signed in to. Use it for pages that need a sign-in or that plain fetching cannot read. When a site needs the person - to sign in, or to pass a CAPTCHA or a check - do not try to get around it: ask them with AskUserQuestion, header exactly "Take over", saying which site and why, with the options "Done" and "Skip this site". They take over the browser from their phone and answer Done when they are through.`;
