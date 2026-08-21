meta:
  id: test_schema
  endian: le
enums:
  color:
    0x00: red
    0x01: green
types:
  m:
    seq:
      - id: a
        type: u1
        enum: color
