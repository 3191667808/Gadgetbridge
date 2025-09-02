package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the NODE_SERIAL_INFO (0x02) page.
 */
public final class NodeSerialInfoPacket {

    private NodeSerialInfoPacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Represents a READ command for Node Serial Info (f1:02).
     */
    public static class Read extends AbstractSRAPMessage {
        private final byte nodeAddress;
        // As per the dissector, this request requires a 23-byte padding payload.
        private final byte[] payload = new byte[23];

        public Read(byte nodeAddress) {
            this.nodeAddress = nodeAddress;
        }

        @Override
        public byte getNodeAddress() { return nodeAddress; }

        @Override
        public FunctionCode getFunctionCode() { return FunctionCode.READ; }

        @Override
        public ResourceType getResourceType() { return ResourceType.MAIN; }

        @Override
        public PageNumber getPageNumber() { return PageNumber.NODE_SERIAL_INFO; }

        @Override
        public byte[] getPayload() { return payload; }
    }

    /**
     * Represents a parsed RESPONSE for Node Serial Info (f5:02).
     * This class holds the structured data from the response payload.
     */
    public static class Response {
        private final int serialNumber;
        private final String serialNumberString;

        public Response(byte[] payload) {
            if (payload == null || payload.length < 4) {
                throw new IllegalArgumentException("Payload for NodeSerialInfo Response must be at least 4 bytes long.");
            }

            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            this.serialNumber = buffer.getInt();

            if (buffer.hasRemaining()) {
                byte[] strBytes = new byte[buffer.remaining()];
                buffer.get(strBytes);
                // The string might be null-terminated, so trim any whitespace or null chars.
                this.serialNumberString = new String(strBytes, StandardCharsets.US_ASCII).trim();
            } else {
                this.serialNumberString = "";
            }
        }

        // --- Getters ---
        public int getSerialNumber() { return serialNumber; }
        public String getSerialNumberString() { return serialNumberString; }

        @Override
        public String toString() {
            return "NodeSerialInfoPacket.Response{" +
                    "serialNumber=" + serialNumber +
                    ", serialNumberString='" + serialNumberString + '\'' +
                    '}';
        }
    }
}

