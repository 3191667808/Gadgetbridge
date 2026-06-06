"""Push weather + GPS + time + notifications to the G2-ADV case LCD.

Protocol decoded from FreeFitDevice.java (companion app zwsvibe).
All opcodes are GENTLE — no fuzzing. If the case has the feature,
the LCD updates. If not, the command is silently ignored.
"""
import asyncio, time
from bleak import BleakClient

MAC    = '41:42:DF:86:3E:EB'
WRITE  = '6e40fc20-b5a3-f393-e0a9-e50e24dcca9e'
NOTIFY = '6e40fc21-b5a3-f393-e0a9-e50e24dcca9e'

# Opcodes from IFreeFitCommand.java + encoders from FreeFitDevice.java
OP_SYNC_TIME    = 0x01        # [01][ts:4 BE][tz:4 BE][hrFmt][lang]
OP_GET_FUNCTION = 0x03        # [03 00]
OP_WEATHER      = 0x05        # [05][weatherCode][tempNow][tempMax]
OP_GPS_FIRST    = 0x07        # [07 00 FF FE <utf16-bytes-first-15>]
OP_GPS_NEXT     = 0x07        # [07 <pktIdx> <utf16-bytes>]
OP_REQ_TIME     = 0x0D        # device->phone request for time
OP_VERSION      = 0x1F
OP_MSG_PUSH     = 0x73        # notifications
OP_CALL         = 0x55
OP_WK_STRING    = 0xB9        # arbitrary text
OP_BARRAGE      = 0xBB        # scrolling marquee


def hex_p(b: bytes) -> str: return " ".join(f"{x:02X}" for x in b)


# Weather code map (FreeFitDevice WeatherUtil) — index = code
WEATHER_LBL = ["sunny", "few-clouds", "partly-cloudy", "cloudy", "overcast",
               "windy", "calm", "high-wind", "hurricane", "storm",
               "haze", "shower", "thunderstorm", "thunderstorm-hail",
               "light-rain", "rain", "heavy-rain", "rainstorm",
               "downpour", "extreme-rain", "heavy-shower",
               "heavy-thunderstorm", "extreme-rain-2", "sleet",
               "snow", "snow-shower", "light-snow", "snow", "heavy-snow",
               "snowstorm", "dust", "sand", "sandstorm",
               "heavy-sandstorm", "tornado", "fog", "hot", "cold"]


def encode_sync_time(is_24h=True, lang=0) -> bytes:
    now = int(time.time())
    tz = -time.altzone if time.daylight else -time.timezone
    return bytes([
        OP_SYNC_TIME,
        (now >> 24) & 0xFF, (now >> 16) & 0xFF, (now >> 8) & 0xFF, now & 0xFF,
        (tz  >> 24) & 0xFF, (tz  >> 16) & 0xFF, (tz  >> 8) & 0xFF, tz  & 0xFF,
        1 if is_24h else 2, lang
    ])


def encode_weather(weather_code: int, temp_now: int, temp_max: int) -> bytes:
    return bytes([OP_WEATHER, weather_code & 0xFF,
                  temp_now & 0xFF, temp_max & 0xFF])


def encode_gps_address(address: str) -> list[bytes]:
    """Multi-packet UTF-16BE string send (max 64 chars).
    First packet has header [07 00 FF FE] then 15 chars.
    Subsequent packets [07 <idx> <chars>] up to 20 bytes each.
    """
    if len(address) > 64:
        address = address[:61] + "..."
    # Convert to UTF-16BE bytes
    utf16 = address.encode("utf-16-be")
    packets = []
    pkt_idx = 0
    # First packet: 4-byte header + 16 bytes payload
    first = bytes([OP_GPS_FIRST, 0x00, 0xFF, 0xFE]) + utf16[:16]
    # pad first packet to 20 bytes
    first = first.ljust(20, b"\x00")
    packets.append(first)
    remaining = utf16[16:]
    pkt_idx = 1
    while remaining:
        chunk = remaining[:18]
        remaining = remaining[18:]
        pkt = bytes([OP_GPS_FIRST, pkt_idx]) + chunk
        pkt = pkt.ljust(20, b"\x00")
        packets.append(pkt)
        pkt_idx += 1
    return packets


def encode_msg_push(text: str, app_id: int = 0) -> bytes:
    """Simple notification — opcode 0x73 then app_id then UTF-16BE text."""
    body = text.encode("utf-16-be")[:40]
    return bytes([OP_MSG_PUSH, app_id & 0xFF]) + body


def encode_wk_string(text: str) -> bytes:
    """Push raw text string — opcode 0xB9."""
    body = text.encode("utf-16-be")[:40]
    return bytes([OP_WK_STRING]) + body


def decode_notify(data: bytes) -> str:
    if not data: return ""
    op = data[0]
    if op == 0x81: return f"SyncTime ACK (status={data[1] if len(data)>1 else '?'})"
    if op == 0x83 and len(data) >= 10:
        return f"FunctionInfo  fw={data[8]|(data[9]<<8)}"
    if op == 0x9F:
        return f"FW: {data[1:].rstrip(bytes([1,10,0])).decode('ascii','replace')!r}"
    if op == 0xFB and len(data) >= 6 and data[1] == 0x03:
        l, r = data[4], data[5]
        return f"Battery: L={l & 0x7F}% R={r & 0x7F}%"
    if op == OP_REQ_TIME:
        return "** CASE asked us for the time **"
    if op == 0x02:
        return f"Push data 02 sub=0x{data[1]:02X} ({len(data)} bytes)"
    return f"op=0x{op:02X} ({len(data)} bytes)"


async def main():
    print(f"Connecting to G2-ADV case @ {MAC} ...")
    async with BleakClient(MAC, timeout=20) as c:
        print(f"Connected. MTU={c.mtu_size}")

        def on_notify(_h, d):
            print(f"  RX: {hex_p(d):<60} -> {decode_notify(d)}")

        await c.start_notify(NOTIFY, on_notify)
        await asyncio.sleep(0.5)

        # 1. Sync the time first — case may need this before accepting weather
        f = encode_sync_time()
        print(f"\n>>> syncTime\n    TX: {hex_p(f)}")
        await c.write_gatt_char(WRITE, f, response=False)
        await asyncio.sleep(2)

        # 2. Push weather: sunny (code 0), now 22°C, max 28°C
        f = encode_weather(weather_code=0, temp_now=22, temp_max=28)
        print(f"\n>>> weather: {WEATHER_LBL[0]} now=22°C max=28°C\n    TX: {hex_p(f)}")
        await c.write_gatt_char(WRITE, f, response=False)
        await asyncio.sleep(3)

        # 3. Push a GPS / address string
        addr = "Tel Aviv, Israel"
        packets = encode_gps_address(addr)
        print(f"\n>>> GPS address: {addr!r}  ({len(packets)} packet(s))")
        for p in packets:
            print(f"    TX: {hex_p(p)}")
            await c.write_gatt_char(WRITE, p, response=False)
            await asyncio.sleep(0.15)
        await asyncio.sleep(3)

        # 4. Cycle through 3 different weather codes so user sees the LCD change
        for code, tnow, tmax in [(11, 18, 22), (24, 0, 3), (35, 30, 38)]:
            f = encode_weather(code, tnow, tmax)
            print(f"\n>>> weather: {WEATHER_LBL[code]} now={tnow}°C max={tmax}°C\n    TX: {hex_p(f)}")
            await c.write_gatt_char(WRITE, f, response=False)
            await asyncio.sleep(3)

        # 5. Push a notification text
        for text in ("Hello from Gadgetbridge", "Test message", "GB"):
            f = encode_msg_push(text)
            print(f"\n>>> msg push: {text!r}\n    TX: {hex_p(f)}")
            await c.write_gatt_char(WRITE, f, response=False)
            await asyncio.sleep(2)

        print("\nIdle 5s — watch the LCD!")
        await asyncio.sleep(5)
        await c.stop_notify(NOTIFY)


asyncio.run(main())
