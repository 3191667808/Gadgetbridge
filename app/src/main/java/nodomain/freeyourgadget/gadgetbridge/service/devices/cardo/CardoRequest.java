package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class CardoRequest {
    private static final Logger LOG = LoggerFactory.getLogger(CardoRequest.class);

    private final CardoMessage message;
    private final ByteBuffer bb;


    public CardoRequest(CardoMessage message) {
        this(message, null);
    }

    public CardoRequest(CardoMessage message, byte[] payload) {
        this.message = message;
        this.bb = ByteBuffer.allocate(255);
        if (payload != null) {
            this.bb.put(payload);
        }
    }

    public void appendPayload(byte[] payload) {
        this.bb.put(payload);
    }

    public void appendPayload(Number payload, int size) {
        switch (size) {
            case 1:
                this.bb.put(payload.byteValue());
                break;
            case 2:
                this.bb.putShort(payload.shortValue());
                break;
            case 4:
                this.bb.putInt(payload.intValue());
                break;
        }
    }

    public byte[] getBtMessage() {
        bb.flip();
        final int len = 1 + (this.message.hasLength ? 1 : 0) + bb.limit();
        final byte[] btMessage = new byte[len];
        btMessage[0] = this.message.command;
        if (this.message.hasLength)
            btMessage[1] = (byte) bb.remaining();
        bb.get(btMessage, (this.message.hasLength ? 2 : 1), bb.remaining());
        return btMessage;
    }
}
