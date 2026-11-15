// Copyright (c) 2026 Ordo contributors. SPDX-License-Identifier: GPL-3.0-or-later
(() => {
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const lang = document.documentElement.lang;
  const fmt = (v, d) => v.toLocaleString(lang, { minimumFractionDigits: d, maximumFractionDigits: d });
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const run = el => { el.classList.remove('run'); void el.offsetWidth; el.classList.add('run'); };

  // ---- Home build + time ruler (real times: frame ~200 ms, storage card 550 ms) ----
  const home = document.querySelector('[data-scene="home"]');
  const ruler = document.querySelector('[data-ruler]');
  let slow = 3;
  function playHome() {
    home.style.setProperty('--slow', slow);
    run(home);
    const out = ruler.querySelector('[data-out]'), fill = ruler.querySelector('[data-fill]');
    const span = 550 * slow, start = performance.now();
    const tick = now => {
      const ms = Math.min(550, (now - start) / slow);
      out.textContent = fmt(ms / 1000, 2) + ' s';
      fill.style.setProperty('--p', (ms / 700).toFixed(4));
      if (now - start < span) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }
  if (!still && home) {
    home.classList.add('armed');
    ruler.querySelector('[data-fill]').style.setProperty('--p', 0);
    // let the font settle so the first frame is the real one
    (document.fonts ? document.fonts.ready : Promise.resolve()).then(() => {
      const io = new IntersectionObserver(([e]) => {
        if (e.isIntersecting) { io.disconnect(); setTimeout(playHome, 250); }
      }, { threshold: 0.35 });
      io.observe(home);
    });
    const btn = ruler.querySelector('[data-replay]'), mode = ruler.querySelector('[data-mode]');
    btn.addEventListener('click', () => {
      slow = slow === 1 ? 3 : 1;
      mode.textContent = slow === 1 ? btn.dataset.modeReal : btn.dataset.modeSlow;
      btn.textContent = slow === 1 ? btn.dataset.slowtxt : btn.dataset.real;
      playHome();
    });
  } else if (ruler) {
    ruler.querySelector('[data-replay]').hidden = true;
    ruler.querySelector('[data-mode]').textContent = ruler.querySelector('[data-replay]').dataset.modeReal;
  }

  // ---- Scenes that play once when they come into view ----
  const scenes = [...document.querySelectorAll('[data-scene="ana"], [data-scene="vault"]')];
  if (!still && 'IntersectionObserver' in window) {
    scenes.forEach(s => s.classList.add('armed'));
    const io = new IntersectionObserver(entries => entries.forEach(e => {
      if (!e.isIntersecting) return;
      io.unobserve(e.target);
      run(e.target);
      const n = e.target.querySelector('[data-count]');
      if (n) {
        const to = parseFloat(n.dataset.count), t0 = performance.now();
        const step = now => {
          const p = Math.min(1, (now - t0) / 1300), k = 1 - Math.pow(1 - p, 3);
          n.textContent = fmt(to * k, 1) + ' GB';
          if (p < 1) requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
      }
    }), { threshold: 0.45 });
    scenes.forEach(s => io.observe(s));
  }

  // ---- AI command demo: sentence -> plan -> confirm -> undo bar ----
  const ai = document.querySelector('[data-scene="ai"]');
  const chips = [...document.querySelectorAll('.cmds button')];
  if (ai && chips.length) {
    const $ = s => ai.querySelector(s);
    const live = document.querySelector('[data-live]');
    const icons = { tidy: '#i-wand', trash: '#i-delete', move: '#i-folder', stop: '#i-block' };
    let token = 0, auto = true, visible = false, idx = 0;
    const show = async (i, byUser) => {
      const my = ++token, c = chips[i], d = c.dataset;
      idx = i;
      chips.forEach(b => b.setAttribute('aria-pressed', String(b === c)));
      const plan = $('[data-plan]');
      plan.className = 'plan ' + d.kind;
      $('[data-icon]').setAttribute('href', icons[d.kind]);
      $('[data-title]').textContent = d.title;
      $('[data-sub]').textContent = d.sub;
      $('[data-meta]').textContent = d.meta;
      $('[data-go]').textContent = d.go || '';
      $('[data-done]').textContent = d.done || '';
      const txt = $('[data-txt]');
      if (still) { txt.textContent = c.textContent; ai.dataset.s = 'plan'; live.textContent = [d.title, d.sub, d.meta].filter(Boolean).join('. '); return; }
      ai.dataset.s = 'type'; txt.textContent = '';
      await wait(byUser ? 150 : 500);
      for (const ch of c.textContent) {
        if (my !== token) return;
        txt.textContent += ch;
        await wait(26 + Math.random() * 30);
      }
      await wait(260); if (my !== token) return;
      ai.dataset.s = 'think';
      await wait(1500); if (my !== token) return;
      ai.dataset.s = 'plan';
      live.textContent = [d.title, d.sub, d.meta].filter(Boolean).join('. ');
      await wait(2600); if (my !== token) return;
      if (d.kind !== 'stop') { ai.dataset.s = 'done'; await wait(2400); if (my !== token) return; }
      if (auto && visible) show((i + 1) % chips.length);
    };
    chips.forEach((b, i) => b.addEventListener('click', () => { auto = false; show(i, true); }));
    if (still) show(0);
    else new IntersectionObserver(([e]) => {
      const was = visible; visible = e.isIntersecting;
      if (visible && !was && auto) show(idx);
      if (!visible) token++; // pause off-screen
    }, { threshold: 0.4 }).observe(ai);
  }

  // ---- Real recording: load and play only when seen ----
  const vid = document.querySelector('video[data-lazy]');
  if (vid) {
    if (still) vid.controls = true;
    else new IntersectionObserver(([e]) => {
      if (e.isIntersecting) { vid.preload = 'auto'; vid.play().catch(() => { vid.controls = true; }); }
      else vid.pause();
    }, { threshold: 0.5 }).observe(vid);
  }
})();
