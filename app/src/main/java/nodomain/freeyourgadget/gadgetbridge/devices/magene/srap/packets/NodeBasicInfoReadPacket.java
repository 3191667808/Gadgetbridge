package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the NODE_BASIC_INFO (0x01) page.
 */
public final class NodeBasicInfoReadPacket {

    private NodeBasicInfoReadPacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Represents a READ command for Node Basic Info (f1:01).
     */
    public static class Read extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final byte[] payload = new byte[9]; // As per the dissector, this request requires a 9-byte padding payload.

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
        public PageNumber getPageNumber() { return PageNumber.NODE_BASIC_INFO; }

        @Override
        public byte[] getPayload() { return payload; }
    }

    /**
     * Represents a parsed RESPONSE for Node Basic Info (f5:01).
     * This class holds the structured data from the response payload.
     */
    public static class Response {
        private final boolean inBootloader;
        private final byte nodeAddress;
        private final int nodeId;
        private final int nodeManufacturer;
        private final int nodeConfigCode;
        private final int nodeHwVersion;
        private final double nodeFwVersion;

        public Response(byte[] payload) {
            if (payload == null || payload.length < 9) {
                throw new IllegalArgumentException("Payload for NodeBasicInfo Response must be at least 9 bytes long.");
            }

            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            byte addressByte = buffer.get();
            this.inBootloader = (addressByte & 0x80) != 0;
            this.nodeAddress = (byte) (addressByte & 0x7F);

            this.nodeId = buffer.getShort() & 0xFFFF;
            this.nodeManufacturer = buffer.getShort() & 0xFFFF;
            this.nodeConfigCode = buffer.get() & 0xFF;
            this.nodeHwVersion = buffer.get() & 0xFF;

            int fwSecondary = buffer.get() & 0xFF;
            int fwPrimary = buffer.get() & 0xFF;
            this.nodeFwVersion = (fwPrimary * 100 + fwSecondary) / 1000.0;
        }

        // --- Getters ---
        public boolean isInBootloader() { return inBootloader; }
        public byte getNodeAddress() { return nodeAddress; }
        public int getNodeId() { return nodeId; }
        public int getNodeManufacturer() { return nodeManufacturer; }
        public int getNodeConfigCode() { return nodeConfigCode; }
        public int getNodeHwVersion() { return nodeHwVersion; }
        public double getNodeFwVersion() { return nodeFwVersion; }

        @Override
        public String toString() {
            return "NodeBasicInfoPacket.Response{" +
                    "inBootloader=" + inBootloader +
                    ", nodeAddress=" + nodeAddress +
                    ", nodeId=0x" + Integer.toHexString(nodeId) +
                    ", nodeManufacturer=" + nodeManufacturer +
                    ", nodeConfigCode=0x" + Integer.toHexString(nodeConfigCode) +
                    ", nodeHwVersion=" + nodeHwVersion +
                    ", nodeFwVersion=" + nodeFwVersion +
                    '}';
        }
    }
}


