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
        types:
          nope:
            seq:
              - id: x
                type: u1
