package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages;

import java.nio.charset.StandardCharsets;

public class AbstractStringMessage {
    final String alias;

    public AbstractStringMessage(byte[] payload) {
        this.alias = new String(payload, StandardCharsets.UTF_8);
    }

}
