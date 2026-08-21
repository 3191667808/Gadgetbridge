meta:
  id: duml_general
  title: DUML General command set
  endian: le
  -duml-cmdset: 0x00
  -duml-class: General

doc: |
  Commands under cmdSet=GENERAL(0x00).

types:
  set_time_request:
    -duml: {cmd: 0x4a, direction: request, name: SetTime}
    doc: |
      Set RTC time
    seq:
      - id: year
        type: u2
      - id: month
        type: u1
        doc: 1-12
      - id: day
        type: u1
        doc: day of month, 1-31
      - id: hour
        type: u1
        doc: 0-23
      - id: minute
        type: u1
      - id: second
        type: u1
