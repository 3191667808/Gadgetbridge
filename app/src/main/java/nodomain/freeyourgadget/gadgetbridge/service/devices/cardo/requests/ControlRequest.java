package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests;

import org.bouncycastle.shaded.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.CardoMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.CardoRequest;

public class ControlRequest extends CardoRequest {
    public ControlRequest(ControlSubset subset, byte[] payload) {
        super(CardoMessage.CONTROL, Arrays.concatenate(new byte[]{(byte) subset.getSubsetId()}, payload));
    }

    public enum ControlSubset {
        FM(0x20),
        ;

        private final int subsetId;

        ControlSubset(int subsetId) {
            this.subsetId = subsetId;
        }

        public int getSubsetId() {
            return subsetId;
        }
    }
}
