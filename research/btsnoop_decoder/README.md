# BLE HCI btsnoop decoder

This directory contains small, dependency-free Python helpers for reverse-engineering BLE traffic from Android `btsnoop_hci.log` captures. They were originally used while adding Gadgetbridge support for the R20 ring (#6239) and the upcoming A10 Pro / G2-ADV driver, and are useful for future Yucheng/JieLi-family devices.

## Capturing a btsnoop log from Android

1. On the Android phone, enable Developer options.
2. In Developer options, enable **Bluetooth HCI snoop log**.
3. Reproduce the pairing, sync, measurement, or notification interaction you want to inspect with the vendor companion app.
4. Collect a bugreport:

   ```sh
   adb bugreport
   ```

5. Unzip the generated bugreport archive and locate the snoop file, commonly at:

   ```text
   FS/data/log/bt/btsnoop_hci.log
   ```

The exact path can vary by Android version and vendor build; search the extracted bugreport for `btsnoop_hci.log` if needed.

## Running the decoders

Both scripts use only the Python standard library.

```sh
python research/btsnoop_decoder/decode_btsnoop.py path/to/btsnoop_hci.log
python research/btsnoop_decoder/decode_btsnoop_timeline.py path/to/btsnoop_hci.log
```

If no path is passed, the scripts default to `ble_dumps/extracted/btsnoop_hci.log`.

## Output

`decode_btsnoop.py` provides a broad opcode-oriented view:

- btsnoop header and record count
- opcode frequency grouped by direction
- unique outgoing app-to-device frames
- unique incoming device-to-app payloads
- deep dives for opcodes that were useful while reversing YCBT-style protocols

`decode_btsnoop_timeline.py` provides a chronological measurement-oriented view:

- command sequences around measurement triggers such as `0x032F`
- real-time push payloads such as heart rate, SpO2, blood pressure, and snapshot frames
- auxiliary opcode exchanges used to infer frame layouts and command semantics

The heuristics look for compact BLE ATT payloads that use FB-prefix/YCBT-style framing or similar group/key/length/checksum layouts. The output is intended as research input: correlate it with companion-app actions, device state, and Gadgetbridge protocol code before turning observations into production support.

## Device families

This tooling is useful for devices and SDK families that share YCBT/JieLi/FreeFit/Bluetrum-style BLE framing, including:

- YCBT-SDK watches, bands, and rings
- JieLi BT-Watch based devices
- FreeFit iEnjoy V2 / G2-ADV style devices
- Bluetrum-derived devices with similar FB-prefix or group/key framing

If you are adding support for a new YCBT/JieLi-family device, capture a vendor-app btsnoop log and run these decoders early. The resulting opcode frequency, measurement sequences, and push-frame layouts can reveal command ordering and payload fields much faster than manual packet inspection alone.

## Related Gadgetbridge work

- #6239: R20 ring support benefited from these decoders while identifying health and measurement traffic.
- Upcoming A10 Pro / G2-ADV support used the same workflow to inspect measurement sequences and push-frame layouts.
