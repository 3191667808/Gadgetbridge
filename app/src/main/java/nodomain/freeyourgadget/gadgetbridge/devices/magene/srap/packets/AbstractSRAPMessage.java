package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;

/**
 * An abstract base class for Magene commands, providing a default implementation for serialization.
 */
public abstract class AbstractSRAPMessage implements SRAPMessage {
    public static final int HEADER_LENGTH = 4;

    @Override
    public byte[] toByteArray() {
        byte[] payload = getPayload();
        int payloadLength = (payload != null) ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + payloadLength);

        // The first byte is the node address with the Most Significant Bit (MSB) set to 1.
        buffer.put((byte) (getNodeAddress() | 0x80));
        buffer.put((byte) getFunctionCode().getValue());
        buffer.put((byte) getResourceType().getValue());
        buffer.put((byte) getPageNumber().getValue());

        if (payloadLength > 0) {
            buffer.put(payload);
        }

        return buffer.array();
    }
}


