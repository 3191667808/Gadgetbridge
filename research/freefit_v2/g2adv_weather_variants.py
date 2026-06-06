"""Try JL and ZK weather variants — the FIR opcode 0x05 didn't update the LCD."""
import asyncio, time
from bleak import BleakClient

MAC    = '41:42:DF:86:3E:EB'
WRITE  = '6e40fc20-b5a3-f393-e0a9-e50e24dcca9e'
NOTIFY = '6e40fc21-b5a3-f393-e0a9-e50e24dcca9e'


def hp(b): return " ".join(f"{x:02X}" for x in b)


def encode_weather_jl(weather_code: int, t_now: int, t_max: int,
                       t_night: int = 18, humidity: int = 50,
                       wind_lvl: int = 3, wind_dir: int = 1) -> bytes:
    """JL mode opcode 0x10, 20 bytes."""
    b = bytearray(20)
    b[0]  = 0x10
    b[1]  = 0x00
    b[2]  = weather_code & 0xFF
    b[3]  = t_now & 0xFF
    b[4]  = t_max & 0xFF
    b[5]  = t_night & 0xFF
    b[6]  = humidity & 0xFF
    b[7]  = wind_lvl & 0xFF
    b[8]  = wind_dir & 0xFF
    # bytes 9..19 typically extended forecast / city code; leave 0
    return bytes(b)


def encode_weather_zk(t_now: int, t_max: int, weather_code: int,
                       city: str = "Tel Aviv") -> bytes:
    """ZK mode opcode 0x25, 20 bytes (i1=tNow i2=tMax i3=code + city utf-8 padded 0xFF)."""
    b = bytearray(20)
    b[0]  = 0x25                   # 37
    b[1]  = t_now & 0xFF
    b[2]  = t_max & 0xFF
    b[3]  = weather_code & 0xFF
    city_b = city.encode("utf-8")[:16]
    for i, c in enumerate(city_b):
        b[4 + i] = c
    for i in range(4 + len(city_b), 20):
        b[i] = 0xFF
    return bytes(b)


async def main():
    print(f"Connecting...")
    async with BleakClient(MAC, timeout=20) as c:
        print(f"Connected MTU={c.mtu_size}")

        def on_notify(_h, d):
            print(f"  RX: {hp(d)}")

        await c.start_notify(NOTIFY, on_notify)
        await asyncio.sleep(0.5)

        # --- JL variant — sunny 22 / 28 / 16 night, 55% humidity, wind 3 east ---
        f = encode_weather_jl(weather_code=0, t_now=22, t_max=28,
                              t_night=16, humidity=55, wind_lvl=3, wind_dir=1)
        print(f"\n>>> JL weather sunny 22°C max28°C\n    TX: {hp(f)}")
        await c.write_gatt_char(WRITE, f, response=False)
        await asyncio.sleep(4)

        # --- JL: cloudy 18 / 24 ---
        f = encode_weather_jl(weather_code=3, t_now=18, t_max=24)
        print(f"\n>>> JL weather cloudy 18°C max24°C\n    TX: {hp(f)}")
        await c.write_gatt_char(WRITE, f, response=False)
        await asyncio.sleep(4)

        # --- ZK variant — sunny 25 / 30 + city "Tel Aviv" ---
        f = encode_weather_zk(t_now=25, t_max=30, weather_code=0, city="Tel Aviv")
        print(f"\n>>> ZK weather sunny 25°C max30°C city Tel Aviv\n    TX: {hp(f)}")
        await c.write_gatt_char(WRITE, f, response=False)
        await asyncio.sleep(4)

        # --- ZK: rain 18 / 22 + city "London" ---
        f = encode_weather_zk(t_now=18, t_max=22, weather_code=11, city="London")
        print(f"\n>>> ZK weather rain 18°C max22°C city London\n    TX: {hp(f)}")
        await c.write_gatt_char(WRITE, f, response=False)
        await asyncio.sleep(4)

        print("\nIdle 5s — watch the LCD")
        await asyncio.sleep(5)
        await c.stop_notify(NOTIFY)


asyncio.run(main())
