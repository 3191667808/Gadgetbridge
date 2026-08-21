meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: entries
        type: entry
        repeat: until_something
    types:
      entry:
        seq:
          - id: a
            type: u1
