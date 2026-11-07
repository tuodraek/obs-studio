#!/bin/sh
# Downloads the Pro Zoom (centaur) model package listed in a manifest asset
# from Google's model server and verifies each file's SHA-1 (= its name).
# Usage: fetch_centaur.sh <centaur_pNN_manifest_config_default_variant.pb> <out dir>
set -e
mkdir -p "$2"
grep -aoE 'https://edgedl\.me\.gvt1\.com/edgedl/mdi-serving/pixelcamera-centaur/[0-9]+/[0-9a-f]{40}' "$1" | sort -u | while read u; do
  h=${u##*/}
  curl -sS --retry 4 -C - -o "$2/$h" "$u"
  echo "$h $(sha1sum "$2/$h" | cut -c1-40)"
done
