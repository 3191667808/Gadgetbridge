package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the FILE_TRANSFORM_CONTROL (0x24) page.
 * This is used to initiate and manage file transfers.
 */
public final class FileTransformControlPacket {

    private FileTransformControlPacket() {
        // Namespace class
    }

    public enum FileTransformControlCode {
        START_TRANSFER(0x01),
        END_TRANSFER(0x02),
        //ABORT_TRANSFER(0x03),
        UNKNOWN(-1);

        private final int value;
        private static final Map<Integer, FileTransformControlCode> map = new HashMap<>();

        FileTransformControlCode(int value) {
            this.value = value;
        }

        static {
            for (FileTransformControlCode code : FileTransformControlCode.values()) {
                map.put(code.value, code);
            }
        }

        public static FileTransformControlCode fromValue(int value) {
            return map.getOrDefault(value, UNKNOWN);
        }

        public int getValue() {
            return value;
        }
    }

    public enum FileTransformReadyStatus {
        READY(0x00),
        NOT_READY(0x01),
        UNKNOWN(-1);

        private final int value;
        private static final Map<Integer, FileTransformReadyStatus> map = new HashMap<>();

        FileTransformReadyStatus(int value) {
            this.value = value;
        }

        static {
            for (FileTransformReadyStatus status : FileTransformReadyStatus.values()) {
                map.put(status.value, status);
            }
        }

        public static FileTransformReadyStatus fromValue(int value) {
            return map.getOrDefault(value, UNKNOWN);
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * Represents a WRITE command for File Transform Control (f2:24).
     */
    public static class Write extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final byte[] payload;

        public Write(byte nodeAddress, FileTransformControlCode controlCode, int totalFileCount, int currentFileIndex, int spuVersion, List<String> filenames) {
            this.nodeAddress = nodeAddress;
            this.payload = buildPayload(controlCode, totalFileCount, currentFileIndex, spuVersion, filenames);
        }

        private byte[] buildPayload(FileTransformControlCode controlCode, int totalFileCount, int currentFileIndex, int spuVersion, List<String> filenames) {
            try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                // Fixed part of the payload (17 bytes)
                ByteBuffer fixedBuffer = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN);
                fixedBuffer.put((byte) controlCode.getValue()); // 1 byte
                fixedBuffer.put(new byte[4]); // 4 bytes padding
                fixedBuffer.put((byte) totalFileCount); // 1 byte
                fixedBuffer.put((byte) currentFileIndex); // 1 byte
                fixedBuffer.putInt(spuVersion); // 4 bytes
                fixedBuffer.put(new byte[6]); // 6 bytes padding
                stream.write(fixedBuffer.array());

                /*// Variable part: list of null-terminated filenames
                for (String filename : filenames) {
                    stream.write(filename.getBytes(StandardCharsets.US_ASCII));
                    stream.write(0); // Null terminator for the string
                }
                // The list is terminated by an extra null byte.
                stream.write(0); */

                return stream.toByteArray();
            } catch (IOException e) {
                // Should not happen with ByteArrayOutputStream
                throw new RuntimeException("Failed to build FileTransformControl payload", e);
            }
        }

        @Override public byte getNodeAddress() { return nodeAddress; }
        @Override public FunctionCode getFunctionCode() { return FunctionCode.WRITE; }
        @Override public ResourceType getResourceType() { return ResourceType.MAIN; }
        @Override public PageNumber getPageNumber() { return PageNumber.FILE_TRANSFORM_CONTROL; }
        @Override public byte[] getPayload() { return payload; }
    }

    /**
     * Represents a parsed RESPONSE for File Transform Control (f5:24).
     */
    public static class Response {
        private final FileTransformControlCode controlType;
        private final FileTransformReadyStatus readyStatus;
        private final int executeTime;
        private final int spuVersion;
        private final int mtu;
        private final List<String> filenames;

        public Response(byte[] payload) {
            if (payload == null || payload.length < 17) {
                throw new IllegalArgumentException("Payload for FileTransformControl Response must be at least 17 bytes long.");
            }

            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            this.controlType = FileTransformControlCode.fromValue(buffer.get() & 0xFF);
            this.readyStatus = FileTransformReadyStatus.fromValue(buffer.get() & 0xFF);
            buffer.get(); // 1 byte padding
            this.executeTime = buffer.getShort() & 0xFFFF;
            buffer.getShort(); // 2 bytes padding
            this.spuVersion = buffer.getInt();
            buffer.getInt(); // 4 bytes padding
            this.mtu = buffer.getShort() & 0xFFFF;

            this.filenames = new ArrayList<>();
            if (buffer.hasRemaining()) {
                try {
                    byte[] stringBytes = new byte[buffer.remaining()];
                    buffer.get(stringBytes);

                    ByteArrayInputStream stringStream = new ByteArrayInputStream(stringBytes);
                    while (stringStream.available() > 0) {
                        ByteArrayOutputStream currentString = new ByteArrayOutputStream();
                        int b;
                        while ((b = stringStream.read()) != 0 && b != -1) {
                            currentString.write(b);
                        }

                        if (currentString.size() > 0) {
                            filenames.add(currentString.toString(StandardCharsets.US_ASCII.name()));
                        } else {
                            // An empty string (reading a 0 immediately) marks the end of the list.
                            break;
                        }
                    }
                } catch (UnsupportedEncodingException e) {
                    // Should not happen with US_ASCII
                    throw new RuntimeException(e);
                }
            }
        }

        // Getters
        public FileTransformControlCode getControlType() { return controlType; }
        public FileTransformReadyStatus getReadyStatus() { return readyStatus; }
        public int getExecuteTime() { return executeTime; }
        public int getSpuVersion() { return spuVersion; }
        public int getMtu() { return mtu; }
        public List<String> getFilenames() { return new ArrayList<>(filenames); }

        @Override
        public String toString() {
            return "FileTransformControlPacket.Response{" +
                    "controlType=" + controlType +
                    ", readyStatus=" + readyStatus +
                    ", executeTime=" + executeTime + "ms" +
                    ", spuVersion=" + spuVersion +
                    ", mtu=" + mtu +
                    ", filenames=" + filenames +
                    '}';
        }
    }
}
