#!/bin/zsh
# Build Cute Touch into /Applications (visible to Launchpad/Spotlight) and (re)start its LaunchAgent.
#   ./build.sh            build, install, start at login
#   ./build.sh app        only build build/Cute Touch.app
set -e
cd "${0:A:h}"
NAME="Cute Touch"
ID=com.vaib215.cutetouch
DEST="/Applications/$NAME.app"
AGENT=~/Library/LaunchAgents/$ID.plist
# A stable signing identity keeps macOS permissions (Screen Recording, Accessibility) across
# rebuilds; ad-hoc signatures change every build. Create one with tools/make-signing-cert.sh.
SIGN_ID="${CUTE_TOUCH_SIGN_ID:-Cute Touch Local Signing}"

# Stage and sign the bundle in build/, then swap it in while the app is stopped: re-signing or
# rewriting a running app's bundle gets it killed with OS_REASON_CODESIGNING.
APP="build/$NAME.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>$ID</string>
    <key>CFBundleName</key><string>$NAME</string>
    <key>CFBundleDisplayName</key><string>$NAME</string>
    <key>CFBundleExecutable</key><string>CuteTouch</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>26.0</string>
    <key>LSUIElement</key><true/>
</dict>
</plist>
PLIST

if [[ ! -f build/AppIcon.icns ]]; then
    swiftc -parse-as-library tools/icon.swift cat.swift -o build/icon
    build/icon build/icon_1024.png
    rm -rf build/AppIcon.iconset && mkdir build/AppIcon.iconset
    for s in 16 32 128 256 512; do
        sips -z $s $s build/icon_1024.png --out build/AppIcon.iconset/icon_${s}x${s}.png >/dev/null
        sips -z $((s*2)) $((s*2)) build/icon_1024.png --out build/AppIcon.iconset/icon_${s}x${s}@2x.png >/dev/null
    done
    iconutil -c icns build/AppIcon.iconset -o build/AppIcon.icns
fi
cp build/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

swiftc -O *.swift -o "$APP/Contents/MacOS/CuteTouch"
if security find-identity -p codesigning | grep -q "\"$SIGN_ID\""; then
    codesign --force --sign "$SIGN_ID" "$APP"
else
    echo "note: no \"$SIGN_ID\" identity; signing ad-hoc (permissions reset on every rebuild)." >&2
    codesign --force --sign - "$APP"
fi
[[ "$1" == app ]] && exit 0

launchctl bootout gui/$(id -u)/$ID 2>/dev/null || true
pkill -x CuteTouch 2>/dev/null || true
rm -rf "$DEST"
mv "$APP" "$DEST"

if [[ ! -f "$AGENT" ]]; then
    cat > "$AGENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$ID</string>
    <key>ProgramArguments</key>
    <array>
        <string>$DEST/Contents/MacOS/CuteTouch</string>
        <string>--background</string>
    </array>
    <key>RunAtLoad</key><true/>
    <!-- Restart after crashes, but stay quit after "Quit" (clean exit). -->
    <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
</dict>
</plist>
PLIST
fi
launchctl bootstrap gui/$(id -u) "$AGENT"
