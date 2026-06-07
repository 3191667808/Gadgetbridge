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

The G2-ADV case live-tested for this PR does not advertise upload-message support in function-info byte 18, so notification pushes are encoded but silently skipped unless firmware reports support. GPS/map, custom clock-face images, OTA, and health tracking are intentionally not exposed for this earbud/case device.

## Deferred by design

OTA firmware update, GPS/map display, clock-face image upload, and health/sport/sleep history remain out of scope because they are either unsupported by G2-ADV firmware or risky without device-specific validation.
