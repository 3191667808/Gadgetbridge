package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the NODE_ADDRESS_INFO (0x03) page.
 */
public final class NodeAddressInfoPacket {

    private NodeAddressInfoPacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Represents a READ command for Node Address Info (f1:03).
     */
    public static class Read extends AbstractSRAPMessage {
        private final byte nodeAddress;
        // As per the dissector, this request requires a 1-byte payload with value 0x02.
        private final byte[] payload = new byte[]{0x02};

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
        public PageNumber getPageNumber() { return PageNumber.NODE_ADDRESS_INFO; }

        @Override
        public byte[] getPayload() { return payload; }
    }

    /**
     * Represents a parsed RESPONSE for Node Address Info (f5:03).
     * This class holds the structured data from the response payload.
     */
    public static class Response {
        private final byte nodeType;
        private final byte addressLength;
        private final byte[] nodeAddresses;

        public Response(byte[] payload) {
            if (payload == null || payload.length < 2) {
                throw new IllegalArgumentException("Payload for NodeAddressInfo Response must be at least 2 bytes long.");
            }

            ByteBuffer buffer = ByteBuffer.wrap(payload);

            this.nodeType = buffer.get();
            this.addressLength = buffer.get();

            if (buffer.remaining() < this.addressLength) {
                throw new IllegalArgumentException(String.format("Reported address length (%d) is greater than remaining payload length (%d)", this.addressLength, buffer.remaining()));
            }

            this.nodeAddresses = new byte[this.addressLength];
            buffer.get(this.nodeAddresses);
        }

        // --- Getters ---
        public byte getNodeType() { return nodeType; }
        public byte getAddressLength() { return addressLength; }
        public byte[] getNodeAddresses() { return nodeAddresses; }

        @Override
        public String toString() {
            return "NodeAddressInfoPacket.Response{" +
                    "nodeType=" + String.format("0x%02x", nodeType) +
                    ", addressLength=" + addressLength +
                    ", nodeAddresses=" + Arrays.toString(nodeAddresses) +
                    '}';
        }
    }
}


