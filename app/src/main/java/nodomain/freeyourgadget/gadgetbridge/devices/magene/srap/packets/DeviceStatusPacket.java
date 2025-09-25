package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

public final class DeviceStatusPacket {

    private DeviceStatusPacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Represents a READ command for Device Status (f1:f0).
     * This requests the device to report its status.
     * The payload can optionally specify a 'markCode' to request a specific type of status.
     */
    public static class Read extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final ResourceType resourceType = ResourceType.MAIN;
        private final byte[] payload;
        /**
         * Constructs a READ request for a specific device status based on a markCode.
         * @param nodeAddress The address of the node.
         * @param markCode The specific mark code to request status for (e.g., 0x03 for pairing, 0x04 for storage).
         */
        public Read(byte nodeAddress, Byte markCode) {
            this.nodeAddress = nodeAddress;
            if (markCode != null) {
                this.payload = new byte[]{markCode};
            } else {
                this.payload = new byte[0]; // Empty payload for general request
            }
        }

        public Read(byte nodeAddress, byte[] payload) {
            this.nodeAddress = nodeAddress;
            this.payload = payload;
        }

        @Override public byte getNodeAddress() { return nodeAddress; }
        @Override public FunctionCode getFunctionCode() { return FunctionCode.READ; }
        @Override public ResourceType getResourceType() { return resourceType; }
        @Override public PageNumber getPageNumber() { return PageNumber.DEVICE_STATUS; }
        @Override public byte[] getPayload() { return payload; }
    }

    /**
     * Represents a WRITE command for Device Status (f2:f0).
     * This is speculative as the protocol primarily uses 0xF0 for responses.
     * It allows sending a markCode and associated data.
     */
    public static class Write extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final ResourceType resourceType = ResourceType.MAIN;
        private final byte[] payload;

        /**
         * Constructs a WRITE command for device status.
         * @param nodeAddress The address of the node.
         * @param markCode The mark code indicating the type of status to write.
         * @param data The data associated with the mark code.
         */
        public Write(byte nodeAddress, byte markCode, byte[] data) {
            this.nodeAddress = nodeAddress;
            ByteBuffer buffer = ByteBuffer.allocate(1 + (data != null ? data.length : 0)).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(markCode);
            if (data != null) {
                buffer.put(data);
            }
            this.payload = buffer.array();
        }

        @Override public byte getNodeAddress() { return nodeAddress; }
        @Override public FunctionCode getFunctionCode() { return FunctionCode.WRITE; }
        @Override public ResourceType getResourceType() { return resourceType; }
        @Override public PageNumber getPageNumber() { return PageNumber.DEVICE_STATUS; }
        @Override public byte[] getPayload() { return payload; }
    }

    /**
     * Represents a RESPONSE for Device Status (f5:f0).
     * This packet contains various status information from the device.
     * The specific content depends on the 'markCode' byte in the payload.
     */
    public static class Response {
        private final byte markCode;
        private final StorageInfo storageInfo;
        private final PairingChargingRidingStatus pairingChargingRidingStatus;
        private final byte[] rawPayload; // Store raw payload for unknown mark codes or debugging

        public Response(byte[] payload) {
            if (payload == null || payload.length == 0) {
                throw new IllegalArgumentException("Payload for DeviceStatus Response cannot be empty.");
            }

            this.rawPayload = Arrays.copyOf(payload, payload.length);
            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            this.markCode = buffer.get(); // First byte is the markCode

            StorageInfo tempStorageInfo = null;
            PairingChargingRidingStatus tempPairingChargingRidingStatus = null;

            // Dissect based on markCode
            if (this.markCode == (byte) 0x04) { // Storage Info
                if (buffer.remaining() >= 6) { // 4 bytes total, 1 byte low, 1 byte high
                    tempStorageInfo = new StorageInfo(buffer);
                } else {
                    // Malformed payload for storage info
                    System.err.println("Warning: Malformed payload for Storage Info (markCode 0x04). Expected at least 6 bytes, got " + buffer.remaining());
                }
            } else if (this.markCode == (byte) 0x03) { // Pairing, Charging, Riding Status
                if (buffer.remaining() >= 3) { // 1 byte pair/charge, 1 byte riding status, 1 byte riding mode
                    tempPairingChargingRidingStatus = new PairingChargingRidingStatus(buffer);
                } else {
                    // Malformed payload for pairing/charging/riding status
                    System.err.println("Warning: Malformed payload for Pairing/Charging/Riding Status (markCode 0x03). Expected at least 3 bytes, got " + buffer.remaining());
                }
            } else {
                // Unknown markCode, or other types not yet implemented.
                System.err.println("Warning: Unknown DeviceStatus markCode: 0x" + String.format("%02X", this.markCode));
            }

            this.storageInfo = tempStorageInfo;
            this.pairingChargingRidingStatus = tempPairingChargingRidingStatus;
        }

        public byte getMarkCode() {
            return markCode;
        }

        public StorageInfo getStorageInfo() {
            return storageInfo;
        }

        public PairingChargingRidingStatus getPairingChargingRidingStatus() {
            return pairingChargingRidingStatus;
        }

        /**
         * Represents the Storage Info status (markCode 0x04).
         */
        public static class StorageInfo {
            private final long totalStorageSpace; // 4 bytes
            private final int freeSpaceLow;      // 1 byte (integer part of percentage)
            private final int freeSpaceHigh;     // 1 byte (fractional part of percentage)

            public StorageInfo(ByteBuffer buffer) {
                this.totalStorageSpace = buffer.getInt() & 0xFFFFFFFFL; // Convert to unsigned long
                this.freeSpaceLow = buffer.get() & 0xFF;
                this.freeSpaceHigh = buffer.get() & 0xFF;
            }

            public long getTotalStorageSpace() {
                return totalStorageSpace;
            }

            public int getFreeSpaceLow() {
                return freeSpaceLow;
            }

            public int getFreeSpaceHigh() {
                return freeSpaceHigh;
            }

            public double getFreeStorageSpacePercent() {
                return freeSpaceLow + (freeSpaceHigh / 100.0);
            }

            @Override
            public String toString() {
                return "StorageInfo{" +
                        "totalStorageSpace=" + totalStorageSpace +
                        ", freeSpaceLow=" + freeSpaceLow +
                        ", freeSpaceHigh=" + freeSpaceHigh +
                        ", freeStorageSpacePercent=" + String.format("%.2f", getFreeStorageSpacePercent()) + "%" +
                        '}';
            }
        }

        /**
         * Represents the Pairing, Charging, and Riding Status (markCode 0x03).
         */
        public static class PairingChargingRidingStatus {
            private final byte pairAndChargingStatusByte; // 1 byte
            private final byte ridingStatus;              // 1 byte
            private final byte ridingMode;                // 1 byte

            public PairingChargingRidingStatus(ByteBuffer buffer) {
                this.pairAndChargingStatusByte = buffer.get();
                this.ridingStatus = buffer.get();
                this.ridingMode = buffer.get();
            }

            public boolean isPaired() {
                return (pairAndChargingStatusByte & 0x01) != 0;
            }

            public boolean isCharging() {
                return (pairAndChargingStatusByte & 0x02) != 0;
            }

            public byte getRidingStatus() {
                return ridingStatus;
            }

            public byte getRidingMode() {
                return ridingMode;
            }

            @Override
            public String toString() {
                return "PairingChargingRidingStatus{" +
                        "isPaired=" + isPaired() +
                        ", isCharging=" + isCharging() +
                        ", ridingStatus=0x" + String.format("%02X", ridingStatus) +
                        ", ridingMode=0x" + String.format("%02X", ridingMode) +
                        '}';
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("DeviceStatusPacket.Response{");
            sb.append("markCode=0x").append(String.format("%02X", markCode));
            if (storageInfo != null) {
                sb.append(", ").append(storageInfo);
            }
            if (pairingChargingRidingStatus != null) {
                sb.append(", ").append(pairingChargingRidingStatus);
            }
            if (storageInfo == null && pairingChargingRidingStatus == null) {
                sb.append(", rawPayload=").append(bytesToHex(rawPayload));
            }
            sb.append('}');
            return sb.toString();
        }

        // Helper to print byte arrays (can be moved to a common utility if needed)
        private static String bytesToHex(byte[] bytes) {
            if (bytes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}

