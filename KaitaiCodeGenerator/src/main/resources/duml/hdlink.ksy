meta:
  id: duml_hdlink
  title: DUML HD Link command set
  endian: le
  -duml-cmdset: 0x09
  -duml-class: HdLink

doc: |
  Commands under cmdSet=HD_LINK(0x09).

types:
  hd_link_state_request:
    -duml: {cmd: 0x43, direction: request, name: HdLinkState}
    doc: |
      RC downlink-radio status push, sent by the RC's own downlink chip
      (module type 14, HdLinkGround) roughly every 500ms.
    seq:
      - id: state
        type: u1
        doc: 0x00 when the aircraft is linked and video is flowing, 0x02 otherwise
