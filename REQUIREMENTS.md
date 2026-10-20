# Bare — technical requirements

Handoff document. Technical requirements only. Visual and interaction taste is deliberately **not** specified here; see the empty "Taste notes" section at the end, which the owner fills in.

## 0. Ground rules

- **No live changes to the TV.** Do not run `adb connect`, `adb install`, `pm disable-user`, `pm uninstall`, or anything else against the device unless the owner explicitly authorizes it in chat, per action. Building and editing local files is fine.
- Report outcomes faithfully. The code has **never run on a device or emulator**; everything below marked "unverified" must be treated as such.
- The mockups in `mock/*.html` are browser approximations, not captures of the app. They are not a source of truth for behavior.

## 1. Target device

| Item | Value | Status |
|---|---|---|
| Model string | `MiTV-AXSO0` (listed publicly as Mi TV 4A Pro) | from DeviceAtlas / XDA, unverified on the unit |
| Android | 9 (API 28), Android TV with Xiaomi PatchWall | unverified |
| Hardware caveat | Service centre fitted a Mi TV 4A mainboard into a Mi TV 4A Horizon chassis | firmware, panel resolution and ABI may differ from the model listing |
| Panel | 1366x768 or 1920x1080 | unknown; layout must derive from `DisplayMetrics`, never hard-code px |

Before any device work, a read-only identity check is required: `ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`, `ro.product.cpu.abi`, `ro.build.fingerprint`, `wm size`, `wm density`.

## 2. Platform and build constraints

- `minSdkVersion` 21, `targetSdkVersion` 28. Target 28 deliberately avoids Android 11 package-visibility rules (`QUERY_ALL_PACKAGES`) and keeps `CONNECTIVITY_ACTION` receivers working.
- Android framework classes only. **No androidx, no Kotlin, no third-party libraries, no Gradle.**
- Permissions: `ACCESS_NETWORK_STATE` only. No `INTERNET`, no location, no storage.
- No services, no background work, no network access, no analytics, no ads.
- Java source level 8 (`javac --release 8`), dexed with `d8 --min-api 21`.
- Build is `./build.sh` using only SDK command-line tools found at `$ANDROID_HOME` or `~/Android/Sdk` (build-tools 36.1.0, platform android-36, JDK 27). `zip` is not installed on this machine, so the script uses `aapt add`.
- Output: `bare.apk`, zipaligned, signed with a local debug key (`debug.keystore`, generated on first build, not to be committed). Currently ~33 KB.
- `apksigner verify bare.apk` must pass.

## 3. Functional requirements

### 3.1 Launcher role
- **R1** Activity declares `MAIN` + `HOME` + `DEFAULT` + `LEANBACK_LAUNCHER`, `launchMode=singleTask`, `excludeFromRecents`, landscape.
- **R2** Back key is a no-op on the home screen.
- **R3** Pressing Home while already home returns to the Home page, scrolls to top, and focuses the first tile.
- **R4** The app must not list itself.

### 3.2 App discovery
- **R5** Enumerate activities for `CATEGORY_LEANBACK_LAUNCHER` first, then `CATEGORY_LAUNCHER`, de-duplicated per package, Leanback wins.
- **R6** Per entry capture label, icon, TV banner (`ActivityInfo.loadBanner`, may be null), system flag, and a launch `Intent` targeting the discovered component with `NEW_TASK | RESET_TASK_IF_NEEDED`.
- **R7** Refresh on `PACKAGE_ADDED`, `PACKAGE_REMOVED`, `PACKAGE_CHANGED`, registered in `onStart`, unregistered in `onStop`, plus a reload on `onStart`.
- **R8** Sort alphabetically with `Collator`.
- **R9** Hidden packages (R14) are excluded.

### 3.3 Screens
- **R10** Top bar: Search control, tabs "Home" and "Apps", Inputs, network status, Settings, clock. The clock honors the system 12/24-hour setting.
- **R11** Home page: a hero reflecting the currently focused entry, a Favorites row, and a Your-apps row. Your-apps excludes entries already in Favorites.
- **R12** Apps page: a grid of all discovered apps, then custom shortcuts, then the Add tile.
- **R13** Switching tabs happens when a tab takes focus; selecting a tab also moves focus into that page's first tile.
- All layout dimensions derive from display metrics and dp. No fixed pixel sizes.

### 3.4 Entry management (long-press OK or MENU on a focused tile)
- **R14** Hide an app from home. Stored as a set of package names.
- **R15** Add to / remove from Favorites. Stored as an ordered list of entry keys; order is user-defined by insertion.
- **R16** App info (`ACTION_APPLICATION_DETAILS_SETTINGS`); Uninstall (`ACTION_DELETE`) only for non-system apps.
- **R17** Menu is also reachable on the Add tile (opens the Add menu).
- **R18** Unhide: a multi-select dialog of hidden packages. Packages that no longer exist are dropped silently.

### 3.5 Custom shortcuts ("add custom apps")
- **R19** Entry point 1: Add tile → "Custom shortcut…" form (name, target) with on-screen keyboard. Validate the target before adding; keep the dialog open on failure.
- **R20** Entry point 2 (no keyboard needed): exported action `dev.bare.launcher.ADD` with string extras `name` (optional, defaults to target) and `target`. Handled in both `onCreate` and `onNewIntent`. Invalid targets show a toast and add nothing.
- **R21** Target grammar, evaluated in this order:
  1. begins with `intent:` → `Intent.parseUri(..., URI_INTENT_SCHEME)`
  2. contains `://` → `ACTION_VIEW` on that URI
  3. contains `/` → `ComponentName.unflattenFromString`, `ACTION_MAIN`
  4. an installed package name → Leanback launch intent, else launcher intent
  5. contains `.` and no spaces → `ACTION_VIEW` on `https://<target>`
  6. otherwise invalid
- **R22** Custom entries persist as JSON `{id,label,target}`; the target is re-resolved on every reload so a later install or uninstall is reflected. An unresolvable custom entry stays visible, shows a toast on launch, and can be removed.
- **R23** Custom entries always appear in Favorites, after explicitly pinned entries, sorted by label.

### 3.6 System controls
- **R24** Settings opens `ACTION_SETTINGS`; network opens `ACTION_WIFI_SETTINGS`. The network indicator reflects connected / disconnected via `ConnectivityManager`.
- **R25** Inputs lists passthrough inputs from `TvInputManager.getTvInputList()` and opens the chosen one with `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput`. If no passthrough inputs are found, show a toast and do nothing else.
- **R26** Search tries `ACTION_ASSIST`, then `ACTION_VOICE_COMMAND`; if neither resolves, show a toast.
- **R27** Every `startActivity` call is wrapped for `ActivityNotFoundException` and `SecurityException` with a user-visible toast; none may crash the launcher.

### 3.7 Focus and navigation
- **R28** Entire UI is operable with D-pad, OK, and MENU only. No touch dependence.
- **R29** After any data reload, focus returns to the previously focused entry key if it still exists, otherwise the first tile of the visible page.
- **R30** Opening and closing dialogs must not leave focus stranded outside the launcher's views.

## 4. Persistence

`SharedPreferences` file `bare`:

| Key | Type | Content |
|---|---|---|
| `hidden` | string set | package names |
| `pinned` | string (JSON array) | entry keys; app key = package, custom key = `c:<id>` |
| `custom` | string (JSON array) | `{id, label, target}` objects |

No other storage. Clearing app data resets everything.

## 5. File map

| File | Role |
|---|---|
| `AndroidManifest.xml` | permissions, HOME/LEANBACK intent filters, `ADD` action |
| `src/dev/bare/launcher/MainActivity.java` | lifecycle, UI assembly, discovery, prefs, menus, custom targets |
| `.../Tile.java` | focusable card, focus ring and scale, card artwork (banner / icon plate / add) |
| `.../Entry.java` | data holder |
| `.../GlowView.java` | background drawing and color transition |
| `.../IconView.java` | five hand-drawn icons (search, mic, gear, input, wifi) |
| `build.sh` | aapt2 → javac → d8 → aapt add → zipalign → apksigner |
| `mock/` | throwaway HTML previews |

## 6. Known gaps and unverified assumptions

1. Never executed on a device or emulator. No emulator system image is installed locally. Focus traversal, scroll behavior, animation cost, and dialog focus are all untested.
2. Performance on the target's low-end SoC is unknown. Per-focus work includes a property animation per tile and background redraws; measure it.
3. `TvInputManager.getTvInputList()` permission requirements on API 28 are assumed to be none; unconfirmed.
4. Inputs depends on a TV app that handles passthrough `ACTION_VIEW`; Search depends on an assistant app. Both degrade to toasts if those apps are disabled. This interacts with the debloat plan (section 8).
5. First-time Home routing: Android may show a "choose default" prompt; the first-run flow is untested. If a stock launcher is disabled before Bare is confirmed working, the TV can be left without a usable Home. Recovery is `adb shell pm enable <pkg>`.
6. `dominant()` samples a 24x24 render of the artwork on the main thread for every entry on every reload. Acceptable for tens of apps; move off-thread or cache if profiling disagrees.
7. Icons and banners are decoded on the main thread on every reload.
8. Text input on a TV relies on the installed IME; behavior on this firmware is unknown.
9. `onKeyDown(KEYCODE_MENU)` assumes the remote can send MENU; the Mi remote's mapping is unverified. Long-press OK is the primary path.
10. No automated tests exist.

## 7. Acceptance checks

Run on an emulator (Android TV, API 28) first; the device only after the owner authorizes it.

- A1 `./build.sh` succeeds from a clean checkout; `apksigner verify` passes; manifest dump shows the HOME and LEANBACK filters and the single permission.
- A2 With zero launchable third-party apps, the home screen renders and does not crash.
- A3 With 60+ apps, D-pad traversal in both pages is smooth and focus never gets lost.
- A4 Installing and uninstalling an app updates the lists without restart.
- A5 Pin, unpin, hide, unhide, and remove-custom survive a process kill.
- A6 `adb shell am start -a dev.bare.launcher.ADD --es name X --es target <each grammar case in R21>` creates a working entry or a toast, never a crash.
- A7 Launching an uninstalled custom target toasts and does not crash.
- A8 Home pressed from inside another app returns to Bare; Home pressed in Bare resets to first tile.
- A9 Back never exits.
- A10 Rotate/density/resolution sweep: 1366x768 and 1920x1080 at the TV's real densities; no clipping of the top bar, hero, or focus ring.
- A11 Disabling the TV input app or the assistant leaves Inputs and Search failing gracefully.

## 8. Debloat requirements

Written as `debloat/debloat.sh`, with usage and recovery steps in `debloat/README.md`. It has been run only against the simulated TV in `debloat/test/` (`bash debloat/test/run.sh`), never against the real unit. Running it against the TV still needs the owner's say-so.

A dry-run-first shell script. It must not run anything against a device until the owner says so.

- **D1** Default mode prints, changes nothing. `--apply` performs; `--restore` re-enables everything the script can touch.
- **D2** First prints the identity properties from section 1, and aborts if `adb devices` shows zero or more than one device.
- **D3** Acts only on packages present in `pm list packages`, matched against an explicit allow-list. No wildcards.
- **D4** Uses `pm disable-user --user 0` for everything, which is reversible. `uninstall --user 0` only as an opt-in fallback.
- **D5** Stock launchers are in a separate tier behind their own flag and may only be touched after Bare has been installed and confirmed as the Home activity.
- **D6** Never touches `com.android.*`, Play Store, Play Services, TV input / HDMI services, or chipset packages (`com.droidlogic.*`, `com.mediatek.*`).

Candidate packages, sourced from community Mi TV / Mi Box debloat lists and not verified on this unit:

| Tier | Packages |
|---|---|
| 1 Xiaomi tracking and promo | `com.miui.tv.analytics`, `com.xm.webcontent`, `com.mitv.tvhome.michannel`, `com.mitv.dream`, `com.xiaomi.android.tvsetup.partnercustomizer` |
| 2 Xiaomi extras | `com.mitv.videoplayer`, `com.xiaomi.mitv.tvmanager` |
| 3 Google fluff | `com.google.android.tvrecommendations`, `com.google.android.leanbacklauncher.recommendations`, `com.google.android.videos`, `com.google.android.music`, `com.google.android.play.games`, `com.google.android.youtube.tvmusic`, `com.google.android.feedback`, `com.google.android.partnersetup` |
| 4 Preloaded streaming (owner's choice) | `com.netflix.ninja`, `com.amazon.amazonvideo.livingroom`, `com.hulu.livingroomplus`, `com.cbs.ott`, `com.sling`, `com.pandora.android.atv`, `com.showtime.standalone`, `com.nousguide.android.rbtv` |
| 5 Stock launchers (last, gated) | `com.mitv.tvhome.atv`, `com.mitv.tvhome.mitvplus`, `com.google.android.tvlauncher` |
| Owner decision | `com.xiaomi.mitv.updateservice` disables OTA updates. Likely wise on a mismatched board, but it is the owner's call. |

Recovery: `adb shell cmd package install-existing --user 0 <pkg>` for uninstalled; `adb shell pm enable --user 0 <pkg>` for disabled.

## 9. Open questions for the owner

1. Which panel resolution does the unit have, and is a firmware read-out acceptable as the first authorized device action?
2. Should Favorites order be user-reorderable (move left/right), or is insertion order enough?
3. Is search expected to be a voice/assistant launcher only, or should it search installed apps?
4. Should the Apps page be the only place hidden-app management lives, or also Home?

## Taste notes

<!-- Owner fills this in. Intentionally empty. -->

