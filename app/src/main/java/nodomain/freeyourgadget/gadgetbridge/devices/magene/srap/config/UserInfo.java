package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;

/**
 * Represents the structure of the UserInfo binary format.
 * This class provides methods to read and write the data according to the
 * specified C-style struct definition.
 */
public class UserInfo extends ConfigFile {
    // Fixed-size strings are handled as byte arrays for precise memory layout.
    private static final int NAME_SIZE = 32;
    // This is the size of the UserInfo specific data including its padding
    private static final int USER_INFO_PAYLOAD_SIZE = 104;
    private static final String filename = "user_info";
    private static final FileType type = FileType.USER_PROFILE;

    private final String userName;
    private final String deviceName;
    private final int mhrBpm;
    private final int lthrBpm;
    private final int ftp;
    private final int year;
    private final int month;
    private final int day;
    private final int gender;
    private final int height;
    private final int weight;

    /**
     * Private constructor to create a new instance of UserInfo.
     * Use the static `fromBytes` method to create an instance from a byte array,
     * or use this constructor to create a new one from scratch.
     */
    public UserInfo(String userName, String deviceName, int mhrBpm, int lthrBpm, int ftp, int year, int month, int day, int gender, int height, int weight) {

        super(filename, type);
        if (userName.length() > NAME_SIZE || deviceName.length() > NAME_SIZE) {
            throw new IllegalArgumentException("User or device name exceeds the maximum length of " + NAME_SIZE + " characters.");
        }
        this.userName = userName;
        this.deviceName = deviceName;
        this.mhrBpm = mhrBpm;
        this.lthrBpm = lthrBpm;
        this.ftp = ftp;
        this.year = year;
        this.month = month;
        this.day = day;
        this.gender = gender;
        this.height = height;
        this.weight = weight;

    }

    // --- Getters for all fields ---

    public String getUserName() { return userName; }
    public String getDeviceName() { return deviceName; }
    public int getMhrBpm() { return mhrBpm; }
    public int getLthrBpm() { return lthrBpm; }
    public int getFtp() { return ftp; }
    public int getYear() { return year; }
    public int getMonth() { return month; }
    public int getDay() { return day; }
    public int getGender() { return gender; }
    public int getHeight() { return height; }
    public int getWeight() { return weight; }


    @Override
    public int getSpecificDataPayloadSize() {
        return USER_INFO_PAYLOAD_SIZE;
    }

    @Override
    protected void writeSpecificDataPayload(ByteBuffer byteBuffer) throws IOException {
        // Write user and device names, padding with null bytes if necessary.
        byte[] userNameBytes = userName.getBytes(StandardCharsets.UTF_8);
        byteBuffer.put(Arrays.copyOf(userNameBytes, NAME_SIZE));

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        byteBuffer.put(Arrays.copyOf(deviceNameBytes, NAME_SIZE));

        // Write numerical fields
        byteBuffer.putShort((short) mhrBpm);
        byteBuffer.putShort((short) lthrBpm);
        byteBuffer.putShort((short) ftp);
        byteBuffer.putShort((short) year);

        byteBuffer.put((byte) month);
        byteBuffer.put((byte) day);
        byteBuffer.put((byte) gender);
        byteBuffer.put((byte) height);

        byteBuffer.putShort((short) weight);

        // Write 26 bytes of padding (zeros).
        byteBuffer.put(new byte[26]);
    }

    /**
     * Reads the UserInfo structure from a byte array.
     * Assumes the data is in Little-Endian byte order and includes magic bytes at the end.
     *
     * @param buffer The byte array to read from.
     * @return A new UserInfo instance.
     * @throws IOException If an I/O error occurs or data is invalid (e.g., magic bytes mismatch).
     */
    public static UserInfo fromBytes(byte[] buffer) throws IOException {
        if (buffer.length != USER_INFO_PAYLOAD_SIZE + ConfigFile.MAGIC_SIZE) {
            throw new IOException("Invalid buffer size. Expected " +
                                  (USER_INFO_PAYLOAD_SIZE + ConfigFile.MAGIC_SIZE) +
                                  ", got " + buffer.length);
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

        // Read fixed-size strings as byte arrays and convert to String, trimming null bytes.
        byte[] userNameBytes = new byte[NAME_SIZE];
        byteBuffer.get(userNameBytes);
        String userName = new String(userNameBytes, StandardCharsets.UTF_8).trim().replace("\000", "");

        byte[] deviceNameBytes = new byte[NAME_SIZE];
        byteBuffer.get(deviceNameBytes);
        String deviceName = new String(deviceNameBytes, StandardCharsets.UTF_8).trim().replace("\000", "");

        // Read unsigned 16-bit integers (u16) into Java's signed `int` to avoid overflow.
        int mhrBpm = byteBuffer.getShort() & 0xFFFF;
        int lthrBpm = byteBuffer.getShort() & 0xFFFF;
        int ftp = byteBuffer.getShort() & 0xFFFF;
        int year = byteBuffer.getShort() & 0xFFFF;

        // Read unsigned 8-bit integers (u8) into Java's signed `int` to avoid overflow.
        int month = byteBuffer.get() & 0xFF;
        int day = byteBuffer.get() & 0xFF;
        int gender = byteBuffer.get() & 0xFF;
        int height = byteBuffer.get() & 0xFF;

        // Read weight (u16)
        int weight = byteBuffer.getShort() & 0xFFFF;

        // Skip the 26 bytes of padding.
        byteBuffer.position(byteBuffer.position() + 26);

        // At this point, byteBuffer should be positioned right before the magic bytes.
        ConfigFile.readAndValidateMagic(byteBuffer);

        return new UserInfo(userName, deviceName, mhrBpm, lthrBpm, ftp, year, month, day, gender, height, weight);
    }


    @Override
    public String toString() {
        return "UserInfo{" +
                "userName='" + userName + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", mhrBpm=" + mhrBpm +
                ", lthrBpm=" + lthrBpm +
                ", ftp=" + ftp +
                ", year=" + year +
                ", month=" + month +
                ", day=" + day +
                ", gender=" + gender +
                ", height=" + height +
                ", weight=" + weight +
                " (" + (weight * 0.1) + " kg)" +
                '}';
    }
}
