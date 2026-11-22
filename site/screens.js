/* The phone screens. Home, drawer, settings and the pin lock are drawn in HTML after
   the app's own layout (green p1 preset). The always-on display and boot screens play
   the app's real frames, dumped by its GalleryDump test into assets/frames.json. */
(function () {
  "use strict";

  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const pad = (n) => String(n).padStart(2, "0");
  const now = () => new Date();
  const hhmm = (d) => pad(d.getHours()) + ":" + pad(d.getMinutes());
  const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const dateLine = (d) => DAYS[d.getDay()] + " " + d.getDate() + " " + MONTHS[d.getMonth()];

  function status() {
    return '<div class="status"><span>' + hhmm(now()) + "</span><span>▲ ▮▮▮ 76%</span></div>";
  }

  // The readout behind home: the app's own row format, sample values.
  const LOG = [
    "DROIDTTY INDUSTRIES OPERATING SYSTEM",
    "COPYRIGHT 2026 DROIDTTY INC.",
    "      - PACMAN -",
    "",
    "up    2d05h17m  awake 31%",
    "os    Android 16 · API 36",
    "cpu   8 cores  0.60-2.85 GHz",
    "mem   3.1G / 7.5G avail",
    "bat   76%  discharging  31°C",
    "net   wifi  ↓1.2M/s ↑48K/s",
    "── procs · by cpu ──────────",
    "  PID  CPU  RES  NAME",
    " 1287 12.4 412M system_server",
    " 3311  6.1 238M Firefox",
    " 2204  3.0 154M Signal",
    "── connections ─────────────",
    "Firefox   142.250.183.4:443",
    "Signal    76.223.92.165:443",
    "────────────────────────────",
    "21:31 net   cell → wifi",
    "21:35 fg    Firefox",
    "21:38 ntf   Signal",
  ];

  function home() {
    const d = now();
    const apps = ["PHONE", "MESSAGES", "FIREFOX", "CAMERA", "SIGNAL", "MAPS"];
    return (
      status() +
      '<div class="s-home">' +
      '<pre class="s-log">' + LOG.map(esc).join("\n") + "</pre>" +
      '<div class="s-front">' +
      '<div class="s-clock">' + hhmm(d) + "</div>" +
      '<div class="s-date">' + dateLine(d).toUpperCase() + "</div>" +
      '<ul class="s-apps">' + apps.map((a) => "<li>&gt; " + a + "</li>").join("") + "</ul>" +
      "</div></div>"
    );
  }

  const ALL_APPS = ["Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Files", "Firefox", "Gallery", "Gmail", "Maps", "Messages", "Music", "Phone", "Settings", "Signal", "Spotify", "Termux", "Weather", "YouTube"];
  let drawerQuery = "";
  function drawer() {
    const q = drawerQuery.toLowerCase();
    const list = ALL_APPS.filter((a) => a.toLowerCase().startsWith(q) || a.toLowerCase().includes(q));
    return (
      status() +
      '<div class="s-drawer">' +
      '<div class="s-search">&gt; ' + (esc(drawerQuery) || '<span class="s-dim">search</span>') + '<span class="s-cur">_</span></div>' +
      "<ul>" + list.map((a) => "<li>" + a.toUpperCase() + "</li>").join("") + "</ul>" +
      (list.length === 1 ? '<div class="s-dim">single match: opening ' + esc(list[0]) + "…</div>" : "") +
      "</div>"
    );
  }

  function settings() {
    const row = (l, v) => '<div class="s-row"><span>' + l + "</span><b>" + v + "</b></div>";
    return (
      status() +
      '<div class="s-settings">' +
      '<div class="s-head">droidtty --config</div>' +
      '<div class="s-dim">long-press home to open</div>' +
      '<div class="s-sec"># start here</div>' + row("get started", "all set ›") +
      '<div class="s-sec"># screens</div>' +
      row("appearance", "presets · fonts ›") + row("always-on, lock, shade", "neofetch ›") +
      row("log", "11 rows ›") + row("shizuku", "connected ›") +
      '<div class="s-sec"># presets</div>' +
      '<div class="s-preset on">* &gt; GREEN P1<i>aod neofetch · lock arch linux</i></div>' +
      '<div class="s-preset amber">&nbsp; C:\\&gt; AMBER P3<i>aod pong · lock dos prompt</i></div>' +
      '<div class="s-preset ember">&nbsp; ~ EMBER<i>aod doom fire · lock bios flash</i></div>' +
      '<div class="s-preset space">&nbsp; · deep space<i>aod starfield · lock wake up</i></div>' +
      '<div class="s-sec"># home</div>' + row("clock", "[x]") + row("android status bar", "[ ]") +
      "</div>"
    );
  }

  let pinTyped = 0;
  function lock() {
    const keys = ["7", "2", "9", "4", "0", "6", "1", "8", "3", "5"];
    const key = (k, label) => '<button type="button" class="s-key" data-key="' + k + '">[ ' + (label || k) + " ]</button>";
    return (
      status() +
      '<div class="s-lock">' +
      '<pre class="s-boot">Arch Linux 6.1.99-android14 (tty1)\n\npacman login: user\nPassword: ' + "*".repeat(pinTyped) + '<span class="s-cur">_</span></pre>' +
      '<div class="s-pin">pin ' + "[*]".repeat(pinTyped) + "</div>" +
      '<div class="s-keys">' + keys.slice(0, 9).map((k) => key(k)).join("") + key("del", "del") + key(keys[9]) + key("ret", "ret") + "</div>" +
      "</div>"
    );
  }

  // ---- app frames: always-on looks and boot presets ----
  let frames = null;
  const framesReady = fetch("assets/frames.json").then((r) => r.json()).then((j) => (frames = j)).catch(() => null);

  function frameHtml(f, clock) {
    const d = now();
    const lines = f.l.map(([text, role, center, mark, cursor]) => {
      // The sample's fixed time becomes the visitor's own.
      // The sample phone's fixed time and date become the visitor's own.
      let t = text.replace("21:42:07", hhmm(d) + ":" + pad(d.getSeconds())).replace("21:42", clock ? hhmm(d) : "21:42")
        .replace("Fri 20 Nov", dateLine(d)).replace("FRI 20 NOV", dateLine(d).toUpperCase());
      const cls = { B: "big", D: "dim", A: "art", V: "inv", T: "" }[role] || "";
      return '<div class="fl ' + cls + (center ? " c" : "") + '">' +
        (mark ? '<span class="mk">' + esc(mark) + "</span>" : "") + esc(t) + (cursor ? '<span class="s-cur">_</span>' : "") + "</div>";
    });
    return '<div class="s-frame' + (f.c ? " centered" : "") + (f.t ? " tail" : "") + '"><div class="fl-wrap">' + lines.join("") + "</div></div>";
  }

  /** Plays one look or preset in an element; returns a stop function. */
  function play(el, item, opts) {
    let i = 0;
    let timer = 0;
    const draw = () => {
      el.innerHTML = (opts.statusBar ? status() : "") + frameHtml(item.frames[i], true);
      fitArt(el);
      i++;
      if (i >= item.frames.length) {
        if (opts.once) return;
        i = 0;
      }
      timer = setTimeout(draw, Math.max(item.ms, 60));
    };
    draw();
    return () => clearTimeout(timer);
  }

  /** The app sizes art rows to fill the width; this does the same in the browser. */
  function fitArt(el) {
    const wrap = el.querySelector(".fl-wrap");
    if (!wrap) return;
    const art = wrap.querySelectorAll(".fl.art");
    const room = el.clientWidth - 16;
    if (art.length) {
      const cols = Math.max(...Array.from(art, (a) => a.textContent.length));
      const size = Math.max(6, Math.min(26, room / (cols * 0.5)));
      art.forEach((a) => { a.style.fontSize = size + "px"; a.style.lineHeight = "1"; });
    }
    // Long boot lines shrink to fit rather than run off the glass.
    const widest = Math.max(...Array.from(wrap.querySelectorAll(".fl:not(.art):not(.big)"), (a) => a.textContent.length), 1);
    const text = Math.max(7, Math.min(15, room / (widest * 0.5)));
    wrap.style.fontSize = text + "px";
  }

  const CYCLE = { aod: ["FIRE", "AQUARIUM", "NEOFETCH", "PLASMA", "STARFIELD", "PONG", "CUBE"], boot: ["ARCH", "BIOS", "DOS", "GRUB", "LOGIN"] };

  /**
   * Renders [name] into a phone's screen element. Returns a cleanup for timers.
   * aod/boot cycle through several looks; home/drawer/settings/lock are static with a little life.
   */
  function render(screen, name) {
    const el = screen;
    el.className = "screen scr-" + name;
    const stops = [];
    if (name === "aod" || name === "boot") {
      el.innerHTML = '<div class="s-dim s-loading">loading…</div>';
      let which = 0;
      const next = () => {
        if (!frames) return;
        const list = CYCLE[name];
        const key = list[which % list.length];
        const item = name === "aod" ? frames.looks[key] : frames.presets[key];
        stops.forEach((s) => s());
        stops.length = 0;
        stops.push(play(el, item, { statusBar: false, once: name === "boot" }));
        el.dataset.look = key.toLowerCase();
        el.dispatchEvent(new CustomEvent("look", { bubbles: true, detail: key }));
        which++;
        const dwell = name === "aod" ? 6500 : (item.frames.length * item.ms + 2600);
        const t = setTimeout(next, dwell);
        stops.push(() => clearTimeout(t));
      };
      framesReady.then(next);
    } else if (name === "home") {
      el.innerHTML = home();
      const t = setInterval(() => { el.innerHTML = home(); }, 30000);
      stops.push(() => clearInterval(t));
    } else if (name === "drawer") {
      // Types a search by itself, the way the drawer narrows as you type.
      const words = ["", "f", "fi", "fir", "", "s", "si", "sig"];
      let w = 0;
      const step = () => {
        drawerQuery = words[w % words.length];
        el.innerHTML = drawer();
        w++;
      };
      step();
      const t = setInterval(step, 900);
      stops.push(() => clearInterval(t));
    } else if (name === "settings") {
      el.innerHTML = settings();
    } else if (name === "lock") {
      pinTyped = 0;
      el.innerHTML = lock();
      const t = setInterval(() => {
        pinTyped = (pinTyped + 1) % 5;
        el.innerHTML = lock();
      }, 700);
      stops.push(() => clearInterval(t));
      // The keypad works too: a tap types a digit.
      el.addEventListener("click", (e) => {
        const k = e.target.closest(".s-key");
        if (!k) return;
        stops.forEach((s) => s());
        stops.length = 0;
        pinTyped = k.dataset.key === "del" ? Math.max(0, pinTyped - 1) : k.dataset.key === "ret" ? 0 : Math.min(8, pinTyped + 1);
        el.innerHTML = lock();
      });
    }
    return () => stops.forEach((s) => s());
  }

  /** Builds the six faces of a phone body inside [el], returns its screen. */
  function buildPhone(el) {
    el.innerHTML =
      '<div class="face front"><div class="screen"></div></div>' +
      '<div class="face back"></div>' +
      '<div class="face side left"></div><div class="face side right"></div>' +
      '<div class="face side topside"></div><div class="face side bottomside"></div>';
    const s = el.querySelector(".screen");
    const cam = document.createElement("div");
    cam.className = "cam";
    el.querySelector(".front").appendChild(cam);
    return s;
  }

  window.DroidScreens = { render, buildPhone };
})();
