# My TV Remote

A tiny, ad-free Android remote for LG webOS televisions.

## Download

**[Download MyTVRemote.apk](https://github.com/Maverick-sst/MyTVRemote/releases/latest/download/MyTVRemote.apk)** (about 240 KB)

The link always points at the newest build. Open it on your phone and install the APK; Android will ask you to allow installs from your browser the first time.

## What it does

- Finds LG TVs on your Wi-Fi automatically, or connects by IP
- Pairs once and remembers the TV
- D-pad, OK, Back, Home, volume, mute, channel, playback
- Trackpad mode with pointer support
- App shortcuts: Netflix, YouTube, Prime Video, Hotstar
- HDMI input switching and text entry on the TV
- Power off, plus best-effort Wake-on-LAN power on

## Install notes

- Phone and TV must be on the same Wi-Fi network.
- The first connection shows an access prompt on the TV; accept it once.
- If discovery is blocked by your router, choose **Enter IP** and use the address shown in TV Settings → Network.
- Wake-on-LAN needs the TV MAC address, which you can add under Settings.
- The APK is signed with a stable release key, so newer builds install over older ones without uninstalling.

## Build

The app lives in [`LGWebOSRemote/`](LGWebOSRemote/). GitHub Actions builds a signed release on every push to `main` and refreshes the `latest` release asset; pull requests only run a validation build.

To build locally:

```bash
cd LGWebOSRemote
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleRelease
```

Release signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment. Without them the build falls back to the debug key.
