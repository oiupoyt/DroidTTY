# droidtty.oiupoyt.space

The DroidTTY website: plain static files, no build step, no server code.

## Deploy

Serve this folder as the web root for `droidtty.oiupoyt.space` (any static server:
caddy `file_server`, nginx `root`, etc.). Everything is relative, so it also works
from a sub-path. Suggested headers:

- `assets/` : `Cache-Control: public, max-age=604800`
- `*.html`, `*.css`, `*.js`, `version.json` : `Cache-Control: no-cache`
- `.ttf` : `Content-Type: font/ttf`

## Keeping it current

- `version.json`: the latest release for the download section. Browsers can't read
  GitHub's copy (no CORS header on the download redirect), so the page falls back to
  this file. Refresh it after a release:
  `curl -sL https://github.com/oiupoyt/DroidTTY/releases/download/latest/version.json > version.json`
- `assets/gallery/`: copied from `docs/gallery/` (regenerate with `tools/gallery.py`).
- `assets/frames.json`: the always-on and boot frames the phones play, a trimmed copy
  of the `gallery-frames` CI artifact.

The phone screens for home, drawer, settings and the pin lock are HTML drawn after the
app's layout (`screens.js`); the always-on and boot screens play the app's real frames.
