"""Run the full A10ProProtocol against the G2-ADV case BLE channel.
The case speaks FreeFit FB-prefix protocol over BLE — same byte format
as the SPP path we decoded, different transport.
"""
import asyncio
import time
from bleak import BleakClient

MAC    = '41:42:DF:86:3E:EB'
NOTIFY = '6e40fc21-b5a3-f393-e0a9-e50e24dcca9e'
WRITE  = '6e40fc20-b5a3-f393-e0a9-e50e24dcca9e'


def with_checksum(data: bytes) -> bytes:
    s = sum(data) & 0xFFFF
    return data + bytes([s & 0xFF, (s >> 8) & 0xFF])


def hex_p(b: bytes) -> str:
    return " ".join(f"{x:02X}" for x in b)


ANC_LBL = {0: "off", 1: "ANC", 2: "transparency"}
AUDIO_LBL = {0: "music", 1: "movie", 2: "low-latency"}


def decode(data: bytes) -> str:
    if not data: return ""
    op = data[0]
    if op == 0x81:
        return f"SyncTime ACK (status={data[1] if len(data)>1 else '?'})"
    if op == 0x83 and len(data) >= 10:
        return (f"FunctionInfo: ai={data[1]} maxName={data[4]} anc={data[5]} "
                f"customEq={data[6]} aiMode={data[7]} fw={data[8] | (data[9]<<8)}")
    if op == 0x9F:
        try:
            s = data[1:].split(b"\x00", 1)[0].decode("ascii", "replace")
            return f"FW: {s!r}"
        except Exception:
            return f"FW raw: {hex_p(data[1:])}"
    if op == 0xFB and len(data) >= 4:
        sub = data[1]
        if sub == 0x03 and len(data) >= 6:
            l, r = data[4], data[5]
            return (f"Battery push: L={l & 0x7F}%{' (chg)' if l & 0x80 else ''}  "
                    f"R={r & 0x7F}%{' (chg)' if r & 0x80 else ''}")
        if sub == 0x05 and len(data) >= 5:
            return f"ANC mode: {ANC_LBL.get(data[4], data[4])}"
        if sub == 0x07 and len(data) >= 5:
            return f"Audio model: {AUDIO_LBL.get(data[4], data[4])}"
        if sub == 0x04 and len(data) > 6:
            return f"BT name: {data[4:-2].decode('ascii','replace')!r}"
        if sub == 0x06:
            return f"Find ack ({len(data)} bytes)"
        if sub == 0x01:
            return f"EQ payload ({len(data)} bytes)"
        if sub == 0x02:
            return f"KeyCode payload ({len(data)} bytes)"
        return f"FB sub=0x{sub:02X} payload"
    return f"unknown op=0x{op:02X}"


async def main():
    notifs = []

    def on_notify(_h, data):
        notifs.append((time.time(), bytes(data)))
        print(f"  RX: {hex_p(data)}    -> {decode(data)}")

    print(f"Connecting to {MAC} ...")
    async with BleakClient(MAC, timeout=15) as c:
        print(f"Connected. MTU={c.mtu_size}")
        await c.start_notify(NOTIFY, on_notify)
        await asyncio.sleep(1)

        async def send(label, frame, wait_s=2.0):
            print(f"\n>>> {label}")
            print(f"    TX: {hex_p(frame)}")
            await c.write_gatt_char(WRITE, frame, response=False)
            await asyncio.sleep(wait_s)

        # ---------- queries ----------
        await send("queryBattery",   with_checksum(bytes([0xFB, 0x03, 0x00, 0x06])))
        await send("queryAnc",       with_checksum(bytes([0xFB, 0x05, 0x00, 0x06])))
        await send("queryAudio",                  bytes([0xFB, 0x07, 0x00, 0x06]))
        await send("queryEq",        with_checksum(bytes([0xFB, 0x01, 0x00, 0x06])))
        await send("queryKeyCode",   with_checksum(bytes([0xFB, 0x02, 0x00, 0x06])))
        await send("queryBlueName",  with_checksum(bytes([0xFB, 0x04, 0x00, 0x06])))
        await send("getFunction",                 bytes([0x03, 0x00]))
        await send("getFirmware",                 bytes([0x1F]))

        # ---------- ACT (sound + bud bahaviour) ----------
        print("\n--- CONTROL: cycling ANC 1 -> 2 -> 0 (listen for the bud announcing each mode) ---")
        for idx in (1, 2, 0):
            await send(f"setAnc({idx}={ANC_LBL[idx]})",
                       with_checksum(bytes([0xFB, 0x05, 0x01, 0x07, idx])), wait_s=2.5)

        print("\n--- Audio model: movie ---")
        await send("setAudioModel(1=movie)",
                   with_checksum(bytes([0xFB, 0x07, 0x01, 0x07, 0x01])), wait_s=2.0)
        await send("setAudioModel(0=music)",
                   with_checksum(bytes([0xFB, 0x07, 0x01, 0x07, 0x00])), wait_s=1.5)

        print("\n--- Find earphones (BOTH should beep loudly!) ---")
        await send("findHeadphones(both)",
                   with_checksum(bytes([0xFB, 0x06, 0x01, 0x07, 0x03])), wait_s=4.0)
        await send("findHeadphones(stop)",
                   with_checksum(bytes([0xFB, 0x06, 0x01, 0x07, 0x00])), wait_s=1.0)

        print("\nIdle listen 8s for unsolicited frames ...")
        await asyncio.sleep(8)
        await c.stop_notify(NOTIFY)

    print(f"\n=== Summary: {len(notifs)} notify frame(s) received ===")


asyncio.run(main())
