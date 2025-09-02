package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the COMMON_FILE_INFO (0x25) page.
 * This is used to initiate a file transfer by sending metadata about the file.
 */
public final class CommonFileInfoPacket {

    private CommonFileInfoPacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Represents a WRITE command for Common File Info (f2:25).
     */
    public static class Write extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final byte[] payload;

        /**
         * @param nodeAddress     The target node address.
         * @param fileType        The type of file being sent.
         * @param transformType   The type of transfer (e.g., 0 for upload).
         * @param fileSize        Total size of the file in bytes.
         * @param packageNumber   Total number of chunks/packages for the file.
         * @param crc             CRC16 of the file.
         * @param filename        The name of the file.
         */
        public Write(byte nodeAddress, FileType fileType, byte transformType, int fileSize, int packageNumber, int crc, String filename) {
            this.nodeAddress = nodeAddress;
            this.payload = buildPayload(fileType, transformType, fileSize, packageNumber, crc, filename);
        }

        private byte[] buildPayload(FileType fileType, byte transformType, int fileSize, int packageNumber, int crc, String filename) {
            byte[] filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
            int payloadSize = 19 + filenameBytes.length;

            ByteBuffer buffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN);

            buffer.put((byte) fileType.getValue());
            buffer.put(transformType);
            buffer.put(new byte[5]); // 5 bytes of padding
            buffer.putInt(fileSize);
            buffer.putShort((short) packageNumber);
            buffer.putShort((short) crc);
            buffer.put(new byte[4]); // 4 bytes of padding
            buffer.put(filenameBytes);

            return buffer.array();
        }

        @Override public byte getNodeAddress() { return nodeAddress; }
        @Override public FunctionCode getFunctionCode() { return FunctionCode.WRITE; }
        @Override public ResourceType getResourceType() { return ResourceType.MAIN; }
        @Override public PageNumber getPageNumber() { return PageNumber.COMMON_FILE_INFO; }
        @Override public byte[] getPayload() { return payload; }
    }

    public static class Response {
        private final FileType fileType;
        private final byte transformType;
        private final int fileSize;
        private final int packageNumber;
        private final int crc;
        private final String filename;

        public Response(byte[] payload) {
            if (payload == null || payload.length < 19) {
                throw new IllegalArgumentException("Payload for CommonFileInfo Response must be at least 19 bytes long.");
            }

            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            this.fileType = FileType.fromValue(buffer.get() & 0xFF);
            this.transformType = buffer.get();
            buffer.get(new byte[5]); // Skip 5 bytes of padding
            this.fileSize = buffer.getInt();
            this.packageNumber = buffer.getShort() & 0xFFFF;
            this.crc = buffer.getShort() & 0xFFFF;
            buffer.get(new byte[4]); // Skip 4 bytes of padding

            byte[] filenameBytes = new byte[buffer.remaining()];
            buffer.get(filenameBytes);
            this.filename = new String(filenameBytes, StandardCharsets.UTF_8);
        }

        // Getters
        public FileType getFileType() { return fileType; }
        public byte getTransformType() { return transformType; }
        public int getFileSize() { return fileSize; }
        public int getPackageNumber() { return packageNumber; }
        public int getCrc() { return crc; }
        public String getFilename() { return filename; }

        @Override
        public String toString() {
            return "CommonFileInfoPacket.Response{" +
                    "fileType=" + fileType +
                    ", transformType=" + transformType +
                    ", fileSize=" + fileSize +
                    ", packageNumber=" + packageNumber +
                    ", crc=0x" + Integer.toHexString(crc) +
                    ", filename='" + filename + '\'' +
                    '}';
        }
    }
}


