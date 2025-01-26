package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio;

import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.ControlRequest;

public class FmRadioToggleRequest extends ControlRequest {
    public FmRadioToggleRequest(boolean on) {
        super(ControlSubset.FM, new byte[]{(byte) (on ? 0x00 : 0x01), 0x00});
    }
}
