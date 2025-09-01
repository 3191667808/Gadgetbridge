package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Represents a single Magene BLE protocol packet.
 * This class handles the 4-byte header and the payload.
 */
public class SRAPPacket {
    public static final int HEADER_LENGTH = 4;

    private final byte nodeAddress;
    private final FunctionCode functionCode;
    private final ResourceType resourceType;
    private final PageNumber pageNumber;
    private final byte[] payload;

    public SRAPPacket(byte nodeAddress, FunctionCode functionCode, ResourceType resourceType, PageNumber pageNumber, byte[] payload) {
        this.nodeAddress = (byte) (nodeAddress & 0x7F); // Ensure node address is within 7 bits
        this.functionCode = functionCode;
        this.resourceType = resourceType;
        this.pageNumber = pageNumber;
        this.payload = payload;
    }

    public byte getNodeAddress() {
        return nodeAddress;
    }

    public FunctionCode getFunctionCode() {
        return functionCode;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public PageNumber getPageNumber() {
        return pageNumber;
    }

    public byte[] getPayload() {
        return payload;
    }

    /**
     * Serializes the entire packet into a byte array for sending over BLE.
     *
     * @return The byte array representation of the packet.
     */
    public byte[] toByteArray() {
        int payloadLength = (payload != null) ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + payloadLength);

        // The first byte is the node address with the Most Significant Bit (MSB) set to 1.
        buffer.put((byte) (nodeAddress | 0x80));
        buffer.put((byte) functionCode.getValue());
        buffer.put((byte) resourceType.getValue());
        buffer.put((byte) pageNumber.getValue());

        if (payloadLength > 0) {
            buffer.put(payload);
        }

        return buffer.array();
    }

    /**
     * Parses a raw byte array from a BLE characteristic into a MagenePacket object.
     *
     * @param data The raw byte array received.
     * @return A MagenePacket object, or null if parsing fails.
     */
    public static SRAPPacket fromBytes(byte[] data) {
        if (data == null || data.length < HEADER_LENGTH) {
            return null; // Packet is too short
        }

        // The MSB of the first byte must be 1
        if ((data[0] & 0x80) == 0) {
            return null; // Not a valid Magene packet
        }

        byte nodeAddress = (byte) (data[0] & 0x7F);
        FunctionCode functionCode = FunctionCode.fromValue(data[1] & 0xFF);
        ResourceType resourceType = ResourceType.fromValue(data[2] & 0xFF);
        PageNumber pageNumber = PageNumber.fromValue(data[3] & 0xFF);

        byte[] payload = Arrays.copyOfRange(data, HEADER_LENGTH, data.length);

        return new SRAPPacket(nodeAddress, functionCode, resourceType, pageNumber, payload);
    }
}

