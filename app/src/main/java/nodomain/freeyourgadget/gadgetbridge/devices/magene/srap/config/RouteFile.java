package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;

public class RouteFile {

    private final String filename;
    private final FileType fileType = FileType.ROUTE; // Should be set from constructor if variable

    private final long timestamp; // u32
    private final String routeName; // char string[], fixed ROUTE_NAME_MAX_BYTES
    private final int duration; // u32, seconds
    private final int routeLength; // u32, meters
    private final int maxGrade; // s32, /100 %
    private final int avgGrade; // s32, /100 %
    private final short avgElevation; // u16, 0xC409
    private final short maxElevation; // u16, 0xC409
    private final short minElevation; // u16, 0xC409
    // padding[2]
    private final int totalAscend; // u32, meters
    private final int unknown4; // u32, 0x00000000
    private final short unknown5; // u16, 0x0C00
    private final short numberOfSegments; // u16
    private final int latitudeEncoded; // u32
    private final int longitudeEncoded; // u32
    private final int unknown6; // u32, 0xffff0000
    private final int minGrade; // s32, /100%
    private final int unknown7; // u32, 0x00000000
    // padding[8]
    private final int dataSize; // u32, size of payloadData
    // padding[16]
    // u64 magic; // 0x02000000 0xa55a55aa

    private final byte[] payloadData;
    private final String routeDescription;

    public static final int ROUTE_NAME_MAX_BYTES = 32; // Corrected to 32

    public static final short UNKNOWN1_DEFAULT = (short) 2500 + 5 * 125; //avg. elev meters * 5 + 2500
    public static final short UNKNOWN2_DEFAULT = (short) 2500 + 5 * 71; // max elevation meters * 5 + 2500
    public static final short UNKNOWN3_DEFAULT = (short) 2500 + 5 * 56; // min elevation meters * 5 + 2500
    public static final int UNKNOWN4_DEFAULT = 0;
    public static final short UNKNOWN5_DEFAULT = (short) 12;
    public static final int UNKNOWN6_DEFAULT = 65535;
    public static final int UNKNOWN7_DEFAULT = 0;
    protected static final byte[] MAGIC_BYTES = new byte[]{0x02, 0x00, 0x00, 0x00, (byte)0xA5, 0x5A, 0x55, (byte)0xAA};

    public static final int MAGIC_SIZE = 8;

    private static final int PADDING_2_BYTES_SIZE = 2;
    private static final int PADDING_8_BYTES_SIZE = 8;
    private static final int PADDING_16_BYTES_SIZE = 16;

    public static final int PADDING_BETWEEN_PAYLOAD_AND_FOOTER_SIZE = 16;
    public static final byte PADDING_BYTE_VALUE = (byte) 0xFF;
    public static final int ROUTE_DESCRIPTION_FIXED_SIZE = 128;


    // Calculate header size carefully: sum of all fields before payloadData
    // timestamp(4) + routeName(ROUTE_NAME_MAX_BYTES) + duration(4) + routeLength(4) + maxGrade(4) + avgGrade(4) +
    // unknown1(2) + unknown2(2) + unknown3(2) + padding(2) + totalAscend(4) + unknown4(4) +
    // unknown5(2) + numberOfSegments(2) + latEncoded(4) + lonEncoded(4) + unknown6(4) +
    // minGrade(4) + unknown7(4) + padding(8) + dataSize(4) + padding(16) + magic(8)
    public static final int FULL_HEADER_SIZE = 4 + ROUTE_NAME_MAX_BYTES + 4 + 4 + 4 + 4 +
            2 + 2 + 2 + PADDING_2_BYTES_SIZE + 4 + 4 +
            2 + 2 + 4 + 4 + 4 +
            4 + 4 + PADDING_8_BYTES_SIZE + 4 + PADDING_16_BYTES_SIZE + MAGIC_SIZE;


    public RouteFile(String filename, long timestamp, String routeName,
                     int duration, int routeLength, int maxGrade, int avgGrade,
                     int totalAscend, short numberOfSegments,
                     int latitudeEncoded, int longitudeEncoded, int minGrade,
                     byte[] payloadData, String routeDescription) throws IOException {
        if (routeName == null) {
            throw new IllegalArgumentException("Route name cannot be null");
        }
        byte[] routeNameBytesUtf8 = routeName.getBytes(StandardCharsets.UTF_8);
        if (routeNameBytesUtf8.length > ROUTE_NAME_MAX_BYTES) { // Corrected to use ROUTE_NAME_MAX_BYTES
            throw new IllegalArgumentException("Route name is too long (max " + ROUTE_NAME_MAX_BYTES + " UTF-8 bytes)");
        }
        if (payloadData == null) {
            throw new IllegalArgumentException("Payload data cannot be null");
        }
        if (routeDescription == null) {
            throw new IllegalArgumentException("Route description cannot be null");
        }

        this.filename = filename;
        // this.fileType = FileType.ROUTE; // Already initialized or pass as param if needed
        this.timestamp = timestamp;
        this.routeName = routeName;
        this.duration = duration;
        this.routeLength = routeLength;
        this.maxGrade = maxGrade;
        this.avgGrade = avgGrade;
        this.avgElevation = UNKNOWN1_DEFAULT;
        this.maxElevation = UNKNOWN2_DEFAULT;
        this.minElevation = UNKNOWN3_DEFAULT;
        this.totalAscend = totalAscend;
        this.unknown4 = UNKNOWN4_DEFAULT;
        this.unknown5 = UNKNOWN5_DEFAULT;
        this.numberOfSegments = numberOfSegments;
        this.latitudeEncoded = latitudeEncoded;
        this.longitudeEncoded = longitudeEncoded;
        this.unknown6 = UNKNOWN6_DEFAULT;
        this.minGrade = minGrade;
        this.unknown7 = UNKNOWN7_DEFAULT;
        this.dataSize = payloadData.length;
        this.payloadData = payloadData;
        this.routeDescription = routeDescription;
    }

    public byte[] serializeToByteArray() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(FULL_HEADER_SIZE + payloadData.length + PADDING_BETWEEN_PAYLOAD_AND_FOOTER_SIZE + 4 +ROUTE_DESCRIPTION_FIXED_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt((int) timestamp);

        byte[] routeNameBytesPadded = new byte[ROUTE_NAME_MAX_BYTES]; // Corrected to use ROUTE_NAME_MAX_BYTES
        byte[] actualRouteNameBytes = routeName.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(actualRouteNameBytes, 0, routeNameBytesPadded, 0, Math.min(actualRouteNameBytes.length, ROUTE_NAME_MAX_BYTES)); // Corrected
        buffer.put(routeNameBytesPadded);

        buffer.putInt(duration);
        buffer.putInt(routeLength);
        buffer.putInt(maxGrade);
        buffer.putInt(avgGrade);
        buffer.putShort(avgElevation);
        buffer.putShort(maxElevation);
        buffer.putShort(minElevation);
        buffer.put(new byte[PADDING_2_BYTES_SIZE]); // padding[2]
        buffer.putInt(totalAscend);
        buffer.putInt(unknown4);
        buffer.putShort(unknown5);
        buffer.putShort(numberOfSegments);
        buffer.putInt(latitudeEncoded);
        buffer.putInt(longitudeEncoded);
        buffer.putInt(unknown6);
        buffer.putInt(minGrade);
        buffer.putInt(unknown7);
        buffer.put(new byte[PADDING_8_BYTES_SIZE]); // padding[8]
        buffer.putInt(payloadData.length + PADDING_BETWEEN_PAYLOAD_AND_FOOTER_SIZE + ROUTE_DESCRIPTION_FIXED_SIZE); // dataSize - actual length of payload
        buffer.put(new byte[PADDING_16_BYTES_SIZE]); // padding[16]
        buffer.put(MAGIC_BYTES);

        buffer.put(payloadData);

        byte[] paddingBytes = new byte[PADDING_BETWEEN_PAYLOAD_AND_FOOTER_SIZE];
        Arrays.fill(paddingBytes, PADDING_BYTE_VALUE);
        buffer.put(paddingBytes);
        buffer.putInt(ROUTE_DESCRIPTION_FIXED_SIZE);

        byte[] actualRouteDescriptionBytes = this.routeDescription.getBytes(StandardCharsets.UTF_8);
        byte[] fixedSizeRouteDescriptionOutput = new byte[ROUTE_DESCRIPTION_FIXED_SIZE]; // auto-filled with 0x00
        System.arraycopy(actualRouteDescriptionBytes, 0, fixedSizeRouteDescriptionOutput, 0, Math.min(actualRouteDescriptionBytes.length, ROUTE_DESCRIPTION_FIXED_SIZE));
        buffer.put(fixedSizeRouteDescriptionOutput);

        return buffer.array();
    }

    public static RouteFile fromBytes(byte[] buffer, String filename) throws IOException {
        if (buffer == null) {
            throw new IllegalArgumentException("Input buffer cannot be null");
        }
        if (buffer.length < FULL_HEADER_SIZE) {
            throw new IOException("Buffer too short for RouteFile header. Min length: " + FULL_HEADER_SIZE + ", got: " + buffer.length);
        }

        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        long timestamp = bb.getInt() & 0xFFFFFFFFL; 

        byte[] routeNameBytesRead = new byte[ROUTE_NAME_MAX_BYTES]; // Corrected to use ROUTE_NAME_MAX_BYTES
        bb.get(routeNameBytesRead);
        int actualNameLength = 0;
        while (actualNameLength < ROUTE_NAME_MAX_BYTES && routeNameBytesRead[actualNameLength] != 0) { // Corrected
            actualNameLength++;
        }
        String routeName = new String(routeNameBytesRead, 0, actualNameLength, StandardCharsets.UTF_8);

        int duration = bb.getInt();
        int routeLength = bb.getInt();
        int maxGrade = bb.getInt();
        int avgGrade = bb.getInt();
        short unknown1 = bb.getShort();
        short unknown2 = bb.getShort();
        short unknown3 = bb.getShort();
        bb.get(new byte[PADDING_2_BYTES_SIZE]); 
        int totalAscend = bb.getInt();
        int unknown4 = bb.getInt();
        short unknown5 = bb.getShort();
        short numberOfSegments = bb.getShort();
        int latitudeEncoded = bb.getInt();
        int longitudeEncoded = bb.getInt();
        int unknown6 = bb.getInt();
        int minGrade = bb.getInt();
        int unknown7 = bb.getInt();
        bb.get(new byte[PADDING_8_BYTES_SIZE]); 
        int parsedDataSize = bb.getInt();
        bb.get(new byte[PADDING_16_BYTES_SIZE]); 
        byte[] magicRead = new byte[MAGIC_SIZE];
        bb.get(magicRead);
        if (!Arrays.equals(MAGIC_BYTES, magicRead)) {
            throw new IOException(String.format("Invalid magic number. Expected %s, got %s", Arrays.toString(MAGIC_BYTES), Arrays.toString(magicRead)));
        }

        if (bb.remaining() < parsedDataSize) {
            throw new IOException("Buffer too short for payload. Expected: " + parsedDataSize + ", remaining: " + bb.remaining());
        }
        byte[] payloadData = new byte[parsedDataSize];
        if (parsedDataSize > 0) {
             bb.get(payloadData);
        }

        if (bb.remaining() < PADDING_BETWEEN_PAYLOAD_AND_FOOTER_SIZE) {
            throw new IOException("Buffer too short for footer padding. Remaining: " + bb.remaining());
        }
        byte[] paddingRead = new byte[PADDING_BETWEEN_PAYLOAD_AND_FOOTER_SIZE];
        bb.get(paddingRead);
        for (byte b : paddingRead) {
            if (b != PADDING_BYTE_VALUE) {
                throw new IOException("Invalid padding byte before footer. Expected 0xFF, got " + String.format("0x%02X", b));
            }
        }

        if (bb.remaining() < ROUTE_DESCRIPTION_FIXED_SIZE) {
            throw new IOException("Buffer too short for fixed-size footer description. Expected: " + ROUTE_DESCRIPTION_FIXED_SIZE + ", remaining: " + bb.remaining());
        }
        byte[] fixedSizeRouteDescriptionInput = new byte[ROUTE_DESCRIPTION_FIXED_SIZE];
        bb.get(fixedSizeRouteDescriptionInput);
        int actualDescriptionLength = 0;
        while (actualDescriptionLength < ROUTE_DESCRIPTION_FIXED_SIZE && fixedSizeRouteDescriptionInput[actualDescriptionLength] != 0) {
            actualDescriptionLength++;
        }
        String parsedRouteDescription = new String(fixedSizeRouteDescriptionInput, 0, actualDescriptionLength, StandardCharsets.UTF_8);


        if (bb.hasRemaining()) {
            throw new IOException("Unexpected trailing data after footer. Remaining: " + bb.remaining());
        }

        return new RouteFile(filename, timestamp, routeName, duration, routeLength,
                maxGrade, avgGrade, totalAscend,
                 numberOfSegments, latitudeEncoded, longitudeEncoded, minGrade,
                payloadData, parsedRouteDescription);
    }

    public String getFilename() { return filename; }
    public FileType getFileType() { return fileType; }
    public long getTimestamp() { return timestamp; }
    public String getRouteName() { return routeName; }
    public int getDuration() { return duration; }
    public int getRouteLength() { return routeLength; }
    public int getMaxGrade() { return maxGrade; }
    public int getAvgGrade() { return avgGrade; }
    public short getAvgElevation() { return avgElevation; }
    public short getMaxElevation() { return maxElevation; }
    public short getMinElevation() { return minElevation; }
    public int getTotalAscend() { return totalAscend; }
    public int getUnknown4() { return unknown4; }
    public short getUnknown5() { return unknown5; }
    public short getNumberOfSegments() { return numberOfSegments; }
    public int getLatitudeEncoded() { return latitudeEncoded; }
    public int getLongitudeEncoded() { return longitudeEncoded; }
    public int getUnknown6() { return unknown6; }
    public int getMinGrade() { return minGrade; }
    public int getUnknown7() { return unknown7; }
    public int getDataSize() { return dataSize; }
    public byte[] getPayloadData() { return payloadData != null ? Arrays.copyOf(payloadData, payloadData.length) : null; }
    public String getRouteDescription() { return routeDescription; }


    @Override
    public String toString() {
        return "RouteFile{" +
                "filename='" + filename +
                ", fileType=" + fileType +
                ", timestamp=" + timestamp +
                ", routeName='" + routeName +
                ", duration=" + duration +
                ", routeLength=" + routeLength +
                ", maxGrade=" + maxGrade +
                ", avgGrade=" + avgGrade +
                ", unknown1=" + String.format("0x%04X", avgElevation) +
                ", unknown2=" + String.format("0x%04X", maxElevation) +
                ", unknown3=" + String.format("0x%04X", minElevation) +
                ", totalAscend=" + totalAscend +
                ", unknown4=" + String.format("0x%08X", unknown4) +
                ", unknown5=" + String.format("0x%04X", unknown5) +
                ", numberOfSegments=" + numberOfSegments +
                ", latitudeEncoded=" + latitudeEncoded +
                ", longitudeEncoded=" + longitudeEncoded +
                ", unknown6=" + String.format("0x%08X", unknown6) +
                ", minGrade=" + minGrade +
                ", unknown7=" + String.format("0x%08X", unknown7) +
                ", dataSize=" + dataSize +
                ", payloadData.length=" + (payloadData != null ? payloadData.length : 0) +
                ", routeDescription='" + routeDescription +
                '}';
    }
}
