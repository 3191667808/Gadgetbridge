meta:
  id: duml_wifi
  title: DUML Wi-Fi command set
  endian: le
  -duml-cmdset: 0x07
  -duml-class: Wifi

doc: |
  Commands under cmdSet=WIFI(0x07).

types:
  set_pairing_pin_request:
    -duml: {cmd: 0x45, direction: request, name: SetPairingPin}
    doc: |
      Phone sends a hardcoded app-identifier string plus a PIN; device replies with a status byte
      and a "pairing state" flag (see set_pairing_pin_response). If that flag is 0x01, no further
      pairing stages are needed - otherwise the app waits for a separate PairingPinApproved push
      once a human confirms pairing on the device itself.
    seq:
      - id: id_len
        type: u1
      - id: id
        type: str
        size: id_len
        encoding: UTF-8
      - id: pin_len
        type: u1
      - id: pin
        type: str
        size: pin_len
        encoding: UTF-8

  set_pairing_pin_response:
    -duml: {cmd: 0x45, direction: response, name: SetPairingPin}
    doc: |
      status is usually 0x00. pairing_state is 0x01 if already paired, 0x02 if the user must
      approve the pairing on the device itself.
    seq:
      - id: status
        type: u1
      - id: pairing_state
        type: u1

  pairing_pin_approved:
    -duml: {cmd: 0x46, direction: both, name: PairingPinApproved}
    doc: |
      A single status byte, pushed by the device once a human approves pairing and echoed back
      by the phone as an ack - the same shape travels both directions, so this one type is
      registered under both DumlPacketType keys (see direction: both).
    seq:
      - id: status
        type: u1
