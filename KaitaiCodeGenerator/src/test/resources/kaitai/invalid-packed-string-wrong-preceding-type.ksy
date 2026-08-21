meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: name_len
        type: u2
      - id: name
        type: str
        size: name_len
