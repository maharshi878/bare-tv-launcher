# Debloat for the Mi TV (MiTV-AXSO0)

`debloat.sh` turns off Xiaomi and Google extras on the TV, and can turn every one of them back on.

- **Nothing is deleted.** Every change is `pm disable-user --user 0`, the same as pressing Disable in the TV's own app settings.
- **Changes are logged.** `--restore` re-enables packages from a fixed list in the script, and a factory reset puts everything back as well.
- **The default is a dry run.** Without `--apply` or `--restore`, the script only reads from the TV and prints a plan.
- **Tested on one real TV.** Run on a MiTV-AXSO0 (Android 9, swapped mainboard) in October 2026: 19 packages disabled, the stock-launcher tier never run, and `rollback.sh` checked against it. Plus a simulated TV (`test/run.sh`, 47 scenarios). Other Mi models may differ, so do the dry run first and read its output.

## Before you start

1. **On your computer:** install Android platform-tools so `adb` works. The script needs `bash`, which macOS, Linux, WSL and Git Bash all have. Run it with `bash debloat.sh`, not `sh debloat.sh`.
2. **On the TV:** open Settings → Device Preferences → About and press OK on **Build** seven times. Then go back to Developer options and turn on **USB debugging**. If you see a **Network debugging** or **ADB over network** switch, turn that on too.
3. **Note the TV's IP address** (Settings → Network). The TV's USB ports can't be used for adb, so the network is the only way in. **Write the IP down**, because you'll need it to recover.
4. Connect from the computer:
   ```sh
   adb connect 192.168.1.20:5555
   ```
   A prompt appears on the TV: tick **Always allow from this computer** and press OK.

## The safe order

Do one step at a time and check the TV after each one.

```sh
cd debloat

# 1. Look first. Read-only: prints the TV's identity and the plan.
bash debloat.sh

# 2. Ads, tracking and extras (tiers 1, 2 and 3 are the default).
bash debloat.sh --apply

# 3. Reboot and check that the TV works: picture, inputs, remote, Wi-Fi, your apps.
adb reboot
adb connect 192.168.1.20:5555
bash debloat.sh --status

# 4. Optional tiers, only the ones you want. Use --keep for any app you still use.
bash debloat.sh --apply --tiers 4 --keep com.netflix.ninja
bash debloat.sh --apply --tiers 5

# 5. Last, and only once Bare is installed: hand Home to Bare and turn off the stock home screens.
bash debloat.sh --dry-run --tiers 6 --set-home
bash debloat.sh --apply --tiers 6 --set-home
```

Run any command with `--dry-run` first (or leave out `--apply`) to see exactly what it will do.

## What it touches

`bash debloat.sh --list` prints the full catalogue with a note on each package.

| Tier | What | Default | Side effects |
|---|---|---|---|
| 1 | Ads and tracking: Xiaomi analytics, PatchWall promos, Mi TV+, Glance, Sensara, recommendation feeds, bug reporters | on | None expected. |
| 2 | Xiaomi extras: screensaver, TV Manager, handbook, Mi Music, Gallery, video player | on | The Mi video player may be what plays USB videos. Add `--keep com.mitv.videoplayer` if you use USB. |
| 3 | Google extras: Play Movies, Play Music, Play Games, YouTube Music, partner setup | on | None expected. |
| 4 | Preloaded streaming apps | off | The remote's Netflix or Prime button stops working if you disable that app. |
| 5 | Voice and casting | off | The mic button and casting from phones stop working. |
| 6 | Stock home screens (PatchWall, Android TV Home) | off, gated | Only runs once Bare is installed, is Home, and is seen running (see below). |
| U | Firmware updates (`--updates`) | off | No OTA updates until you re-enable it. On a TV with a swapped board, an OTA built for the original board could be risky. That's your call. |

### Never touched, whatever you pass

These are blocked by name pattern, even with `--only`:

- core Android (`com.android.*`)
- Play Store and Play Services
- chipset packages (`com.droidlogic.*`, `com.mediatek.*`, `com.amlogic.*` and the like)
- TV inputs and HDMI
- TV settings
- the keyboard currently in use
- anything with "remote" or "bluetooth" in its name, so the remote keeps working
- the setup wizard
- WebView
- Bare itself
- whichever app is Home right now, except in tier 6

The script also only acts on packages that are both installed and named exactly in its catalogue. There are no wildcards.

### The tier 6 gate

Before it disables a stock home screen, the script checks four things:

1. Bare is installed and enabled.
2. Home resolves to Bare. `--set-home` makes this so with `cmd package set-home-activity`.
3. Starting Home actually opens Bare.
4. Four seconds later, Bare is still running and is the screen in front.

If any check fails, tier 6 is refused (exit code 4), the stock home screens stay as they are, and the reason is printed.

## Options

| Option | Effect |
|---|---|
| `--dry-run` | The default. Reads the TV and prints the plan. |
| `--apply` | Disables the planned packages after you type `yes`. |
| `--restore` | Re-enables everything in the catalogue that is disabled. Home screens come back first. |
| `--status` | Shows the state of every catalogue package. |
| `--list` | Prints the catalogue. No TV needed. |
| `--tiers 1,2,3` | Which tiers to act on. |
| `--updates` | Also disables firmware updates. |
| `--only a,b` / `--keep a,b` | Act on just these packages, or never on these. |
| `--set-home` | With `--apply`: makes Bare Home before tier 6. |
| `--home-to PKG` | With `--restore`: gives Home back to PKG, e.g. `com.google.android.tvlauncher` or `com.mitv.tvhome.atv`. |
| `--uninstall-fallback` | If the TV refuses `disable-user` for a package, uses `pm uninstall -k --user 0` instead. That is still reversible: the APK stays on the TV and `--restore` reinstalls it. Off by default. |
| `--restore-all` | With `--restore`: also re-enables packages that were already off before this script first ran. |
| `--serial S` | Picks a device when more than one is connected. |
| `--force-model` / `--force-sdk` | Continues when the model string or Android version doesn't match. Because of the board swap the model string may well differ. Check the printed identity first. |
| `--offline FILE` | Dry run against a saved `adb shell pm list packages > packages.txt`, with no TV connected. |
| `--yes` | Skips the "type yes" prompt. Needed when the script isn't run from a terminal. |

Exit codes:

| Code | Meaning |
|---|---|
| 0 | OK |
| 1 | Usage error, or you cancelled |
| 2 | adb or connection problem |
| 3 | Not the expected TV |
| 4 | Tier 6 refused |
| 5 | Some packages failed |
| 130 | Interrupted |

## Logs and snapshots

Everything goes in `debloat/state/<TV serial>/`. This folder is not committed. It's keyed by the TV's own serial number, so a new IP address doesn't lose it.

| File | What it holds |
|---|---|
| `baseline/` | The TV as it was the very first time the script changed anything. It is never overwritten. `--restore` uses it to leave alone anything that was already off from the factory. |
| `before-<time>/` | A snapshot taken before each `--apply` or `--restore`. |
| `actions.log` | Every package changed, with time and device. |
| `run-<time>.log` | The full output of each run. |

## If something goes wrong

Start at the top and stop at the first fix that works.

### A feature stopped working (an app, the mic, casting, the Netflix button, USB video)

```sh
bash debloat.sh --status                          # find the package that's off
bash debloat.sh --restore --only <package>        # turn just that one back on
```

You can also do it on the TV: Settings → Apps → See all apps → Show system apps → the app → **Enable**.

### Something odd, and you don't know which package caused it

```sh
bash debloat.sh --restore            # everything back on; Home screens first
adb reboot
```

Then re-apply one tier at a time to find the culprit, and add it to `--keep`.

### The TV boots to a black screen, or Home does nothing

This means the stock home screens are off and Bare isn't working. The TV is still running, and adb over the network normally still works.

```sh
adb connect 192.168.1.20:5555
bash debloat.sh --restore --only com.mitv.tvhome.atv,com.google.android.tvlauncher --home-to com.mitv.tvhome.atv
adb reboot
```

If `--home-to` reports that it can't find the Home activity, the stock launcher is still re-enabled. Press Home on the remote and pick it when Android asks.

### adb can't reach the TV

- **"unauthorized":** accept the prompt on the TV. If no prompt appears, turn debugging off and on again in Developer options.
- **"offline" or the connection is refused:** run `adb disconnect`, then `adb connect <ip>:5555` again. Check the TV's IP hasn't changed (look in your router). Restart the adb server with `adb kill-server`.
- **Developer options are unreachable because there's no Home screen:**
  - Press and hold Home, or press the remote's Settings or menu key; some Android TV builds open Settings that way.
  - Try plugging in a USB keyboard and pressing the Settings shortcut.
- **More than one device listed:** add `--serial <ip>:5555`.

### The script stopped half-way (lost connection, Ctrl-C, power cut)

Each step is a single `pm` command, so nothing is left half-changed. Reconnect and run `--status`. Then run the same `--apply` again: it skips what's already done. Or run `--restore` to undo.

### "Cannot disable" or SecurityException for a package

Some firmwares protect certain system apps. Either leave it (the run reports it and exits with code 5) or rerun with `--uninstall-fallback` for that package. That removes the package for your user only and is undone by `--restore`.

### After a reboot or firmware update, some packages are back on

Some Xiaomi firmware re-enables packages at boot; `com.google.android.leanbacklauncher` is known to do this on this model. Run `bash debloat.sh --status`, then `--apply` again. If an OTA added new bloat, it won't be in the catalogue. Add exact names to the catalogue in `debloat.sh` yourself.

### You want to uninstall Bare

**First** run `bash debloat.sh --restore --only com.mitv.tvhome.atv,com.google.android.tvlauncher --home-to com.mitv.tvhome.atv`, **then** uninstall Bare. Uninstalling Bare while the stock home screens are off leaves the TV with no Home.

### Last resort: factory reset

A factory reset clears every user-level change this script makes, because nothing was ever removed from the system partition. If the TV still starts, use Settings → Device Preferences → Reset.

If it doesn't start, use Xiaomi's recovery menu. On Mi TVs you usually reach it by unplugging the TV, then holding the power button on the TV itself while plugging it back in. Some models use other key combinations. **This is unverified for this unit**, and the swapped board may change it. Look up the procedure for your exact model before you need it. A reset wipes your apps and logins.

## Testing the script without a TV

```sh
bash test/run.sh
```

This runs the script against `test/fake-adb`, a simulated TV, through 47 scenarios:

- no device, two devices, an unauthorized device, an offline device
- wrong model, wrong Android version
- dry run, apply, re-apply, refused disables, the uninstall fallback
- the tier 6 gate refusing and passing, and Bare crashing
- a dropped connection
- status, restore, `--restore-all`, offline mode

It never calls the real `adb`.
