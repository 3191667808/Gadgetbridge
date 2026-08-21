meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: entries
        type: entry
        repeat: eos
    types:
      entry:
        seq:
          - id: a
            type: u1
          - id: b
            type: u1
            if: _io.size > 1
