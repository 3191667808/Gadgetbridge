meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: a
        type: u1
      - id: b
        type: str
        size: 4
        if: _io.size > 1
