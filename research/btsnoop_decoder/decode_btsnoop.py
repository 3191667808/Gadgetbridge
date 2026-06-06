"""Parse btsnoop_hci.log and classify YCBT/JieLi-style BLE frame opcodes."""
import struct
import sys
from collections import defaultdict
from pathlib import Path

# Yucheng / YCBT-style vendor characteristics (LE — little-endian 16-byte UUIDs)
BE94_SERVICE = bytes.fromhex("be940000-7333-be46-b7ae-689e71722bd5".replace("-",""))
BE94_WRITE   = "be940001-7333-be46-b7ae-689e71722bd5"
BE94_NOTIFY  = "be940003-7333-be46-b7ae-689e71722bd5"

LOG = Path(sys.argv[1] if len(sys.argv) > 1 else "ble_dumps/extracted/btsnoop_hci.log")

data = LOG.read_bytes()
assert data[:8] == b"btsnoop\x00", "not a btsnoop file"
# header 8 magic + 4 version + 4 datalink = 16
ver, dl = struct.unpack(">II", data[8:16])
print(f"btsnoop v{ver} datalink={dl} size={len(data)}")

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, flags, drops, ts_hi, ts_lo = struct.unpack(">IIIIII", data[pos:pos+24])
    pos += 24
    if pos + incl_len > len(data):
        break
    payload = data[pos:pos+incl_len]
    pos += incl_len
    records.append((flags, payload))

print(f"records: {len(records)}")

# Filter for HCI ACL packets containing candidate characteristic traffic
# btsnoop packet type byte is offset 0 in payload for monitor mode; for h4 it's the first byte.
# Real layout: HCI packet types — 0x01 cmd, 0x02 ACL, 0x04 evt.
# ACL packets carry L2CAP -> ATT.  We'll just hex-search for BE94 service or short forms.

write_handle = None
notify_handle = None

# Quick approach: substring scan for short UUID 0x0001/0x0003 prefix
# Easier: look for ATT writes with characteristics matching our magic prefixes
# (e.g. payload contains b"GC", b"GP", b"GF", b"IS" prefix at known position)

interesting = []
opcodes_seen = defaultdict(int)
unsolicited = []  # responses we haven't classified
sent = []

for i, (flags, p) in enumerate(records):
    direction_in = (flags & 1) == 1
    # Heuristic: ATT MTU is 23 by default, our frames are 6-25 bytes; payload position
    # within ACL is roughly bytes 8+ (skip L2CAP header).  We'll scan for our specific
    # YCBT frame signatures (group/key bytes 0x02xx / 0x05xx / 0x06xx / 0x03xx etc).

    if len(p) < 12 or len(p) > 60:
        continue
    # Look at last 20 bytes for our framing
    for off in range(8, min(len(p), 16)):
        if off + 6 > len(p):
            break
        grp, key, lenlo, lenhi = p[off], p[off+1], p[off+2], p[off+3]
        if grp not in (0x01, 0x02, 0x03, 0x04, 0x05, 0x06):
            continue
        total = lenlo | (lenhi << 8)
        if not (6 <= total <= 200):
            continue
        if off + total > len(p):
            continue
        opcode = (grp << 8) | key
        payload_body = p[off+4 : off+total-2]
        opcodes_seen[(opcode, "in" if direction_in else "out")] += 1
        rec = (opcode, "in" if direction_in else "out", payload_body.hex())
        interesting.append(rec)
        break

# Summary
print("\n=== Opcodes observed (Yucheng frame format) ===")
for (opc, dirn), n in sorted(opcodes_seen.items(), key=lambda x: (-x[1], x[0][0])):
    print(f"  0x{opc:04X} {dirn:3}  count={n}")

# Save outgoing frames (what the app SENDS) — these are what we want to learn from
outs = [(o, p) for o, d, p in interesting if d == "out"]
print(f"\n=== Outgoing app -> ring ({len(outs)} frames) ===")
seen = set()
for opc, pl in outs:
    key = (opc, pl[:16])
    if key in seen: continue
    seen.add(key)
    print(f"  0x{opc:04X}  payload={pl[:48]}")

# Print unique incoming payloads grouped by opcode
ins = defaultdict(list)
for o, d, p in interesting:
    if d == "in":
        ins[o].append(p)
print(f"\n=== Incoming ring -> app (unique payloads per opcode) ===")
for opc in sorted(ins):
    uniq = set(ins[opc])
    print(f"  0x{opc:04X}  {len(ins[opc])} frames, {len(uniq)} unique")
    for p in list(uniq)[:3]:
        print(f"        payload={p[:80]}")

# Focus on NEW interesting opcodes — what we don't yet handle
NEW_OPCODES = [0x0600, 0x0226, 0x051A, 0x0540, 0x0541, 0x0542, 0x0543, 0x0544,
               0x021B, 0x040C, 0x030C, 0x033D, 0x0105, 0x0109]

print("\n=== DEEP DIVE: new/interesting opcodes ===")
for opc in NEW_OPCODES:
    out_pls = [p for o, d, p in interesting if o == opc and d == "out"]
    in_pls  = [p for o, d, p in interesting if o == opc and d == "in"]
    if not (out_pls or in_pls):
        continue
    print(f"\n  0x{opc:04X}:")
    if out_pls:
        for p in set(out_pls):
            print(f"    -> {p}")
    if in_pls:
        for p in set(in_pls):
            print(f"    <- {p}")
