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
          - id: name_len
            type: u1
          - id: name
            type: str
            size: name_len
            encoding: UTF-8
