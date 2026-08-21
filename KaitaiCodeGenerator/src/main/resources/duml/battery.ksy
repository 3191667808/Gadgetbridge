meta:
  id: duml_battery
  title: DUML Battery command set
  endian: le
  -duml-cmdset: 0x0d
  -duml-class: Battery

doc: |
  Commands under cmdSet=BATTERY(0x0d).

types:
  battery_dynamic_data_request:
    -duml: {cmd: 0x02, direction: request, name: BatteryDynamicData}
    doc: |
      The aircraft's own battery push, sent roughly once a second by module type Battery.
    seq:
      - id: unparsed_prefix
        size: 20
        doc: |
          Unconfirmed structure.
      - id: percent
        type: u1
        doc: 0-100
