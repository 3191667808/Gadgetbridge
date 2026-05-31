package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;

/**
 * Base class for configuration files that include a magic byte sequence at the end.
 */
public abstract class ConfigFile {
    protected static final byte[] MAGIC_BYTES = new byte[]{0x01, 0x00, 0x00, 0x00, (byte)0xA5, 0x5A, 0x55, (byte)0xAA};
    protected static final int MAGIC_SIZE = MAGIC_BYTES.length;

    protected final String filename;
    protected final FileType type;

    protected ConfigFile(String filename, FileType type) {
        this.filename = filename;
        this.type = type;
    }

    public String getFilename() {
        return filename;
    }

    public FileType getFileType() {
        return type;
    }

    /**
     * Subclasses must implement this to write their specific data payload to the buffer.
     * The buffer is assumed to be correctly sized and ordered (Little Endian).
     * @param buffer The ByteBuffer to write the payload to.
     * @throws IOException If an I/O error occurs.
     */
    protected abstract void writeSpecificDataPayload(ByteBuffer buffer) throws IOException;

    /**
     * Subclasses must implement this to return the size of their specific data payload,
     * excluding the magic bytes.
     * @return The size of the specific data payload in bytes.
     */
    public abstract int getSpecificDataPayloadSize();

    /**
     * Serializes the entire configuration file (specific data payload + magic bytes)
     * into a byte array.
     * @return A new byte array containing the serialized data.
     * @throws IOException If an I/O error occurs during payload writing.
     */
    public byte[] serializeToByteArray() throws IOException {
        int payloadSize = getSpecificDataPayloadSize();
        ByteBuffer byteBuffer = ByteBuffer.allocate(payloadSize + MAGIC_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        writeSpecificDataPayload(byteBuffer); // Subclass writes its data
        byteBuffer.put(MAGIC_BYTES);          // Base class appends magic bytes
        return byteBuffer.array();
    }

    /**
     * Reads and validates the magic bytes from the given ByteBuffer.
     * Assumes the buffer's current position is where the magic bytes begin.
     * @param bufferAfterPayload The ByteBuffer positioned at the start of the magic bytes.
     * @throws IOException If the buffer is too short, or if the magic bytes do not match.
     */
    protected static void readAndValidateMagic(ByteBuffer bufferAfterPayload) throws IOException {
        if (bufferAfterPayload.remaining() < MAGIC_SIZE) {
            throw new IOException("Buffer too short, not enough bytes remaining for magic. Expected " + MAGIC_SIZE + ", remaining: " + bufferAfterPayload.remaining());
        }
        byte[] magicRead = new byte[MAGIC_SIZE];
        bufferAfterPayload.get(magicRead);
        if (!Arrays.equals(MAGIC_BYTES, magicRead)) {
            throw new IOException("Magic bytes mismatch. Expected: " + Arrays.toString(MAGIC_BYTES) + ", Got: " + Arrays.toString(magicRead));
        }
    }
}
