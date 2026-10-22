#!/usr/bin/env bash
# Downloads the wallpapers listed in sources.txt from Unsplash (free licence: unsplash.com/license),
# cropped to 1920x1080, and writes index.txt for build.sh. The photos themselves stay out of git.
#
# sources.txt, one per line:   unsplash-photo-id | place shown on screen | photographer
set -euo pipefail
cd "$(dirname "$0")"
[ -f sources.txt ] || { echo "No sources.txt here." >&2; exit 1; }

trim() { local s=$1; s=${s#"${s%%[![:space:]]*}"}; printf '%s' "${s%"${s##*[![:space:]]}"}"; }

: >index.txt.tmp
n=0
while IFS='|' read -r id place who; do
    id=$(trim "$id") place=$(trim "${place:-}") who=$(trim "${who:-}")
    if [ -z "$id" ] || [ "${id:0:1}" = "#" ]; then continue; fi
    n=$((n + 1))
    f="$id.jpg"
    if [ ! -s "$f" ]; then
        curl -fsSL --retry 2 -o "$f.part" \
            "https://images.unsplash.com/photo-$id?w=1920&h=1080&fit=crop&crop=entropy&q=85&fm=jpg"
        mv "$f.part" "$f"
    fi
    echo "$f | $place | ${who:+Photo: $who}" >>index.txt.tmp
    printf '%s  %6s KB  %s\n' "$f" "$(( $(stat -c %s "$f") / 1024 ))" "$place"
done <sources.txt
mv index.txt.tmp index.txt
echo "$n wallpapers; index.txt written."
