# My TV Remote

A minimal, ad-free Android remote for LG webOS televisions.

## Download

Get the current build from the rolling release:

<https://github.com/Maverick-sst/MyTVRemote/releases/latest/download/MyTVRemote.apk>

## Features

- SSDP discovery on the local network, plus manual IP entry
- LG webOS pairing prompt with saved client key
- D-pad, OK, Back, Home, volume, mute, channel and playback controls
- Trackpad mode using LG's pointer-input socket
- App shortcuts (Netflix, YouTube, Prime Video, Hotstar)
- HDMI/Live TV input switching and text entry
- Power off and best-effort Wake-on-LAN power on

## Project layout

- `app/src/main/java/com/maverick/lgremote/MainActivity.kt` — all UI and protocol code
- `.github/workflows/android.yml` — validation builds on pull requests, signed release on `main`

## Build

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleRelease
```

Requirements: JDK 17 and Android SDK platform 35.

Release signing is configured through the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment variables. When they are absent the build signs with the debug key so local and pull-request builds still work.

The release build enables R8 and resource shrinking, which keeps the APK around 240 KB.

## Protocol notes

The app speaks LG's second-screen protocol directly over Wi-Fi:

- WebSocket on port 3000 carries `register` pairing and SSAP requests
- `ssap://com.webos.service.networkinput/getPointerInputSocket` returns a second socket for D-pad, button and pointer events
- Button events use the pointer socket (`type:button`), media and app commands use SSAP requests

Bluetooth is not used.

## Limitations

- Phone and TV must be on the same Wi-Fi network.
- Some routers block SSDP multicast or isolate clients; use **Enter IP** in that case.
- Wake-on-LAN requires the TV MAC address and standby network wake support.
- Tested logic targets webOS 5-era LG TVs; other webOS versions should work but are not guaranteed.
