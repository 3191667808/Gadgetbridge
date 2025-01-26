package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages;

import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;

public class DeviceAliasMessage extends AbstractStringMessage {

    public DeviceAliasMessage(byte[] payload) {
        super(payload);
    }

    public CardoMap<ByteUtils.CardoField, Object> getDeviceStatus() {
        final CardoMap<ByteUtils.CardoField, Object> temp = new CardoMap<>();
        temp.put(new ByteUtils.CardoField("friendlyName", alias.length()), alias);
        return temp;
    }
}
