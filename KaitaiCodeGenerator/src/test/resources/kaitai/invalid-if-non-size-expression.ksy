meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: a
        type: u1
      - id: b
        type: u1
        if: some_field == 1
