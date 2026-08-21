meta:
  id: test_schema
  endian: le
enums:
  color:
    0: red
types:
  m:
    seq:
      - id: mystery
        size: 4
        enum: color
