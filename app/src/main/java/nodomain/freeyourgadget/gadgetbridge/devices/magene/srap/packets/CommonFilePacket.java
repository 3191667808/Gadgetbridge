package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the COMMON_FILE (0x26) page.
 * This is used to send a chunk of a file during a file transfer.
 */
public final class CommonFilePacket {

    private CommonFilePacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Represents a WRITE command for a Common File chunk (f2:26).
     */
    public static class Write extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final byte[] payload;

        /**
         * @param nodeAddress  The target node address.
         * @param resourceType The target resource type.
         * @param chunkIndex   The 0-based index of this file chunk.
         * @param chunkData    The raw byte data of the file chunk.
         */
        public Write(byte nodeAddress, int chunkIndex, byte[] chunkData) {
            this.nodeAddress = nodeAddress;
            this.payload = buildPayload(chunkIndex, chunkData);
        }

        private byte[] buildPayload(int chunkIndex, byte[] chunkData) {
            if (chunkData == null || chunkData.length == 0) {
                throw new IllegalArgumentException("Chunk data cannot be null or empty.");
            }
            if (chunkIndex < 0 || chunkIndex > 65535) {
                throw new IllegalArgumentException("Chunk index must be between 0 and 65535.");
            }
            if (chunkData.length > 65535) {
                throw new IllegalArgumentException("Chunk data length cannot exceed 65535 bytes.");
            }

            ByteBuffer buffer = ByteBuffer.allocate(4 + chunkData.length).order(ByteOrder.LITTLE_ENDIAN);

            buffer.putShort((short) chunkIndex);
            buffer.putShort((short) chunkData.length);
            buffer.put(chunkData);

            return buffer.array();
        }

        @Override public byte getNodeAddress() { return nodeAddress; }
        @Override public FunctionCode getFunctionCode() { return FunctionCode.WRITE; }
        @Override public ResourceType getResourceType() { return ResourceType.MAIN; }
        @Override public PageNumber getPageNumber() { return PageNumber.COMMON_FILE; }
        @Override public byte[] getPayload() { return payload; }
    }
}


