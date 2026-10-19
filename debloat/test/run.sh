#!/usr/bin/env bash
# Runs debloat.sh against fake-adb through each scenario and checks the outcome.
# Never talks to a real device: PATH puts the fake first and the real adb is not used.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=$HERE/../debloat.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
cp "$HERE/fake-adb" "$WORK/bin/adb"
chmod +x "$WORK/bin/adb"
export PATH="$WORK/bin:$PATH" FAKE="$WORK/tv"
pass=0 fail=0

fresh_tv() {
    rm -rf "$FAKE" "$WORK/state"
    mkdir -p "$FAKE"
    echo "192.168.1.20:5555 device" >"$FAKE/devices"
    printf '%s\n' ro.product.model=MiTV-AXSO0 ro.build.version.sdk=28 ro.build.version.release=9 \
        ro.serialno=ABC123 ro.product.device=dangal >"$FAKE/props"
    printf '%s\n' android com.android.tv.settings com.google.android.gms com.droidlogic.tvinput \
        com.google.android.leanback.ime com.miui.tv.analytics com.xm.webcontent com.glance.tv \
        com.mitv.tvhome.atv com.google.android.tvlauncher com.netflix.ninja com.xiaomi.mitv.updateservice \
        com.google.android.videos com.mitv.dream >"$FAKE/pkgs"
    echo com.mitv.dream >"$FAKE/disabled"   # disabled by the factory, before the script ever ran
    echo "com.mitv.tvhome.atv/.HomeActivity" >"$FAKE/home"
}

# check NAME EXPECTED_EXIT GREP_PATTERN -- debloat args...
check() {
    local name=$1 want=$2 pat=$3
    shift 4
    out=$(bash "$SCRIPT" --state-dir "$WORK/state" "$@" </dev/null 2>&1)
    got=$?
    if [ "$got" = "$want" ] && printf '%s' "$out" | grep -Eq -- "$pat"; then
        pass=$((pass + 1)); echo "ok    $name"
    else
        fail=$((fail + 1)); echo "FAIL  $name (exit $got, wanted $want, pattern '$pat')"; printf '%s\n' "$out" | tail -n 15 | sed 's/^/      /'
    fi
}
disabled() { grep -qxF "$1" "$FAKE/disabled"; }
expect() { if eval "$2"; then pass=$((pass + 1)); echo "ok    $1"; else fail=$((fail + 1)); echo "FAIL  $1"; fi; }

fresh_tv
check "list needs no TV" 0 "Tier 6" -- --list
out=$(sh "$SCRIPT" --list 2>&1); expect "running under sh is refused" '[ $? -ne 0 ] && printf "%s" "$out" | grep -q "with bash"'
check "bad tiers" 1 "digits 1 to 6" -- --tiers 1,9
check "unknown option" 1 "Unknown option" -- --frobnicate
check "two modes" 1 "Pick one mode" -- --apply --restore
check "--only outside catalogue" 1 "not in the catalogue" -- --only com.example.thing
check "--only protected name" 1 "not in the catalogue|protected" -- --only com.android.tv.settings
check "--offline needs dry run" 1 "only works with a dry run" -- --apply --offline /dev/null

echo "" >"$FAKE/devices"
check "no device" 2 "No TV found" -- --dry-run
printf '%s\n' "a:5555 device" "b:5555 device" >"$FAKE/devices"
check "two devices" 2 "More than one device" -- --dry-run
check "two devices, --serial picks one" 0 "Dry run" -- --dry-run --serial b:5555
echo "a:5555 unauthorized" >"$FAKE/devices"
check "unauthorized" 2 "Allow debugging" -- --dry-run
echo "a:5555 offline" >"$FAKE/devices"
check "offline" 2 "offline" -- --dry-run

fresh_tv
sed -i 's/MiTV-AXSO0/MiTV-MOOQ1/' "$FAKE/props"
check "wrong model stops" 3 "mainboard swapped" -- --dry-run
check "wrong model with --force-model" 0 "Dry run" -- --dry-run --force-model
fresh_tv
sed -i 's/sdk=28/sdk=30/' "$FAKE/props"
check "wrong sdk stops" 3 "force-sdk" -- --dry-run

fresh_tv
check "dry run plans and changes nothing" 0 "Dry run: nothing" -- --dry-run
expect "dry run left the TV alone" '! disabled com.miui.tv.analytics'
check "dry run skips absent packages" 0 "skip: not on this TV" -- --dry-run
check "dry run reports factory-disabled" 0 "com.mitv.dream +ok: already disabled" -- --dry-run
check "dry run tier 6 refusal is reported" 0 "Tier 6 would be REFUSED" -- --dry-run --tiers 6
check "apply without a terminal needs --yes" 1 "--yes" -- --apply
check "apply tiers 1-3" 0 "Done. 0 failure" -- --apply --yes
expect "analytics disabled" 'disabled com.miui.tv.analytics'
expect "Netflix untouched (tier 4 not chosen)" '! disabled com.netflix.ninja'
expect "stock launcher untouched" '! disabled com.mitv.tvhome.atv'
expect "core packages untouched" '! disabled com.android.tv.settings && ! disabled com.google.android.gms'
expect "baseline written once" '[ -s "$WORK/state/ABC123/baseline/disabled" ]'
check "second apply is a no-op" 0 "Nothing to do" -- --apply --yes

check "tier 6 refused while PatchWall is Home" 4 "Tier 6 REFUSED" -- --apply --yes --tiers 6
expect "PatchWall still enabled after refusal" '! disabled com.mitv.tvhome.atv'
echo dev.bare.launcher >>"$FAKE/pkgs"
touch "$FAKE/bare_crash"
check "tier 6 refused when Bare crashes" 4 "may be crashing" -- --apply --yes --tiers 6 --set-home
rm "$FAKE/bare_crash"
check "tier 6 with --set-home" 0 "Done. 0 failure" -- --apply --yes --tiers 6 --set-home
expect "PatchWall disabled once Bare is Home" 'disabled com.mitv.tvhome.atv'
expect "Bare itself never disabled" '! disabled dev.bare.launcher'

echo com.netflix.ninja >"$FAKE/refuse"
check "refused disable is reported" 5 "FAILED +com.netflix.ninja" -- --apply --yes --only com.netflix.ninja
check "uninstall fallback" 0 "removed +com.netflix.ninja" -- --apply --yes --only com.netflix.ninja --uninstall-fallback
check "keep wins over tiers" 0 "Nothing to do|0 package" -- --dry-run --tiers 4 --keep com.netflix.ninja

touch "$FAKE/drop"
check "lost connection stops cleanly" 2 "Lost the TV" -- --apply --yes --tiers 4 --keep com.netflix.ninja --updates
rm "$FAKE/drop"
check "status" 0 "disabled at baseline" -- --status
check "restore" 0 "Done. 0 failure" -- --restore --yes --home-to com.google.android.tvlauncher
expect "analytics back" '! disabled com.miui.tv.analytics'
expect "Netflix reinstalled for user 0" 'grep -qxF com.netflix.ninja "$FAKE/pkgs"'
expect "factory-disabled package left as found" 'disabled com.mitv.dream'
expect "Home handed back" 'grep -q tvlauncher "$FAKE/home"'
check "restore-all includes factory-disabled" 0 "enabled +com.mitv.dream" -- --restore --yes --restore-all

printf 'package:android\npackage:com.glance.tv\r\n' >"$WORK/pkgs.txt"
check "offline dry run" 0 "com.glance.tv +disable" -- --offline "$WORK/pkgs.txt"

echo ""
echo "$pass passed, $fail failed"
[ $fail -eq 0 ]
