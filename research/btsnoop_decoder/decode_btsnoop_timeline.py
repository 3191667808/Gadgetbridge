"""Build a chronological timeline of YCBT/JieLi-style BLE measurement traffic."""
import struct
import sys
from pathlib import Path


NAMES = {
    0x0100: "GetDeviceInfo",
    0x0101: "GetBattery",
    0x0103: "SetTime",
    0x0104: "EnableHealthSensors",
    0x0105: "PUSH_DeviceVersion?",
    0x0109: "SetUserInfo",
    0x010C: "GetDeviceName",
    0x0112: "SetUnitsLanguage?",
    0x0126: "SetLanguage?",
    0x0200: "GetDeviceSupportFn",
    0x0201: "GetDeviceStorageInfo",
    0x0203: "GetSportData?",
    0x020C: "SetSomething",
    0x021B: "GetCapability?",
    0x0226: "GetSleepStatus",
    0x0309: "AppCtl_0x09",
    0x030C: "AppCtl_0x0C_PrimePPG",
    0x032F: "APP_START_MEASUREMENT",
    0x033D: "AppCtl_0x3D",
    0x0404: "DevCtl_0x04",
    0x040C: "DevCtl_0x0C",
    0x040E: "MeasurementComplete",
    0x0502: "Health_HistoryCount?",
    0x0504: "Health_HistorySleep",
    0x0506: "Health_HistoryHeart",
    0x0508: "Health_HistoryBlood",
    0x0509: "Health_HistoryAll",
    0x0513: "STREAM_SLEEP",
    0x0515: "STREAM_HEART",
    0x0517: "STREAM_BLOOD",
    0x0518: "STREAM_ALL",
    0x051A: "Health_HistoryBloodOxygen",
    0x0540: "Health_DeleteSport",
    0x0541: "Health_DeleteSleep",
    0x0542: "Health_DeleteHeart",
    0x0543: "Health_DeleteBlood",
    0x0544: "Health_DeleteAll",
    0x0580: "HISTORY_ACK",
    0x0600: "REAL_SNAPSHOT",
    0x0601: "REAL_HR",
    0x0602: "REAL_SPO2",
    0x0603: "REAL_BP",
}


def parse_btsnoop(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"btsnoop\x00", "not a btsnoop file"
    pos = 16
    records = []
    while pos + 24 <= len(data):
        _orig, incl, flags, _drops, ts_hi, ts_lo = struct.unpack(
            ">IIIIII", data[pos:pos + 24]
        )
        pos += 24
        payload = data[pos:pos + incl]
        pos += incl
        ts = (ts_hi << 32) | ts_lo
        records.append((ts, flags, payload))
    return records


def extract_frames(records):
    frames = []
    for ts, flags, p in records:
        if len(p) < 12 or len(p) > 80:
            continue
        direction_in = (flags & 1) == 1
        for off in range(8, min(len(p), 18)):
            if off + 6 > len(p):
                break
            grp, key, lenlo, lenhi = p[off], p[off + 1], p[off + 2], p[off + 3]
            if grp not in (0x01, 0x02, 0x03, 0x04, 0x05, 0x06):
                continue
            total = lenlo | (lenhi << 8)
            if not (6 <= total <= 200) or off + total > len(p):
                continue
            opcode = (grp << 8) | key
            body = p[off + 4: off + total - 2]
            frames.append((ts, "<" if direction_in else ">", opcode, body.hex()))
            break
    frames.sort(key=lambda x: x[0])
    return frames


def main():
    log_path = sys.argv[1] if len(sys.argv) > 1 else "ble_dumps/extracted/btsnoop_hci.log"
    frames = extract_frames(parse_btsnoop(log_path))
    print(f"Total framed exchanges: {len(frames)}")

    print("\n=== Measurement trigger sequences (0x032F + neighbors) ===")
    trigger_idx = [i for i, f in enumerate(frames) if f[2] == 0x032F]
    seen = set()
    for idx in trigger_idx:
        block = frames[max(0, idx - 3):idx + 8]
        sig = tuple((f[1], f[2], f[3][:4]) for f in block)
        if sig in seen:
            continue
        seen.add(sig)
        print("\n  --- sequence ---")
        for _ts, d, opc, body in block:
            n = NAMES.get(opc, f"0x{opc:04X}")
            print(f"   {d} 0x{opc:04X} {n:28} {body}")

    print("\n=== All push payloads ===")
    for opc in (0x0601, 0x0602, 0x0603, 0x0600):
        pls = [b for _ts, d, o, b in frames if o == opc and d == "<"]
        print(f"\n 0x{opc:04X} {NAMES[opc]} ({len(pls)} frames)")
        for pl in pls:
            print(f"   <- {pl}")

    print("\n=== Auxiliary opcode exchanges ===")
    for opc in (0x030C, 0x040C, 0x033D, 0x0109, 0x040E,
                0x021B, 0x0226, 0x051A, 0x0105):
        pls = [(d, b) for _ts, d, o, b in frames if o == opc]
        print(f"\n 0x{opc:04X} {NAMES.get(opc, '?')} ({len(pls)})")
        for d, pl in pls:
            print(f"   {d} {pl}")


if __name__ == "__main__":
    main()
