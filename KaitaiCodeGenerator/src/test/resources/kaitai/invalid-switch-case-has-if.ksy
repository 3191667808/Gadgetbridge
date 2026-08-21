meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: cmd_type
        type: u1
      - id: body
        type:
          switch-on: cmd_type
          cases:
            3: config_body
    types:
      config_body:
        seq:
          - id: oper_type
            type: u1
          - id: extra
            type: u1
            if: _io.size > 1
