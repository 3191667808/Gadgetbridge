# FreeFit iEnjoy V2 / G2-ADV Feature Catalog

This catalog summarizes the FreeFit firmware-family coverage wired by the Gadgetbridge A10 Pro / G2-ADV driver. It is based on the decompiled `com.czw.freefit.ienjoy` app and live validation against a G2-ADV LCD charging case.

## Wired in this PR

| Area | Opcode(s) | Gadgetbridge hook |
|---|---:|---|
| Battery, ANC, EQ, gestures, BT name, audio preset, find earbuds | `0xFB` subcommands | initialization + device settings |
| Firmware and function-info discovery | `0x1F`, `0x03` | initialization |
| Time sync | `0x01`, `0x0D` | connect + `onSetTime` |
| Weather family auto-detect | ACK `0x85` / `0x90` / `0xA5` | response decoder |
| JL 4-day weather frame | `0x10` | `onSendWeather` |
| Music state and metadata | `0x41`, `0x99` | `onSetMusicInfo`, `onSetMusicState` |
| Incoming / answered / declined / ended calls | `0x55` | `onSetCallState` |
| Reverse find-phone request | `0x53` | response decoder -> `GBDeviceEventFindPhone` |
| Notifications | `0x73` / `0x23` | `onNotification`, gated by function-info byte 18 |
| Alarm list | `0x02 0x03` | `onSetAlarms` |
| User profile | `0x02 0x01` | connect initialization |
| Anti-lost, find-band, units, volume cap | `0x70`, `0x51`, `0x11`, `0xFB 08` | device settings |
| Scrolling text / barrage | `0xBB` | device settings |
| Power-off and factory reset | `0xAD`, `0x71` | danger-zone settings |

## Live-validated JL weather layout

`0x10 00 [today icon] [current temp] [today high] [today low] [D+1 icon/high/low] [D+2 icon/high/low] [D+3 icon/high/low] [UV] [wind direction] [humidity] 00 00`

Live-confirmed icon codes include: `0` cloud, `1` sun, `4` thunderstorm with sun, `7` heavy rain, `11` drizzle, `22` heavy rain alt, `33` thunderstorm, `38` snow, `39` rain, `40` cloud.

## G2-ADV firmware gates

The G2-ADV case live-tested for this PR does not set function-info byte 18 bit 0, but the official zwsvibe app still pushes `0x73 MsgPush` frames that the case acknowledges. We therefore default `supportsUploadMessage` to true and use the byte-18 bit purely as an informational marker.

## RCSP authentication

The `jl_rcsp.JieliRcspAuth` helper ports the MIT-licensed JieLi RCSP challenge/response cipher from `hybridherbst/web-bluetooth-e87` (TypeScript) to Java, with a parity unit test against the upstream reference vector. This unlocks the proprietary feature path used by zwsvibe:

* **Contacts upload** (`com.jieli.jl_rcsp.task.contacts.UpdateContactsTask` analogue)
* **GPS map / navigation** (`0xB4 NaviInfo`, `0xDD GPS`, `0x07 GPS-Address`, `0x18 PositionInfo`)
* **Custom clock-face / dial image upload** (`0x98 DialSet` + RCSP file transfer)
* **OTA firmware update** (`0xFA OTAState` + RCSP file transfer)

These features are not yet wired into Gadgetbridge UI surfaces but the auth primitive (`getRandomAuthData`, `getEncryptedAuthData`) is in place and verified.

## RCSP-only features (auth available, wiring deferred)

The following vendor-app capabilities flow through RCSP-authenticated channels:

* **Contacts upload** — `com.jieli.jl_rcsp.task.contacts.UpdateContactsTask`
* **GPS map / navigation** — `0xB4 NaviInfo`, `0xDD GPS`, `0x07 GPS-Address`, `0x18 PositionInfo`
* **Custom clock-face / dial image upload** — `0x98 DialSet` + RCSP file transfer
* **OTA firmware update** — `0xFA OTAState` + RCSP file transfer

The authentication handshake itself is now available via `JieliRcspAuth` (ported from MIT-licensed `web-bluetooth-e87`). Wiring these higher-level RCSP file-transfer protocols into Gadgetbridge is the next milestone; each requires its own framing layer that we have not yet ported.
