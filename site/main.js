/* Page behaviour: the 3D phones, the screen tabs, the site's colour presets, the gallery
   and the latest version for the download section. */
(function () {
  "use strict";
  const S = window.DroidScreens;
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // ---- colour presets: the app's own, remembered ----
  const themeButtons = document.querySelectorAll("[data-set-theme]");
  // Gallery renders: background near brightness 0.04, brightest text near 0.78 (measured).
  // The filter's tables run 0..1 in 16 steps; each maps that brightness onto the theme.
  const RENDER_BG = 0.04;
  const RENDER_FG = 0.78;
  function retint() {
    const cs = getComputedStyle(document.body);
    const rgb = (v) => { const h = cs.getPropertyValue(v).trim().replace("#", ""); return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255); };
    const bg = rgb("--scr-bg");
    const fg = rgb("--scr-fg");
    const steps = 16;
    ["r", "g", "b"].forEach((ch, c) => {
      const table = [];
      for (let i = 0; i <= steps; i++) {
        const t = Math.min(1, Math.max(0, (i / steps - RENDER_BG) / (RENDER_FG - RENDER_BG)));
        table.push((bg[c] + (fg[c] - bg[c]) * t).toFixed(4));
      }
      const fn = document.getElementById("retint-" + ch);
      if (fn) fn.setAttribute("tableValues", table.join(" "));
    });
    document.querySelectorAll(".grid li button, .lightbox").forEach((el) => { el.style.background = cs.getPropertyValue("--scr-bg"); });
  }

  function setTheme(t) {
    document.body.dataset.theme = t;
    retint();
    themeButtons.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.setTheme === t)));
    try { localStorage.setItem("droidtty-theme", t); } catch (e) { /* private mode: not remembered */ }
  }
  themeButtons.forEach((b) => b.addEventListener("click", () => setTheme(b.dataset.setTheme)));
  let saved = null;
  try { saved = localStorage.getItem("droidtty-theme"); } catch (e) { saved = "green"; /* no storage: asking on every visit would nag, so skip the picker */ }
  if (saved) setTheme(saved);
  retint();

  // First visit: ask for a colour once. Closing it any way keeps green p1 and remembers that.
  const picker = document.getElementById("picker");
  if (!saved && picker && picker.showModal) {
    picker.addEventListener("click", (e) => {
      const opt = e.target.closest("[data-pick]");
      if (opt) { setTheme(opt.dataset.pick); picker.close(); }
      else if (e.target === picker) picker.close();
    });
    // Previewing: the page behind shows each colour as the visitor moves through the list.
    picker.querySelectorAll("[data-pick]").forEach((b) => {
      const preview = () => { document.body.dataset.theme = b.dataset.pick; retint(); };
      b.addEventListener("pointerenter", preview);
      b.addEventListener("focus", preview);
    });
    picker.addEventListener("close", () => {
      let chosen = null;
      try { chosen = localStorage.getItem("droidtty-theme"); } catch (e) { /* stays unsaved */ }
      setTheme(chosen || "green");
    });
    picker.showModal();
    picker.querySelector("[data-pick]").focus();
  }

  // ---- phones ----
  const phones = Array.from(document.querySelectorAll(".phone"));
  const screens = new Map();
  phones.forEach((p) => {
    const s = S.buildPhone(p);
    screens.set(p, { el: s, stop: S.render(s, p.dataset.screen) });
  });

  // Each phone leans toward the pointer; on touch screens it follows the scroll instead.
  const base = { rx: 6, ry: -18 };
  function lean(p, rx, ry) {
    p.style.setProperty("--rx", rx.toFixed(2) + "deg");
    p.style.setProperty("--ry", ry.toFixed(2) + "deg");
  }
  if (!reduced) {
    const fine = matchMedia("(pointer: fine)").matches;
    if (fine) {
      window.addEventListener("pointermove", (e) => {
        const x = e.clientX / innerWidth - 0.5;
        const y = e.clientY / innerHeight - 0.5;
        phones.forEach((p) => { if (!p.dataset.turning) lean(p, base.rx - y * 10, base.ry + x * 26); });
      }, { passive: true });
    } else {
      const onScroll = () => {
        phones.forEach((p) => {
          if (p.dataset.turning) return;
          const r = p.getBoundingClientRect();
          const t = (r.top + r.height / 2) / innerHeight - 0.5;
          lean(p, base.rx + t * 10, base.ry + t * 22);
        });
      };
      window.addEventListener("scroll", onScroll, { passive: true });
      onScroll();
    }
  }

  // ---- screen tabs: the stage phone turns around to the chosen screen ----
  const NOTES = {
    home: "home: pinned apps as text, in front of the readout. swipe up for the drawer, down for the shade.",
    drawer: "app drawer: type and it narrows by prefix, initials or any part of the name; a single match opens by itself.",
    aod: "always-on display: playing the app's own frames. it cycles through a few of the 19 looks.",
    boot: "boot: what plays when the screen turns on while locked. these are the app's real boot frames.",
    lock: "lock screen: droidtty's own pin lock, asking in the boot's voice. the keypad shuffles each time; tap it here too.",
    settings: "settings: long-press home. presets come with a matching always-on look and lock screen.",
  };
  const stage = document.querySelector(".stage-phone");
  const note = document.getElementById("stage-note");
  const tabs = Array.from(document.querySelectorAll("[role=tab]"));
  function show(name, focus) {
    tabs.forEach((t) => {
      const on = t.dataset.tab === name;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      if (on && focus) t.focus();
    });
    note.textContent = NOTES[name];
    const entry = screens.get(stage);
    const swap = () => {
      entry.stop();
      entry.stop = S.render(entry.el, name);
    };
    if (reduced) { swap(); return; }
    // Half a turn to show the back, swap the screen, and turn back.
    stage.dataset.turning = "1";
    lean(stage, 4, 180 + base.ry);
    setTimeout(swap, 300);
    setTimeout(() => { lean(stage, base.rx, base.ry); delete stage.dataset.turning; }, 320);
  }
  tabs.forEach((t, i) => {
    t.tabIndex = i === 0 ? 0 : -1;
    t.addEventListener("click", () => show(t.dataset.tab));
    t.addEventListener("keydown", (e) => {
      const d = e.key === "ArrowDown" || e.key === "ArrowRight" ? 1 : e.key === "ArrowUp" || e.key === "ArrowLeft" ? -1 : 0;
      if (!d) return;
      e.preventDefault();
      show(tabs[(i + d + tabs.length) % tabs.length].dataset.tab, true);
    });
  });
  // Name the look that's playing, so a visitor knows what they're seeing.
  stage.addEventListener("look", (e) => {
    const sel = tabs.find((t) => t.getAttribute("aria-selected") === "true");
    if (sel && (sel.dataset.tab === "aod" || sel.dataset.tab === "boot")) {
      note.textContent = NOTES[sel.dataset.tab] + " now: " + e.detail.toLowerCase() + ".";
    }
  });

  // ---- gallery ----
  const LOOKS = [
    ["fire", "doom fire", 1], ["starfield", "starfield", 1], ["life", "game of life", 1], ["cube", "spinning cube", 1],
    ["rain", "rain on glass", 1], ["aquarium", "aquarium", 1], ["scope", "oscilloscope", 1], ["pong", "pong", 1],
    ["plasma", "plasma", 1], ["helix", "dna helix", 1], ["matrix", "matrix rain", 1],
    ["clock", "minimal clock", 0], ["status", "terminal status", 0], ["prompt", "prompt", 0], ["log", "log tail", 0],
    ["neofetch", "neofetch", 0], ["tty", "tty clock", 0], ["htop", "htop meters", 0], ["binary", "binary clock", 0],
  ];
  const LOCKS = [
    ["arch", "arch linux"], ["bootline", "bootline"], ["bios", "bios flash"], ["dos", "dos prompt"], ["login", "unix login"],
    ["grub", "grub menu"], ["c64", "commodore 64"], ["panic", "kernel panic"], ["ssh", "ssh login"], ["wakeup", "wake up"],
  ];
  // Captured on a phone running DroidTTY (Nothing, Android 16), not rendered.
  const SHOTS = [
    ["home-green", "home · green p1"], ["home-space", "home · deep space"], ["home-arcade", "home · arcade"],
    ["home-mono", "home · white crt"], ["settings-presets", "settings · presets"],
  ];
  const items = [
    ...SHOTS.map(([f, t]) => ({ kind: "real", still: "assets/shots/" + f + ".webp", full: "assets/shots/" + f + ".webp", title: t, tag: "screenshot" })),
    ...LOOKS.map(([f, t, anim]) => ({ kind: "aod", still: "assets/gallery/aod/" + f + ".png", full: "assets/gallery/aod/" + f + (anim ? ".gif" : ".png"), title: t, tag: anim ? "animated" : "always-on" })),
    ...LOCKS.map(([f, t]) => ({ kind: "lock", still: "assets/gallery/lock/" + f + ".png", full: "assets/gallery/lock/" + f + ".gif", title: t, tag: "lock" })),
  ];
  const grid = document.getElementById("gallery-grid");
  grid.innerHTML = items.map((it, i) =>
    '<li data-kind="' + it.kind + '"' + (it.kind === "real" ? ' class="real"' : "") + '><button type="button" data-i="' + i + '" aria-label="' + it.title + ', ' + it.tag + ', open larger">' +
    '<img src="' + it.still + '" alt="' + it.title + ' (' + it.tag + ')" loading="lazy" width="360" height="760"></button>' +
    '<div class="cap"><b>' + it.title + "</b><span>" + it.tag + "</span></div></li>").join("");
  retint();
  const box = document.getElementById("lightbox");
  const boxImg = document.getElementById("lightbox-img");
  const boxCap = document.getElementById("lightbox-cap");
  grid.addEventListener("click", (e) => {
    const b = e.target.closest("button[data-i]");
    if (!b) return;
    const it = items[+b.dataset.i];
    boxImg.src = it.full;
    boxImg.classList.toggle("real", it.kind === "real");
    boxImg.alt = it.title;
    boxCap.textContent = it.title + " · " + it.tag;
    if (box.showModal) box.showModal(); else window.open(it.full);
  });
  box.addEventListener("click", (e) => { if (e.target === box) box.close(); });
  document.querySelectorAll("[data-filter]").forEach((b) => b.addEventListener("click", () => {
    document.querySelectorAll("[data-filter]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
    grid.querySelectorAll("li").forEach((li) => { li.hidden = b.dataset.filter !== "all" && li.dataset.kind !== b.dataset.filter; });
  }));

  // ---- latest version: the copy of the app's update file served with the site ----
  // (GitHub's download redirect sends no CORS header, so browsers can't read it there.)
  const ver = document.getElementById("dl-version");
  const notes = document.getElementById("dl-notes");
  fetch("version.json", { cache: "no-cache" })
    .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
    .then((v) => { ver.textContent = v.versionName; notes.textContent = v.notes || ""; })
    .catch(() => { ver.textContent = "see all releases"; });
})();
