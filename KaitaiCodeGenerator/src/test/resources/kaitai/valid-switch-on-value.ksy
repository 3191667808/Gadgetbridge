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
            4: encrypt_body
    types:
      config_body:
        seq:
          - id: oper_type
            type: u1
          - id: mod_type
            type: u1
      encrypt_body:
        seq:
          - id: mod_type
            type: u1
          - id: buf_data
            type: str
            size: 4
            encoding: UTF-8
