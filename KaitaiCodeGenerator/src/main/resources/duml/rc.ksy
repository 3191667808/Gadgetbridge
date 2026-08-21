meta:
  id: duml_rc
  title: DUML Remote Control command set
  endian: le
  -duml-cmdset: 0x06
  -duml-class: Rc

doc: |
  Commands under cmdSet=RC(0x06).

types:
  battery_info_request:
    -duml: {cmd: 0x1e, direction: request, name: BatteryInfo}
    doc: |
      The RC's own battery push, sent roughly once a second.
    seq:
      - id: remaining_capacity
        type: u2
        doc: mAh
      - id: unknown2
        type: u2
        doc: always 0x0000 in every sampled frame; meaning unconfirmed
      - id: percent
        type: u2
        doc: 0-100; only the low byte has ever been observed non-zero
