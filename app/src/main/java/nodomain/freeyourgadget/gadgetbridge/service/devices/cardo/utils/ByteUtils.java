package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils;

import org.apache.commons.lang3.EnumUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoEnums;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.ConfigMessage;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class ByteUtils {
    private static final Logger LOG = LoggerFactory.getLogger(ByteUtils.class);


    public static CardoMap<CardoField, Object> parseStructure(byte[] bytes, List<CardoField> fields) {
        CardoMap<CardoField, Object> result = new CardoMap<>();
        int totalBits = bytes.length * 8;
        int currentBit = 0;

        for (CardoField field : fields) {
            int numBits = field.getBitSize();

            if (currentBit + numBits > totalBits) {
                throw new IllegalArgumentException("Bit structure exceeds the available bits in the byte array.");
            }

            int value = 0;
            for (int j = 0; j < numBits; j++) {
                int byteIndex = (currentBit + j) / 8;
                int bitIndex = 7 - ((currentBit + j) % 8);
                int bitValue = (bytes[byteIndex] >> bitIndex) & 1;
                value = (value << 1) | bitValue;
            }

            result.put(field, field.interpretValue(value));

            currentBit += numBits;
        }


        //meant for developing/debugging: this part of the codes checks that the full message can be reconstructed by its fields
        if (true) {
            final byte[] reconstructed = ByteUtils.constructFieldStructure(result, fields, bytes.length);
            if (!Arrays.equals(bytes, reconstructed)) {
                LOG.error("Parsing incomplete/wrong. Reconstructed message: {}, INCOMING: {}", GB.hexdump(reconstructed), GB.hexdump(bytes));
            }
            assert Arrays.equals(bytes, reconstructed);
        }
        return result;
    }

    public static byte[] constructStructure(CardoMap<CardoField, Object> fieldValues, ConfigMessage.InfoType infoType) {
        final List<CardoField> orderedFields = infoType.getFields();
        final int totalLenght = infoType.getPayloadLength();

        return constructFieldStructure(fieldValues, orderedFields, totalLenght);
    }

    private static byte[] constructFieldStructure(CardoMap<CardoField, Object> fieldValues, List<CardoField> orderedFields, int totalLenght) {
        int totalBits = orderedFields.stream()
                .mapToInt(CardoField::getBitSize)
                .sum();

        int numBytes = (totalBits + 7) / 8;
        if (numBytes > totalLenght) {
            throw new IllegalArgumentException("Bit structure exceeds the available bits in the byte array.");
        }
        byte[] result = new byte[totalLenght];

        int currentBit = 0;

        for (CardoField field : orderedFields) {
            Object originalValue = fieldValues.get(field);

            int rawValue;
            if (field.getBitSize() == 1) {
                rawValue = Boolean.TRUE.equals(originalValue) ? 1 : 0;
            } else if (originalValue.getClass().isEnum()) {
                rawValue = ((CardoEnums) originalValue).getBtPayload();
            } else if (originalValue instanceof EnumSet) {
                EnumSet<?> enumSet = (EnumSet<?>) originalValue;
                Class<? extends Enum> enumClass = enumSet.isEmpty() ? null : enumSet.iterator().next().getDeclaringClass();
                rawValue = (int) EnumUtils.generateBitVector(enumClass
                        , ((EnumSet) originalValue));
            } else {
                rawValue = ((Number) originalValue).intValue();
            }

            int numBits = field.getBitSize();

            for (int j = 0; j < numBits; j++) {
                int byteIndex = (currentBit + j) / 8;
                int bitIndex = 7 - ((currentBit + j) % 8);

                int bitValue = (rawValue >> (numBits - 1 - j)) & 1;

                if (bitValue == 1) {
                    result[byteIndex] |= (1 << bitIndex);
                } else {
                    result[byteIndex] &= ~(1 << bitIndex);
                }
            }

            currentBit += numBits;
        }
        return result;
    }

    public static class CardoField {
        private final String name;
        private final int bitSize;
        private final Function<Object, Object> valueInterpreter;

        public CardoField(String name, int bitSize, Function<Object, Object> valueInterpreter) {
            this.name = name;
            this.bitSize = bitSize;
            this.valueInterpreter = valueInterpreter;
        }

        public CardoField(String name, int bitSize) {
            this(name, bitSize, bitSize == 1
                    ? value -> (int) value == 1
                    : Function.identity());
        }

        public int getBitSize() {
            return bitSize;
        }

        public String getName() {
            return name;
        }

        public Object interpretValue(Object value) {
            return valueInterpreter.apply(value);
        }

        @Override
        public String toString() {
            return "CardoField{" +
                    "name='" + name + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            CardoField that = (CardoField) obj;
            return bitSize == that.bitSize && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, bitSize);
        }
    }
}


