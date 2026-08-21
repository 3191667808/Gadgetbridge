meta:
  id: simple
  endian: le
  bit-endian: le
enums:
  color:
    0x00: red
    0x01: green
types:
  simple_msg:
    doc: A tiny schema exercising every field shape the emitter supports - scaled scalar, enum bitfield, bool bitfield, optional tail.
    seq:
      - id: value
        type: s2
        -scale: 0.1
      - id: shade
        type: b7
        enum: color
      - id: active
        type: b1
      - id: extra
        type: u1
        if: _io.size > 3
