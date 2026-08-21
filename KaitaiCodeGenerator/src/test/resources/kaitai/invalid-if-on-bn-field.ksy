meta:
  id: t
  endian: le
  bit-endian: le
types:
  m:
    seq:
      - id: a
        type: u1
      - id: b
        type: b4
        if: _io.size > 1
