package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests;

import org.bouncycastle.shaded.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.CardoMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.CardoRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.ConfigMessage;

public class SetRequest extends CardoRequest {
    public SetRequest(ConfigMessage.InfoType infoType, byte[] payload) {
        super(CardoMessage.SET, Arrays.concatenate(new byte[]{(byte) infoType.getTypeId()}, payload));
    }
}
