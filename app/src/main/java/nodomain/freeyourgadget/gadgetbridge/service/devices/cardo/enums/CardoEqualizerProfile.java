package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

import org.apache.commons.lang3.EnumUtils;

import java.util.EnumSet;

public enum CardoEqualizerProfile implements CardoEnums {
    HIGH_VOLUME,
    BASS_BOOST,
    VOCAL,
    OFF,
    UNK_4,
    UNK_5,
    UNK_6,
    UNK_7,
    UNK_8,
    UNK_9,
    UNK_10,
    UNK_11,
    UNK_12,
    UNK_13,
    UNK_14,
    UNK_15,
    JBL_HIGH_VOLUME,
    JBL_BASS_BOOST,
    JBL_VOCAL,
    JBL_OFF,
    ;

    public static EnumSet<CardoEqualizerProfile> fromBitMask(final int code) {
        return EnumUtils.processBitVector(CardoEqualizerProfile.class, code);
    }

    @Override
    public int getBtPayload() {
        return ordinal();
    }
}
