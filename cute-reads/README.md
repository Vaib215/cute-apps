# Cute Reads

A calm, distraction-free e-book reader for Android. Black page, white text, nothing else until you ask for it.

[![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/Vaib215)

## Features

- **Just the page.** Full screen, no status bar. Previous/next buttons at the bottom; the page number opens the options.
- **Library** with covers (the book's own, or a typeset one), most recently read first, and progress.
- **Open EPUB and TXT** from your phone's files.
- **Night / day**, **warmth** (like Night Shift), **brightness**, **text size**, and five reading fonts.
- **Highlights, notes and bookmarks**, listed together with their pages.
- **Contents**, **find in book**, and **go to page**.
- **Do Not Disturb while reading**: notifications are silenced while a book is open; alarms still ring.
- **Screen timer**: keep the screen on for as long as you read, or let it sleep after 2–30 minutes.
- Works offline. No ads, no tracking, no accounts, no internet permission.

## Install

Download `CuteReads-<version>.apk` from [Releases](https://github.com/Vaib215/cute-apps/releases) and open it on your phone. Android 8.0 or newer.

## Build

Needs only the Android SDK (build-tools and a platform) and a JDK. No Gradle.

```sh
./build.sh            # CuteReads.apk, debug-signed
./build.sh install    # ...and install over ADB
./build.sh release    # CuteReads-<version>.apk, signed with your release key
```

`release` reads the keystore from `~/.config/cute-apps/release.jks` and its password from `~/.config/cute-apps/release.pass` (override with `READ_KEYSTORE` / `READ_KEYSTORE_PASS_FILE`).

## Adding bundled books

```sh
python3 prep_gutenberg.py <gutenberg-id> <file-name> [--cover]
```

writes `books/<file-name>.txt` (the reader's format), `.meta` (title, author) and optionally `.jpg` (cover).

## Credits

- Fonts: [Literata](https://github.com/googlefonts/literata), [Charis SIL](https://software.sil.org/charis/), [Merriweather](https://github.com/EbenSorkin/Merriweather4) and [Atkinson Hyperlegible](https://www.brailleinstitute.org/freefont/), under the SIL Open Font License 1.1 (see `fonts/licenses`).
- Bundled books (*Siddhartha*, *The Great Gatsby*) are public-domain texts.
- Icons follow Material Design icon shapes (Apache 2.0).

## License

[MIT](../LICENSE)
