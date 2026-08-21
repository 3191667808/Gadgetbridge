meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: name_len
        type: u1
      - id: name
        type: str
        size: name_len
        encoding: UTF-8
      - id: extra
        type: u1
        if: _io.size > 1
