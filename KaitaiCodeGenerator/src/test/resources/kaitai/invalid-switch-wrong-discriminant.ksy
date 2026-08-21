meta:
  id: test_schema
  endian: le
types:
  m:
    seq:
      - id: cmd_type
        type: u1
      - id: other
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
