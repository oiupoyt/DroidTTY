// droidtty.oiupoyt.space: the static site in site/, plus /version.json read live from the
// latest GitHub release, so the download section always names the version that's out.
// Browsers can't fetch GitHub's copy themselves (no CORS header on the redirect).

const RELEASE = "https://github.com/oiupoyt/DroidTTY/releases/download/latest/version.json";
const FRESH_S = 300; // a new release shows within five minutes

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/version.json") return version(request, env, ctx);
    return env.ASSETS.fetch(request);
  },
};

async function version(request, env, ctx) {
  const cache = caches.default;
  const key = new Request(new URL("/version.json", request.url).toString());
  const hit = await cache.match(key);
  if (hit) return hit;

  let body = null;
  try {
    const r = await fetch(RELEASE, { redirect: "follow", cf: { cacheTtl: FRESH_S } });
    if (r.ok) {
      const v = await r.json();
      // Only a well-formed release file is passed on.
      if (typeof v.versionName === "string" && Number.isInteger(v.versionCode)) body = JSON.stringify(v);
    }
  } catch (e) {
    // GitHub unreachable: fall back to the copy deployed with the site below.
  }

  if (body === null) {
    const fallback = await env.ASSETS.fetch(new Request(new URL("/version.json", request.url)));
    const res = new Response(fallback.body, fallback);
    res.headers.set("Cache-Control", "no-cache");
    res.headers.set("X-Version-Source", "bundled");
    return res;
  }

  const res = new Response(body, {
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": `public, max-age=${FRESH_S}`,
      "X-Version-Source": "github",
    },
  });
  ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}
