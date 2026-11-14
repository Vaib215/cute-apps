#!/bin/zsh
# Build Cute Touch.
#   ./build.sh            build, install to /Applications and (re)start it
#   ./build.sh app        only build build/Cute Touch.app
#   ./build.sh release    ad-hoc signed build/CuteTouch-<version>.dmg for GitHub Releases
set -e
cd "${0:A:h}"
NAME="Cute Touch"
ID=com.vaib215.cutetouch
VERSION=1.0
DEST="/Applications/$NAME.app"
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
    <key>CFBundleShortVersionString</key><string>$VERSION</string>
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

# "Open at login" agent (SMAppService.agent): starts in the background, restarts after a crash,
# stays quit after Quit.
mkdir -p "$APP/Contents/Library/LaunchAgents"
cat > "$APP/Contents/Library/LaunchAgents/$ID.login.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$ID.login</string>
    <key>BundleProgram</key><string>Contents/MacOS/CuteTouch</string>
    <key>ProgramArguments</key>
    <array>
        <string>Contents/MacOS/CuteTouch</string>
        <string>--background</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
</dict>
</plist>
PLIST

swiftc -O *.swift -o "$APP/Contents/MacOS/CuteTouch"
if [[ "$1" == release ]]; then
    # Downloads can't use your local identity; ad-hoc it is (users approve it once in
    # System Settings → Privacy & Security → Open Anyway).
    codesign --force --sign - "$APP"
    STAGE=build/dmg
    rm -rf "$STAGE" && mkdir -p "$STAGE"
    cp -R "$APP" "$STAGE/"
    ln -s /Applications "$STAGE/Applications"
    DMG="build/CuteTouch-$VERSION.dmg"
    rm -f "$DMG"
    hdiutil create -volname "$NAME" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
    rm -rf "$STAGE"
    echo "$DMG"
    exit 0
elif security find-identity -p codesigning | grep -q "\"$SIGN_ID\""; then
    codesign --force --sign "$SIGN_ID" "$APP"
else
    echo "note: no \"$SIGN_ID\" identity; signing ad-hoc (permissions reset on every rebuild)." >&2
    codesign --force --sign - "$APP"
fi
[[ "$1" == app ]] && exit 0

# Older builds installed a hand-written LaunchAgent; the bundle carries its own now.
if [[ -f ~/Library/LaunchAgents/$ID.plist ]]; then
    launchctl bootout gui/$(id -u)/$ID 2>/dev/null || true
    rm -f ~/Library/LaunchAgents/$ID.plist
fi
pkill -x CuteTouch 2>/dev/null || true
rm -rf "$DEST"
mv "$APP" "$DEST"
# Restart through the login agent if it's registered, otherwise just launch in the background.
launchctl kickstart -k gui/$(id -u)/$ID.login 2>/dev/null || open -a "$DEST" --args --background
