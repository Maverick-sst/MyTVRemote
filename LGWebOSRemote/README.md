# My TV Remote

A minimal Android remote for LG webOS televisions, designed for supported 2020 UN73-series models.

## Features
- SSDP discovery of devices on the same Wi-Fi network
- LG webOS pairing prompt and saved client key
- D-pad (up/down/left/right + OK), Home, volume and power-off controls
- Wake-on-LAN power-on (requires TV MAC address and standby network wake support)

## Build
A GitHub Actions workflow is included at `.github/workflows/android.yml`. After the project is pushed to the `main` branch, GitHub builds a debug APK. Download `MyTVRemote-debug-apk` from the workflow run's Artifacts section.

## Notes
- Phone and TV must be on the same local network.
- First connection requires accepting the access prompt on the TV.
- Discovery uses SSDP. Some routers suppress multicast or isolate Wi-Fi clients; if discovery fails, use the TV's IP address manually.
- Bluetooth is not used. LG webOS remote control is performed over Wi-Fi.
- Power-on is best-effort Wake-on-LAN; the TV and router must support network wake while in standby.
- The app has not been verified against the user's physical television.
