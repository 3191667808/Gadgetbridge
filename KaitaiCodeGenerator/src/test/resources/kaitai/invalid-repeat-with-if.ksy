meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: entries
        type: entry
        repeat: eos
        if: _io.size > 0
    types:
      entry:
        seq:
          - id: a
            type: u1
