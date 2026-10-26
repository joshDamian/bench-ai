// @ts-check
// What design-check asks the rendered page beyond the mechanical rules: the floor rules a
// render shows (an eyebrow, placeholder copy, an image with no alt, a viewport that will not
// zoom), the reflex tells generated UI falls into, and the finish details. Each function here
// is stringified and run inside the page, so it carries its own helpers and reaches for
// nothing outside itself. It returns hits by rule id; rules.mjs says each one's tier.

/**
 * @param {{ignore:string[], fontSizes:number[]}} opts
 * @returns {Array<{rule:string, selector:string, detail:string}>}
 */
export function pageTells(opts) {
  /** @type {Array<{rule:string, selector:string, detail:string}>} */
  const hits = [];
  /** @type {Map<string, number>} */
  const perRule = new Map();
  /** @param {string} rule @param {string} selector @param {string} detail */
  const hit = (rule, selector, detail) => {
    const n = (perRule.get(rule) || 0) + 1;
    perRule.set(rule, n);
    if (n <= 3) hits.push({ rule, selector, detail });
  };
  /** @param {any} el */
  const ignored = (el) => !!el.closest('[data-design-check-ignore]') || opts.ignore.some((sel) => {
    try {
      return !!el.closest(sel);
    } catch {
      return false;
    }
  });
  /** @param {any} el */
  const shown = (el) => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0.5 && r.height > 0.5;
  };
  /** @param {any} el */
  const where = (el) => {
    let s = el.tagName.toLowerCase();
    if (el.id) s += `#${el.id}`;
    for (const c of String(el.getAttribute('class') || '').trim().split(/\s+/).slice(0, 2)) if (c) s += `.${c}`;
    return s;
  };
  /** @param {any} el */
  const words = (el) => String(el.innerText ?? el.textContent ?? '').trim().replace(/\s+/g, ' ');
  /** @param {any} el */
  const own = (el) => Array.from(el.childNodes).filter((/** @type {any} */ n) => n.nodeType === 3).map((/** @type {any} */ n) => n.textContent).join(' ').trim().replace(/\s+/g, ' ');
  /** @param {string} v */
  const rgba = (v) => {
    const n = (String(v).match(/-?[\d.]+/g) || []).map(Number);
    if (n.length < 3) return null;
    const k = /^color\(srgb/.test(v) ? 255 : 1;
    return [n[0] * k, n[1] * k, n[2] * k, n.length > 3 ? n[3] : 1];
  };
  /** @param {number[]|null} c */
  const hsl = (c) => {
    if (!c) return null;
    const [r, g, b] = [c[0] / 255, c[1] / 255, c[2] / 255];
    const mx = Math.max(r, g, b);
    const mn = Math.min(r, g, b);
    const l = (mx + mn) / 2;
    let h = 0;
    let s = 0;
    if (mx !== mn) {
      const d = mx - mn;
      s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      h = (mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4) * 60;
    }
    return { h, s, l, a: c[3] };
  };
  const inHue = (/** @type {number} */ h, /** @type {number} */ a, /** @type {number} */ b) => (a <= b ? h >= a && h <= b : h >= a || h <= b);
  const purple = (/** @type {any} */ c) => c && c.a > 0.2 && inHue(c.h, 244, 295) && c.s >= 0.42 && c.l >= 0.3 && c.l <= 0.8;
  const cream = (/** @type {any} */ c) => c && inHue(c.h, 22, 62) && c.s >= 0.16 && c.l >= 0.88 && c.l < 0.995;
  const terracotta = (/** @type {any} */ c) => c && c.a > 0.5 && inHue(c.h, 6, 30) && c.s >= 0.38 && c.s <= 0.85 && c.l >= 0.42 && c.l <= 0.72;
  /** The colours in a value: `rgb(...)` and `color(srgb ...)` as a computed style writes them. @param {string} v */
  const colorsIn = (v) => (String(v).match(/rgba?\([^)]*\)|color\(srgb[^)]*\)/g) || []).map((c) => hsl(rgba(c)));
  /** The opaque background a box is seen on. @param {any} el */
  const ground = (el) => {
    for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
      const c = rgba(getComputedStyle(n).backgroundColor);
      if (c && c[3] >= 0.99) return c;
    }
    return [255, 255, 255, 1];
  };
  const CODE = 'code,pre,kbd,samp,var,textarea,input,select,[class*=code],[class*=terminal],[class*=diff],[class*=hljs],[class*=mono]';
  const HEADINGS = 'h1,h2,h3,h4,h5,h6,[role=heading]';
  const vw = document.documentElement.clientWidth;

  const all = Array.from(document.querySelectorAll('body *')).filter((el) => !ignored(el) && !['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'svg', 'path'].includes(el.tagName));
  const visible = all.filter(shown);
  const texty = visible.filter((el) => own(el).length > 0);

  // --- floor: what a render shows that a person relying on it would lose
  for (const h of visible.filter((el) => el.matches('h1,h2,h3'))) {
    const hs = parseFloat(getComputedStyle(h).fontSize);
    // The label sits just above the heading: its previous sibling, or the one before the
    // heading's own wrapper when the heading is the first thing in it.
    const before = h.previousElementSibling || (h.parentElement && h.parentElement.firstElementChild === h ? h.parentElement.previousElementSibling : null);
    if (!before || !shown(before) || before.matches(`${HEADINGS},time,label,figcaption,th,legend,nav,a,button,img,svg,picture,input,[role=status],[role=tablist]`) || before.querySelector('time,a,button,img,input,svg')) continue;
    const t = words(before);
    if (!t || t.length > 48 || t.split(' ').length > 6) continue;
    const cs = getComputedStyle(before);
    const size = parseFloat(cs.fontSize);
    const br = before.getBoundingClientRect();
    const hr = h.getBoundingClientRect();
    if (hr.top - br.bottom > 40 || br.right < hr.left || br.left > hr.right) continue;
    const caps = cs.textTransform === 'uppercase' || /small-caps/.test(cs.fontVariantCaps || cs.fontVariant) || (/[A-Z]{3}/.test(t) && t === t.toUpperCase());
    const tracked = parseFloat(cs.letterSpacing) >= size * 0.04;
    const pill = parseFloat(cs.borderRadius) >= br.height / 2 - 1 && (rgba(cs.backgroundColor)?.[3] || 0) > 0;
    if (size <= Math.min(16, hs * 0.6) && (caps || tracked || pill)) hit('eyebrow', where(before), `"${t}" (${size}px${caps ? ', capitals' : ''}${tracked ? ', tracked' : ''}${pill ? ', a pill' : ''}) announces the heading "${words(h).slice(0, 40)}" below it`);
  }
  for (const el of texty) {
    const m = /lorem ipsum|dolor sit amet|\b(John|Jane) Doe\b|\bAcme (Inc|Corp|Co)\b/i.exec(own(el));
    if (m) hit('placeholder', where(el), `"${m[0]}" on the page`);
  }
  for (const img of visible.filter((el) => el.matches('img,input[type=image]'))) if (!img.hasAttribute('alt')) hit('alt', where(img), `no alt attribute (${img.getAttribute('src') || 'no src'})`);
  const viewport = document.querySelector('meta[name=viewport]')?.getAttribute('content') || '';
  if (/user-scalable\s*=\s*(no|0)|maximum-scale\s*=\s*1(\.0)?(?![\d.])/i.test(viewport)) hit('no-zoom', 'meta[name=viewport]', `"${viewport}" stops the page being zoomed`);

  // --- reflex: the defaults
  /** A box drawn as a card: a fill unlike its ground, a border all round, or a shadow, on rounded corners. @param {any} el */
  const card = (el) => {
    if (el.matches('button,input,select,textarea,img,svg,video,pre,code,a,dialog,[role=dialog],html,body')) return false;
    /** @type {any} */ const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    if (r.width < 120 || r.height < 56 || parseFloat(cs.borderTopLeftRadius) < 4) return false;
    const fill = rgba(cs.backgroundColor);
    const parentGround = el.parentElement ? ground(el.parentElement) : [255, 255, 255, 1];
    const filled = !!fill && fill[3] > 0.05 && fill.slice(0, 3).some((v, i) => Math.abs(v - parentGround[i]) > 4);
    const bordered = ['Top', 'Right', 'Bottom', 'Left'].every((s) => parseFloat(cs[`border${s}Width`]) >= 1 && cs[`border${s}Style`] !== 'none');
    return filled || bordered || cs.boxShadow !== 'none';
  };
  const cards = visible.filter(card);
  const cardSet = new Set(cards);
  for (const c of cards) {
    for (let n = c.parentElement; n && n !== document.body; n = n.parentElement) {
      if (cardSet.has(n)) {
        hit('nested-cards', where(c), `a card inside ${where(n)}`);
        break;
      }
    }
  }
  // Rows: three or more siblings of one width and height side by side.
  const rows = new Set(visible.map((el) => el.parentElement).filter(Boolean));
  for (const parent of rows) {
    const kids = Array.from(/** @type {any} */ (parent).children).filter((k) => shown(k) && !ignored(k));
    if (kids.length < 3) continue;
    const rects = kids.map((k) => k.getBoundingClientRect());
    const top = rects[0].top;
    const row = kids.filter((_, i) => Math.abs(rects[i].top - top) < 4 && Math.abs(rects[i].width - rects[0].width) < 6 && Math.abs(rects[i].height - rects[0].height) <= rects[0].height * 0.15);
    if (row.length < 3 || rects[0].width > vw * 0.5) continue;
    const tiled = row.filter((k) => {
      const first = Array.from(k.querySelectorAll('*')).find((d) => shown(d));
      if (!first) return false;
      const fr = first.getBoundingClientRect();
      const cs = getComputedStyle(first);
      const square = fr.width >= 24 && fr.width <= 72 && Math.abs(fr.width - fr.height) <= fr.width * 0.2;
      const tile = (rgba(cs.backgroundColor)?.[3] || 0) > 0.05 || parseFloat(cs.borderTopWidth) >= 1;
      const above = Array.from(k.querySelectorAll('h2,h3,h4,h5,h6,strong,b,[role=heading]')).some((t) => t.getBoundingClientRect().top >= fr.bottom - 2);
      return square && tile && above && (first.querySelector('svg,img,i') || first.matches('svg,img,i') || own(first).length <= 2);
    });
    if (tiled.length >= 3) hit('icon-tile-grid', where(parent), `${tiled.length} same-size cards in a row, each led by an icon in a tile above its heading`);
    const metrics = row.filter((k) => {
      const texts = Array.from(k.querySelectorAll('*')).filter((d) => shown(d) && own(d));
      const big = texts.find((d) => /\d/.test(own(d)) && /^[\d$€£¥+~<>≈.,%\s×xkKmMbB/-]+$/.test(own(d)) && parseFloat(getComputedStyle(d).fontSize) >= 28);
      return big && texts.some((d) => d !== big && parseFloat(getComputedStyle(d).fontSize) * 1.8 <= parseFloat(getComputedStyle(big).fontSize));
    });
    if (metrics.length >= 3) hit('hero-metric', where(parent), `${metrics.length} big numbers over small labels, side by side`);
    const bs = row.filter((k) => {
      const cs = getComputedStyle(k);
      const blur = Math.max(0, ...[...cs.boxShadow.matchAll(/(-?[\d.]+)px\s+(-?[\d.]+)px\s+([\d.]+)px/g)].map((m) => Number(m[3])));
      return parseFloat(cs.borderTopWidth) >= 0.5 && parseFloat(cs.borderTopWidth) <= 1.5 && cs.borderTopStyle !== 'none' && blur >= 16;
    });
    if (bs.length >= 3) hit('border-shadow-card', where(parent), `${bs.length} cards in a row, each with a hairline border and a wide soft shadow`);
  }

  let monoCount = 0;
  /** @type {string[]} */ const monoSamples = [];
  /** @type {Map<string, number>} */ const families = new Map();
  let tracked = 0;
  /** @type {string[]} */ const trackedSamples = [];
  /** @type {string[]} */ const numbers = [];
  /** @type {Set<number>} */ const offScale = new Set();
  /** @type {string[]} */ const offSamples = [];
  for (const el of texty) {
    const cs = getComputedStyle(el);
    const t = own(el);
    const size = parseFloat(cs.fontSize);
    const first = cs.fontFamily.split(',')[0].trim().replace(/^["']|["']$/g, '');
    const mono = /mono|courier|consolas|menlo|monaco/i.test(first) || first === 'monospace';
    if (mono && !el.closest(CODE) && !/[{}()<>=;/\\_#@$]|\.\w|^[\da-f]{6,}$|^[\d\s.,:%+-]+$/i.test(t)) {
      monoCount++;
      if (monoSamples.length < 3) monoSamples.push(`"${t.slice(0, 24)}"`);
    }
    if (!mono) families.set(first, (families.get(first) || 0) + t.length);
    const caps = cs.textTransform === 'uppercase' || /small-caps/.test(cs.fontVariantCaps || '');
    if (size <= 15 && caps && parseFloat(cs.letterSpacing) >= size * 0.04 && /[a-z]{3}/i.test(t)) {
      tracked++;
      if (trackedSamples.length < 3) trackedSamples.push(`"${t.slice(0, 24)}"`);
    }
    if (/^[(\[]?0\d[)\].]?$|^(step|phase|chapter|part|no\.?)\s*0\d$|^0\d\s*[/—–-]\s*0?\d+$/i.test(t) && !el.closest('td,th,time,table')) numbers.push(`"${t}"`);
    if (cs.backgroundClip === 'text' || /** @type {any} */ (cs).webkitBackgroundClip === 'text') {
      for (let n = /** @type {any} */ (el); n; n = n.parentElement) {
        if (/gradient/.test(getComputedStyle(n).backgroundImage)) {
          hit('gradient-text', where(el), `"${t.slice(0, 40)}" is filled with a gradient`);
          break;
        }
      }
    }
    if (opts.fontSizes.length && !opts.fontSizes.some((s) => Math.abs(s - size) < 0.5)) {
      offScale.add(Math.round(size * 100) / 100);
      if (offSamples.length < 3) offSamples.push(`${where(el)} ${size}px`);
    }
    if ((t.match(/\s[·•]\s/g) || []).length >= 2) hit('middle-dot-meta', where(el), `"${t.slice(0, 50)}"`);
    if (/[\u{1F300}-\u{1FAFF}\u{2600}-\u{26FF}]/u.test(t) && el.closest('button,a,h1,h2,h3,h4,li,nav,label')) hit('emoji-icons', where(el), `"${t.slice(0, 30)}"`);
    const stock = /\b(unlock(s|ing)?|unleash|supercharge[sd]?|seamless(ly)?|elevate[sd]?|empower(s|ing)?|revolutioni[sz]e|game[- ]chang(er|ing)|cutting[- ]edge|next[- ]gen(eration)?|world[- ]class|best[- ]in[- ]class|industry[- ]leading|all[- ]in[- ]one|effortless(ly)?|blazing(ly)? fast|lightning[- ]fast|reimagined|mission[- ]critical|future[- ]proof)\b/i.exec(t);
    if (stock) hit('stock-phrase', where(el), `"${stock[0]}" in "${t.slice(0, 50)}"`);
  }
  if (monoCount >= 3) hit('mono-costume', 'body', `${monoCount} labels in a monospace face that are not code or data: ${monoSamples.join(', ')}`);
  if (tracked >= 3) hit('tracked-caps', 'body', `${tracked} small tracked capital labels: ${trackedSamples.join(', ')}`);
  if (numbers.length >= 2) hit('section-numbers', 'body', `index numbers as markers: ${numbers.slice(0, 4).join(', ')}`);
  if (offScale.size) hit('off-scale-type', offSamples[0].split(' ')[0], `${offScale.size} font size${offScale.size === 1 ? '' : 's'} not on the type scale (${[...offScale].slice(0, 6).join(', ')}px), e.g. ${offSamples.join(', ')}`);
  const used = [...families.entries()].filter(([, n]) => n >= 40).map(([f]) => f);
  if (used.length > 2) hit('font-count', 'body', `${used.length} typefaces in running use: ${used.join(', ')}`);
  const lead = [document.body, document.querySelector('h1')].filter(Boolean).map((el) => getComputedStyle(/** @type {any} */ (el)).fontFamily.split(',')[0].trim().replace(/^["']|["']$/g, ''));
  for (const f of new Set(lead)) if (/^(Inter|Roboto|Open Sans|Poppins|Montserrat|Lato|Nunito|Raleway|Space Grotesk|Plus Jakarta Sans|Geist|DM Sans|Manrope|Outfit|Sora|Fraunces|Playfair Display|Instrument Serif|Newsreader)$/i.test(f)) hit('overused-font', 'body', `${f}, the face every generator reaches for`);

  // Colour: the AI palette and the cream-and-terracotta look.
  const pageBg = hsl(ground(document.body));
  const big = visible.filter((el) => {
    const r = el.getBoundingClientRect();
    return r.width * r.height >= vw * 400;
  });
  const accents = visible.filter((el) => el.matches('button,a,[role=button],h1,h2'));
  let purpleAt = '';
  for (const el of [...big, ...accents]) {
    const cs = getComputedStyle(el);
    const bg = hsl(rgba(cs.backgroundColor));
    if ((bg && bg.a > 0.5 && purple(bg)) || colorsIn(cs.backgroundImage).some(purple) || (el.matches('h1,h2') && purple(hsl(rgba(cs.color))))) {
      purpleAt = where(el);
      break;
    }
  }
  if (purpleAt) hit('ai-palette', purpleAt, 'violet / indigo carrying the page: the generated-UI palette');
  if (cream(pageBg)) {
    const t = accents.find((el) => {
      const cs = getComputedStyle(el);
      return terracotta(hsl(rgba(cs.backgroundColor))) || terracotta(hsl(rgba(cs.color)));
    });
    if (t) hit('cream-terracotta', where(t), 'a cream ground with a terracotta accent');
  }

  let glass = 0;
  for (const el of visible) {
    /** @type {any} */ const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    if (cs.backdropFilter && cs.backdropFilter !== 'none') glass++;
    // A thick coloured stripe down one side of a box.
    for (const [side, other] of [['Left', 'Right'], ['Right', 'Left']]) {
      const wid = parseFloat(cs[`border${side}Width`]);
      const c = hsl(rgba(cs[`border${side}Color`]));
      if (wid >= 2 && cs[`border${side}Style`] === 'solid' && parseFloat(cs[`border${other}Width`]) <= 1 && parseFloat(cs.borderTopWidth) <= 1 && c && c.s > 0.25 && c.a > 0.5 && r.height >= 32 && r.width >= 120 && !el.matches('blockquote')) {
        hit('side-tab', where(el), `a ${wid}px coloured border down its ${side.toLowerCase()} side`);
      }
    }
    // A glow: a zero-offset coloured shadow.
    for (const layer of `${cs.boxShadow},${cs.textShadow}`.split(/,(?![^(]*\))/)) {
      const m = /(rgba?\([^)]*\)|color\([^)]*\))\s+0px\s+0px\s+([\d.]+)px/.exec(layer);
      const c = m ? hsl(rgba(m[1])) : null;
      if (m && Number(m[2]) >= 8 && c && c.s > 0.3 && c.a > 0.15) {
        hit('dark-glow', where(el), `a ${m[2]}px glow in ${m[1]}`);
        break;
      }
    }
    const timing = `${cs.transitionTimingFunction} ${cs.animationTimingFunction}`;
    if ([...timing.matchAll(/cubic-bezier\(([^)]*)\)/g)].some((m) => {
      const n = m[1].split(',').map(Number);
      return n[1] > 1 || n[3] > 1 || n[1] < 0 || n[3] < 0;
    })) hit('bounce-easing', where(el), `overshoot easing: ${timing.match(/cubic-bezier\([^)]*\)/)?.[0]}`);
    const props = cs.transitionProperty.split(',').map((/** @type {string} */ s) => s.trim());
    const durs = cs.transitionDuration.split(',').map((/** @type {string} */ s) => parseFloat(s) * (/ms/.test(s) ? 0.001 : 1));
    if (props.some((/** @type {string} */ p, /** @type {number} */ i) => p === 'all' && (durs[i % durs.length] || 0) > 0)) hit('transition-all', where(el), `transition-property: all (${cs.transitionDuration})`);
    if (el.matches('a,button,[role=button],input,select,textarea,summary') && Math.max(...durs) > 0.3) hit('slow-feedback', where(el), `feedback over ${Math.round(Math.max(...durs) * 1000)}ms`);
    if (cs.animationName !== 'none' && cs.animationIterationCount === 'infinite') {
      const radius = parseFloat(cs.borderTopLeftRadius);
      if (r.width <= 16 && r.height <= 16 && radius >= Math.min(r.width, r.height) / 2 - 1 && (rgba(cs.backgroundColor)?.[3] || 0) > 0.2 && parseFloat(cs.borderTopWidth) === 0) hit('pulsing-dot', where(el), `a ${Math.round(r.width)}px dot animating forever (${cs.animationName})`);
      const parent = el.parentElement;
      if (parent && r.width > parent.clientWidth * 1.3 && getComputedStyle(parent).overflowX !== 'visible') hit('marquee', where(el), `a strip ${Math.round(r.width)}px wide moving forever inside ${Math.round(parent.clientWidth)}px`);
    }
    if (el.tagName === 'MARQUEE') hit('marquee', where(el), '<marquee>');
  }
  if (glass >= 2) hit('glass', 'body', `${glass} surfaces blurred with backdrop-filter`);

  for (const el of visible.filter((e) => e.matches('a,button,[role=button]'))) {
    const t = words(el);
    if (/\S\s*(→|↗|➔|⟶|->)$/.test(t)) hit('arrow-link', where(el), `"${t.slice(0, 40)}"`);
    if (/^(learn more|get started|click here|read more|submit|discover more|explore now|find out more)\s*[.!→↗]?$/i.test(t)) hit('generic-label', where(el), `"${t}"`);
  }
  for (const h of visible.filter((el) => el.matches('h1,h2,h3'))) {
    const hcs = getComputedStyle(h);
    const ht = words(h);
    for (const k of Array.from(h.querySelectorAll('span,em,i,strong,b,mark,u'))) {
      const t = words(k);
      if (!t || t.split(' ').length > 3 || t.length >= ht.length * 0.6) continue;
      const cs = getComputedStyle(k);
      const differs = cs.color !== hcs.color || (cs.fontStyle !== hcs.fontStyle && cs.fontStyle === 'italic') || cs.fontFamily !== hcs.fontFamily || (rgba(cs.backgroundColor)?.[3] || 0) > 0.1 || /gradient/.test(cs.backgroundImage);
      if (differs) {
        hit('accent-word', where(h), `"${t}" set apart inside "${ht.slice(0, 40)}"`);
        break;
      }
    }
    // Lines: the box's height over its line height.
    if (h.matches('h1,h2')) {
      const lh = parseFloat(hcs.lineHeight) || parseFloat(hcs.fontSize) * 1.2;
      const lines = Math.round(h.getBoundingClientRect().height / lh);
      if (lines > 2) hit('long-headline', where(h), `"${ht.slice(0, 40)}" runs to ${lines} lines at ${vw}px`);
    }
  }
  const body = String(document.body.innerText || '');
  const dashes = (body.match(/—/g) || []).length;
  if (dashes >= 5) hit('em-dash', 'body', `${dashes} em dashes in the page's text`);

  // --- finish
  const heads = visible.filter((el) => el.matches('h1,h2,h3'));
  const unbalanced = heads.filter((el) => !/balance|pretty/.test(getComputedStyle(el).textWrapStyle || getComputedStyle(el).textWrap || ''));
  if (unbalanced.length) hit('text-wrap', where(unbalanced[0]), `${unbalanced.length} heading${unbalanced.length === 1 ? '' : 's'} without text-wrap: balance`);
  const paras = visible.filter((el) => el.matches('p') && words(el).length > 80 && !/pretty|balance/.test(getComputedStyle(el).textWrapStyle || getComputedStyle(el).textWrap || ''));
  if (paras.length) hit('text-wrap', where(paras[0]), `${paras.length} paragraph${paras.length === 1 ? '' : 's'} without text-wrap: pretty`);
  const cells = visible.filter((el) => el.matches('td,th') && /\d/.test(words(el)) && !/tabular-nums/.test(getComputedStyle(el).fontVariantNumeric));
  if (cells.length >= 3) hit('tabular-nums', where(cells[0]), `${cells.length} table cells with figures in proportional numerals`);
  /** @type {Set<string>} */
  const surfaces = new Set();
  const walk = (/** @type {any} */ rule) => {
    if (rule.selectorText && /::selection/.test(rule.selectorText)) surfaces.add('::selection');
    if (rule.selectorText && /::-webkit-scrollbar/.test(rule.selectorText)) surfaces.add('scrollbar-color');
    if (rule.style) for (const p of ['caret-color', 'scrollbar-color', 'accent-color']) if (rule.style.getPropertyValue(p)) surfaces.add(p);
    if (rule.cssRules) for (const r of rule.cssRules) walk(r);
  };
  for (const sheet of Array.from(document.styleSheets)) {
    try {
      for (const r of Array.from(sheet.cssRules)) walk(r);
    } catch {
      /* another origin's sheet */
    }
  }
  const missing = ['::selection', 'caret-color', 'scrollbar-color', 'accent-color'].filter((s) => !surfaces.has(s));
  if (missing.length) hit('browser-surfaces', ':root', `left at the browser's default: ${missing.join(', ')}`);
  for (const el of visible) {
    const cs = getComputedStyle(el);
    const outer = parseFloat(cs.borderTopLeftRadius);
    const pad = parseFloat(cs.paddingTop);
    if (!(outer > 0) || !(pad > 0) || pad > 24) continue;
    const child = Array.from(el.children).find((k) => shown(k));
    if (!child) continue;
    const inner = parseFloat(getComputedStyle(child).borderTopLeftRadius);
    const gap = child.getBoundingClientRect().top - el.getBoundingClientRect().top;
    if (inner > 0 && Math.abs(inner - outer) < 0.5 && Math.abs(gap - pad - parseFloat(cs.borderTopWidth)) < 2) hit('concentric-radius', where(el), `${outer}px outside, ${inner}px inside, ${pad}px apart: the outer should be ${inner + pad}px`);
  }
  let last = 0;
  for (const h of Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6')).filter(shown)) {
    const level = Number(h.tagName[1]);
    if (last && level > last + 1) hit('heading-skip', where(h), `h${last} then h${level}`);
    last = level;
  }
  for (const img of visible.filter((el) => el.matches('img'))) if (!(img.hasAttribute('width') && img.hasAttribute('height')) && getComputedStyle(img).aspectRatio === 'auto') hit('img-size', where(img), 'no width and height, and no aspect-ratio');
  if (!document.documentElement.getAttribute('lang')) hit('doc-meta', 'html', 'no lang attribute');
  if (!document.title.trim()) hit('doc-meta', 'head', 'no <title>');
  for (const el of texty.filter((e) => e.matches('p,li,dd,blockquote,figcaption'))) {
    const t = own(el);
    if (/\w\.\.\.(\s|$)/.test(t)) hit('typography-marks', where(el), `"..." in "${t.slice(0, 40)}"`);
    else if (/(^|\s)"[^"]+"/.test(t)) hit('typography-marks', where(el), `straight quotes in "${t.slice(0, 40)}"`);
  }
  return hits;
}

/**
 * The page as someone whose scripts failed sees it: text in the flow of the page that is
 * fully transparent. Menus and tooltips sit out of the flow and are left alone. When the
 * page has no text at all without scripts it is an app drawn by script, and this cannot judge it.
 * @param {{ignore:string[]}} opts
 */
export function pageAtRest(opts) {
  const text = String(document.body?.innerText || '').trim();
  if (text.length < 20) return { drawnByScript: true, hidden: [] };
  /** @type {string[]} */
  const hidden = [];
  for (const el of Array.from(document.querySelectorAll('body *'))) {
    if (hidden.length >= 3) break;
    if (opts.ignore.some((sel) => {
      try {
        return !!el.closest(sel);
      } catch {
        return false;
      }
    }) || el.closest('[aria-hidden=true],[hidden],dialog:not([open]),template,noscript')) continue;
    const own = Array.from(el.childNodes).filter((n) => n.nodeType === 3).map((n) => n.textContent).join('').trim();
    if (own.split(/\s+/).length < 2) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) continue;
    let k = 1;
    let flow = true;
    for (let n = /** @type {any} */ (el); n && n.nodeType === 1; n = n.parentElement) {
      const cs = getComputedStyle(n);
      k *= parseFloat(cs.opacity);
      if (cs.position === 'absolute' || cs.position === 'fixed') flow = false;
    }
    if (k < 0.02 && flow) {
      let s = el.tagName.toLowerCase();
      for (const c of String(el.getAttribute('class') || '').trim().split(/\s+/).slice(0, 2)) if (c) s += `.${c}`;
      hidden.push(`${s} "${own.slice(0, 40)}"`);
    }
  }
  return { drawnByScript: false, hidden };
}

/** Whether a dark page says so to the browser, so its scrollbars and fields come out dark too. */
export function pageScheme() {
  const cs = getComputedStyle(document.documentElement);
  const bg = (String(getComputedStyle(document.body).backgroundColor).match(/[\d.]+/g) || []).map(Number);
  const dark = bg.length >= 3 && (bg.length < 4 || bg[3] > 0.5) && (0.2126 * bg[0] + 0.7152 * bg[1] + 0.0722 * bg[2]) / 255 < 0.25;
  return { dark, scheme: cs.colorScheme };
}
