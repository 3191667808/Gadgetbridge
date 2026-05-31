package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

import org.apache.commons.lang3.EnumUtils;

import java.util.EnumSet;

public enum CardoLanguage implements CardoEnums {
    ENGLISH_US,
    ENGLISH_UK,
    SPANISH,
    FRENCH,
    DEUTSCH,
    JAPANESE,
    CHINESE,
    UNK_7,
    ITALIAN,
    RUSSIAN,
    HEBREW,
    PORTUGUESE,
    KOREAN;

    public static EnumSet<CardoLanguage> fromBitMask(final int code) {
        return EnumUtils.processBitVector(CardoLanguage.class, code);
    }

    @Override
    public int getBtPayload() {
        return ordinal();
    }
}
