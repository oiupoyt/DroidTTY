# droidtty.oiupoyt.space

The DroidTTY website: static files, plus a small worker (`worker/index.js`) that serves the
latest release version. No build step.

## Deploy

Deploy with `npx wrangler deploy` from the repo root (config in `wrangler.json`). To host
elsewhere, serve this folder as the web root for `droidtty.oiupoyt.space` (any static server:
caddy `file_server`, nginx `root`, etc.). Everything is relative, so it also works
from a sub-path. Suggested headers:

- `assets/` : `Cache-Control: public, max-age=604800`
- `*.html`, `*.css`, `*.js`, `version.json` : `Cache-Control: no-cache`
- `.ttf` : `Content-Type: font/ttf`

## Keeping it current

- `version.json`: the download section's version is read live from the latest GitHub
  release by the worker (`worker/index.js`, cached five minutes), so a new release shows
  without redeploying. This copy is only the fallback for when GitHub can't be reached.
- `assets/gallery/`: copied from `docs/gallery/` (regenerate with `tools/gallery.py`).
- `assets/frames.json`: the always-on and boot frames the phones play, a trimmed copy
  of the `gallery-frames` CI artifact.

The phone screens for home, drawer, settings and the pin lock are HTML drawn after the
app's layout (`screens.js`); the always-on and boot screens play the app's real frames.
