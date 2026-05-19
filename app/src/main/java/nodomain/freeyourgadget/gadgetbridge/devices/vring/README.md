# VRing R26 (MO YOUNG "Da Ring") support for Gadgetbridge

Module path: `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/vring/`
& `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/vring/`

## Device

- **Hardware**: VRing R26 smart ring (also sold under aliases of the MO YOUNG LTD ring family)
- **Vendor app**: "Da Ring" — `com.moyoung.ring`
- **Advertised BLE name**: `VRing`
- **Service UUID**: `0xFDDA` (registered to MHCS, used by the CRP/Sifli SDK family)
- **Pairing**: open / no bond, no authentication handshake

## Protocol summary (reverse-engineered)

All commands use a 6-byte framing on the FDDA service:

```
FD DA 10 <total_len> <category> <command> <payload...>
```

`total_len` includes the 6-byte header.

### Characteristics

| UUID | Direction | Purpose |
|------|-----------|---------|
| `FDD1` | read + notify | Current totals: 3 × LE24 = steps, distance(m), calories (optional 4th LE24 = time) |
| `FDD2` | write | Default command channel |
| `FDD3` | notify | Command responses & notifications |
| `FDD5` | write | Alt channel (category 0xF1) |
| `FDD6` | write | Alt channel (category 0xF2) |
| `2A19` | read + notify | Standard battery level |
| `2A28` | read | Standard software revision |
| `2A29` | read | Standard manufacturer name |

### Time sync (cat=1 cmd=1)

5-byte payload = `[epoch_le32][0x08]` where the epoch is the user's local wall time re-interpreted as if it were GMT+8 (the ring's internal reference timezone). This must be sent before any history query, or the ring returns empty buffers.

### Key categories / commands

| Cat | Cmd | Meaning | Response format |
|-----|-----|---------|-----------------|
| 1 | 1 | Set time | ack |
| 1 | 9 | Start HR measurement | live HR push (single byte BPM in cat=1 cmd=9 notification) |
| 2 | 0 | Query device info | 5 bytes |
| 2 | 9 | Query HR history | `[marker] + N × (bpm + ts_LE32)` |
| 2 | 11 | Query SpO2 history | same shape as HR |
| 2 | 13 | Query history steps (per day) | `day + 4 × LE32` (steps, distance, calories, activeMin) |
| 2 | 15 | Timing HR (paginated) | per-page; client must re-request next page |
| 2 | 18 | Step details (per day) | `day + N × LE16` step buckets (30-min slots) |
| 2 | 32 | Stress history | same shape as HR |
| 3 | 1 | Shutdown | ack |
| 9 | 2 | Find device | (no haptic on this hardware) |

### Byte order quirks

- LE24, LE32 throughout (vendor uses `bArr[0] | (bArr[1]<<8) | (bArr[2]<<16)`)
- HR timestamps in history list are **little-endian uint32** seconds since epoch (NOT big-endian as some CRP SDK docs suggest)

## Module layout

```
devices/vring/
  VRingR26Constants.java          UUIDs, opcodes, magic bytes
  VRingR26Coordinator.java        scan filter, capabilities, sample provider wiring
  samples/
    VRingR26ActivitySampleProvider.java
service/devices/vring/
  VRingR26DeviceSupport.java      connection, GATT callbacks, parsers, persistence
  VRingR26Packet.java             encode/decode + static packet builders
entities/
  AbstractVRingR26ActivitySample.java   distanceCm/activeCalories overrides
```

DAO entity generated from `GBDaoGenerator.java::addVRingR26ActivitySample` — adds `VRingR26ActivitySample` with steps/distance/calories columns on top of the standard activity-sample shape.

## What works

- ✅ Connect / discover services / bond-less pairing
- ✅ Battery level (read + push)
- ✅ Time sync
- ✅ Current step counter (direct FDD1 read + push)
- ✅ 7-day history sync on connect and on user-triggered "fetch data":
    - Daily totals (steps, distance, calories, active minutes) → `VRingR26ActivitySample`
    - Per-30-min step buckets → `VRingR26ActivitySample` rows
    - HR history → `GenericHeartRateSample`
    - SpO2 / stress (when present) → `GenericSpo2Sample` / `GenericStressSample`
- ✅ Manual HR test (~15s on-finger)
- ✅ Find-device opcode (sent, but the R26 hardware has no motor/LED so no user-visible effect)
- ✅ Sample data automatically eligible for Google Fit / Health Connect via Gadgetbridge's built-in export

## Known limitations / TODO

- No notification → ring (the R26 has no display / motor / LED, so no useful surface)
- HRV decoder not implemented (no live samples captured to validate format)
- Sleep parser not implemented (cat=2 cmd=25 — format observed but not yet decoded against ground truth)
- Per-bucket activity intensity is heuristic (steps/min × 2); tune once we have user feedback
- Time-sync logic assumes the user's local zone matches the ring's "GMT+8 reference" trick used by the vendor — works in all common cases but may need refinement for users in non-DST zones

## Testing

Tested against one physical R26 unit. Protocol parsers were derived from analysing the vendor "Da Ring" Android app's BLE traffic patterns (the app is built on a CRP-style SDK common to several MO YOUNG / Colmi / VRing ring SKUs).
