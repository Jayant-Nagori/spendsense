#!/bin/bash
# Builds an UNSIGNED iOS device build of SpendSense and an AltStore/SideStore source for sideloading.
#
#   scripts/build-ipa.sh [HOST[:PORT]]
#
# Output (in ~/spendsense-dist):
#   SpendSense.ipa            Payload/SpendSense.app, Release, iphoneos, CODE_SIGNING_ALLOWED=NO.
#                             SideStore/AltStore re-sign it with each person's own free Apple ID.
#   altstore-source.json      AltStore "source" pointing at http://HOST:8765/SpendSense.ipa
#   icon.png                  App icon referenced by the source.
# HOST defaults to this Mac's Wi-Fi address (ipconfig getifaddr en0); serve the folder with
#   cd ~/spendsense-dist && python3 -m http.server 8765 --bind <HOST>
# No Apple signing identity is used or needed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$HOME/spendsense-dist"
HOST="${1:-$(ipconfig getifaddr en0 2>/dev/null || echo HOST)}"
case "$HOST" in *:*) BASE="http://$HOST/";; *) BASE="http://$HOST:8765/";; esac
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

command -v xcodegen >/dev/null || { echo "xcodegen missing: brew install xcodegen" >&2; exit 1; }
(cd "$ROOT/iosApp" && xcodegen generate --quiet)

DD="$ROOT/iosApp/build/device"
xcodebuild -project "$ROOT/iosApp/SpendSense.xcodeproj" -scheme SpendSense -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath "$DD" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build | tail -3

APP="$DD/Build/Products/Release-iphoneos/SpendSense.app"
[ -d "$APP" ] || { echo "build failed: $APP missing" >&2; exit 1; }

# Hardening gate: never ship debug symbols. The dSYM stays in Products/ (keep it privately for crash symbolication).
if find "$APP" -name '*.dSYM' -o -name '*.swiftmodule' -o -name '*.bcsymbolmap' | grep -q .; then
  echo "refusing to package: debug artifacts inside $APP" >&2; exit 1
fi
BIN="$APP/SpendSense"
if dwarfdump --debug-info "$BIN" 2>/dev/null | grep -q DW_TAG_compile_unit; then
  echo "refusing to package: $BIN still has DWARF debug info" >&2; exit 1
fi
LOCAL_SYMS=$(nm -a "$BIN" 2>/dev/null | grep -c ' [tdbs] ' || true)
if [ "${LOCAL_SYMS:-0}" -gt 50 ]; then
  echo "refusing to package: $BIN is not stripped ($LOCAL_SYMS local symbols)" >&2; exit 1
fi

mkdir -p "$DIST"
STAGE="$(mktemp -d)"
mkdir -p "$STAGE/Payload"
cp -R "$APP" "$STAGE/Payload/"
NEW_IPA="$DIST/SpendSense.ipa.new"
(cd "$STAGE" && zip -qry "$NEW_IPA" Payload)
mv -f "$NEW_IPA" "$DIST/SpendSense.ipa"
rm -r "$STAGE"
cp "$ROOT/iosApp/SpendSense/Assets.xcassets/AppIcon.appiconset/icon-1024.png" "$DIST/icon.png"

VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Info.plist")
BUILD=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$APP/Info.plist")
SIZE=$(stat -f%z "$DIST/SpendSense.ipa")
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

python3 - "$DIST/altstore-source.json" "$BASE" "$VERSION" "$BUILD" "$SIZE" "$DATE" <<'PY'
import json, sys
out, base, version, build, size, date = sys.argv[1:]
source = {
    "name": "SpendSense",
    "identifier": "com.jayant.spendsense.source",
    "subtitle": "Personal expense tracker",
    "description": "Private builds of SpendSense for friends. Add this source in SideStore or AltStore to install and get updates.",
    "sourceURL": base + "altstore-source.json",
    "iconURL": base + "icon.png",
    "tintColor": "#D6B97C",
    "apps": [{
        "name": "SpendSense",
        "bundleIdentifier": "com.jayant.spendsense",
        "developerName": "Jayant Nagori",
        "subtitle": "Where your money went, privately.",
        "localizedDescription": (
            "Tracks UPI, bank and card payments from bank SMS (via a Shortcuts automation), asks what each payment "
            "was for and whether you split it, tracks investments, and answers questions about your money offline "
            "or with your own AI key. All data stays encrypted on the device; Face ID app lock."
        ),
        "iconURL": base + "icon.png",
        "tintColor": "#D6B97C",
        "category": "utilities",
        "screenshots": [],
        "versions": [{
            "version": version,
            "buildVersion": build,
            "date": date,
            "size": int(size),
            "downloadURL": base + "SpendSense.ipa",
            "localizedDescription": "iOS build sharing the Android app's code and UI (Kotlin + Compose Multiplatform).",
            "minOSVersion": "17.2",
        }],
        "appPermissions": {
            "entitlements": [],
            "privacy": {"NSFaceIDUsageDescription": "SpendSense uses Face ID to keep your finances private."},
        },
    }],
    "news": [],
}
with open(out, "w") as f:
    json.dump(source, f, indent=2)
PY

echo "IPA:    $DIST/SpendSense.ipa ($SIZE bytes, v$VERSION ($BUILD))"
echo "Source: $DIST/altstore-source.json  (base $BASE)"
