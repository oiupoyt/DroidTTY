# Publishing on Google Play

What the Play build is, what to upload, and the answers Play Console asks for. The
declarations below describe what the code actually does; keep them in step with it.

## The Play build

`play` is one of three builds (`app/build.gradle.kts`, flavor dimension `store`):

| build | update behaviour | install permission |
|---|---|---|
| `github` | checks the rolling GitHub release, downloads and installs on a tap | yes |
| `fdroid` | checks tagged releases, tap opens F-Droid | no |
| `play` | asks Google Play (In-App Updates API), tap opens the Play listing | no |

The Play build never downloads or installs anything itself, so it doesn't declare
`REQUEST_INSTALL_PACKAGES`, which Play only allows for app stores, browsers, file managers
and similar.

## What to upload

Every `vX.Y.Z` tag publishes `DroidTTY-play.aab` on the GitHub release (built by
`.github/workflows/release-tag.yml` with `bundlePlayRelease`, signed with the release key).
Upload that file to Play Console. To build it locally: `./gradlew :app:bundlePlayRelease`
(output in `app/build/outputs/bundle/playRelease/`).

### Play App Signing: keep one signature

Play re-signs what it distributes. When enrolling, choose to **use your own key** ("Use a key
from Java keystore" / export with Google's PEPK tool) and give it the existing release key
(`release.p12`, alias `sysreadout`). Then Play's APKs carry the same signature as the GitHub
and F-Droid ones, and people can move between channels without uninstalling. If Play
generates its own key instead, a Play install and a GitHub install can't update each other.

## Before the first release

- **New personal account:** a closed test with at least 12 testers for 14 days.
- **Privacy policy URL:** https://droidtty.oiupoyt.space/privacy
- **App category:** Personalization. **Contains ads:** no. **Target audience:** 18+ (not directed at children).

## Permission declarations (Policy › App content)

### Accessibility API (`BIND_ACCESSIBILITY_SERVICE`, not an accessibility tool)

> DroidTTY is a home screen (launcher) with its own always-on display, lock screens and
> notification shade. Its accessibility service ("lock service") is used to: draw the
> always-on display, lock screens and shade over other apps and over the lock screen; turn
> the screen off when the user double-taps the home screen (GLOBAL_ACTION_LOCK_SCREEN);
> detect from window events when Android's notification shade or another app opens, using
> only the window's type and size, to close it or restore the lock; and, while the app's own
> PIN lock is shown, block the home, back, recents, search, assistant and camera keys. It
> does not read screen content or typed text, and no data from it is stored or transmitted.
> No public API offers these together: overlays over the keyguard and global actions require
> an accessibility service. Before the user is sent to the accessibility setting, the app
> shows a prominent disclosure explaining this and asks for consent ("agree, turn it on" /
> "not now").

**Video** (Play may ask): settings › get started › lock service → the disclosure → agree →
Android's accessibility setting → switch on DroidTTY → turn the screen off: the always-on
display shows → turn it on: the lock screen's boot preset plays → double-tap home: screen locks.

### All installed apps (`QUERY_ALL_PACKAGES`)

> DroidTTY is a launcher (home screen app): listing and launching the installed apps is its
> core function. Category: device launcher.

### VPN service (`BIND_VPN_SERVICE`)

> Optional, off by default (settings › log › DNS monitor). A local VPN on the device that
> shows which hostnames each app looks up and forwards every lookup unchanged to the
> network's own DNS server. No traffic is sent to the developer or any third party, and
> nothing is altered or redirected.

If the review rejects this, the DNS monitor can be left out of the Play build only (it would
move to the `github` and `fdroid` source sets like the self-updater).

### Foreground service, special use (`FOREGROUND_SERVICE_SPECIAL_USE`)

> Runs only when the accessibility service is off and the user has switched on the always-on
> display or the boot screen over the lock screen: it listens for screen on/off to show them.
> No other foreground service type covers reacting to screen off to show an always-on display.

### Location (foreground only)

> Shows the GPS fix and satellite count in the home-screen readout, only while such a row is
> on screen. Not stored, not transmitted. No background location.

## Data safety form

- **Does the app collect or share user data?** No. Everything is processed on the device.
- **Is data encrypted in transit?** Only network use is the update check through Google Play.
- **Can users request deletion?** Not applicable: nothing is collected. Uninstalling deletes all
  local data.
