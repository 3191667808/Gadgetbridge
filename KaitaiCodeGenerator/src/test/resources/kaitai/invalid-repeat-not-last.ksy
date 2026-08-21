meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: entries
        type: entry
        repeat: eos
      - id: trailing
        type: u1
    types:
      entry:
        seq:
          - id: a
            type: u1
