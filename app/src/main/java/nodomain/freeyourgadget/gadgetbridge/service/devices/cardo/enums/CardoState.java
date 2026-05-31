package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

import org.apache.commons.lang3.EnumUtils;

import java.util.EnumSet;

public enum CardoState implements CardoEnums {
    START,
    STAND_BY,
    PAIRING,
    MOBILE_CALL,
    MUSIC_ACTIVE,
    FM_ACTIVE,
    ;


    public static EnumSet<CardoState> fromBitMask(final int code) {
        return EnumUtils.processBitVector(CardoState.class, code);
    }

    @Override
    public int getBtPayload() {
        return ordinal();
    }
}
