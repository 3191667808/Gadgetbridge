package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;

public class RidingBikeConfig extends ConfigFile {

    private static final int SIZE_OF_NUM_ENTRIES = 8; // u64 for number of entries
    private static final int ENTRY_ID_SIZE = 1; // u8
    private static final int BIKE_NAME_MAX_BYTES = 123; // char[123]
    private static final int ENTRY_WEIGHT_SIZE = 2; // u16
    private static final int ENTRY_WHEEL_LENGTH_SIZE = 2; // u16
    public static final int SERIALIZED_ENTRY_SIZE = ENTRY_ID_SIZE + BIKE_NAME_MAX_BYTES + ENTRY_WEIGHT_SIZE + ENTRY_WHEEL_LENGTH_SIZE;
    private static final FileType type = FileType.MY_BIKE_CONFIG;
    private static final String filename = "riding_bike.bin";

    private final List<Entry> entries;

    public RidingBikeConfig(List<Entry> entries) {
        super(filename, type);
        this.entries = new ArrayList<>(Objects.requireNonNull(entries, "Entries list cannot be null"));
    }

    public List<Entry> getEntries() {
        return new ArrayList<>(entries); // Return a copy for immutability
    }

    @Override
    public int getSpecificDataPayloadSize() {
        return SIZE_OF_NUM_ENTRIES + entries.size() * SERIALIZED_ENTRY_SIZE;
    }

    @Override
    protected void writeSpecificDataPayload(ByteBuffer buffer) throws IOException {
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Ensure little-endian for all multi-byte types

        buffer.putLong(entries.size());
        for (Entry entry : entries) {
            entry.writeToBuffer(buffer);
        }
    }

    public static RidingBikeConfig fromBytes(byte[] fullBuffer) throws IOException {
        Objects.requireNonNull(fullBuffer, "Input buffer cannot be null");
        if (fullBuffer.length < MAGIC_SIZE + SIZE_OF_NUM_ENTRIES) {
            throw new IOException("Buffer too short to contain num_entries and magic. Min length: " + (MAGIC_SIZE + SIZE_OF_NUM_ENTRIES) + ", actual: " + fullBuffer.length);
        }

        int payloadSize = fullBuffer.length - MAGIC_SIZE;
        ByteBuffer payloadBuffer = ByteBuffer.wrap(fullBuffer, 0, payloadSize).order(ByteOrder.LITTLE_ENDIAN);

        long numEntries = payloadBuffer.getLong();
        if (numEntries < 0 || numEntries > (payloadBuffer.remaining() / SERIALIZED_ENTRY_SIZE)) { // Basic sanity check for numEntries
            throw new IOException("Invalid number of entries: " + numEntries + " or buffer too small for declared entries.");
        }

        int expectedPayloadSize = SIZE_OF_NUM_ENTRIES + (int) numEntries * SERIALIZED_ENTRY_SIZE;
        if (payloadSize != expectedPayloadSize) {
            throw new IOException("Payload size mismatch. Expected: " + expectedPayloadSize + ", Got: " + payloadSize);
        }

        List<Entry> parsedEntries = new ArrayList<>();
        for (int i = 0; i < numEntries; i++) {
            if (payloadBuffer.remaining() < SERIALIZED_ENTRY_SIZE) {
                throw new IOException("Buffer too short for entry #" + i);
            }
            parsedEntries.add(Entry.fromBuffer(payloadBuffer));
        }

        // Validate magic bytes from the end of the full buffer
        ByteBuffer magicBuffer = ByteBuffer.wrap(fullBuffer, payloadSize, MAGIC_SIZE);
        readAndValidateMagic(magicBuffer);

        return new RidingBikeConfig(parsedEntries);
    }

    @Override
    public String toString() {
        return "RidingBikeConfig{" +
                ", entries=" + entries +
                '}';
    }

    public static class Entry {
        private final byte id;
        private final String bikeName;
        private final short weight;
        private final short wheelLength;

        public Entry(byte id, String bikeName, short weight, short wheelLength) {
            this.id = id;
            this.bikeName = Objects.requireNonNull(bikeName, "Bike name cannot be null");
            this.weight = weight;
            this.wheelLength = wheelLength;
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "bikeName=" + bikeName +
                    ", weight='" + weight + '\'' +
                    ", wheelLength=" + wheelLength +
                    '}';
        }

        public byte getId() {
            return id;
        }

        public String getBikeName() {
            return bikeName;
        }

        public short getWeight() {
            return weight;
        }

        public short getWheelLength() {
            return wheelLength;
        }

        void writeToBuffer(ByteBuffer buffer) throws IOException {
            buffer.order(ByteOrder.LITTLE_ENDIAN); // Ensure little-endian for multi-byte types

            buffer.put(id);

            byte[] nameBytes = bikeName.getBytes(StandardCharsets.UTF_8);
            byte[] fixedNameBytes = new byte[BIKE_NAME_MAX_BYTES]; // Initializes with 0x00
            System.arraycopy(nameBytes, 0, fixedNameBytes, 0, Math.min(nameBytes.length, BIKE_NAME_MAX_BYTES));
            buffer.put(fixedNameBytes);

            buffer.putShort(weight);
            buffer.putShort(wheelLength);
        }

        static Entry fromBuffer(ByteBuffer buffer) throws IOException {
            buffer.order(ByteOrder.LITTLE_ENDIAN); // Ensure little-endian for multi-byte types

            byte id = buffer.get();

            byte[] nameBytes = new byte[BIKE_NAME_MAX_BYTES];
            buffer.get(nameBytes);
            int nameLength = 0;
            while (nameLength < BIKE_NAME_MAX_BYTES && nameBytes[nameLength] != 0) {
                nameLength++;
            }
            String bikeName = new String(nameBytes, 0, nameLength, StandardCharsets.UTF_8);

            short weight = buffer.getShort();
            short wheelLength = buffer.getShort();

            return new Entry(id, bikeName, weight, wheelLength);
        }
        
    }
}
