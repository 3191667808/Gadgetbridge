package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

public enum CardoBackgroundVolume implements CardoEnums {
    PERCENT_10(9),
    PERCENT_20(1),
    PERCENT_30(2),
    PERCENT_40(3),
    PERCENT_50(4),
    PERCENT_60(5),
    PERCENT_70(6),
    PERCENT_80(7),
    PERCENT_90(8),
    PERCENT_100(0),
    ;

    private final int customIndex;

    CardoBackgroundVolume(int customIndex) {
        this.customIndex = customIndex;
    }

    public static CardoBackgroundVolume fromCustomIndex(int index) {
        for (CardoBackgroundVolume value : CardoBackgroundVolume.values()) {
            if (value.getCustomIndex() == index) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid index: " + index);
    }

    public int getCustomIndex() {
        return customIndex;
    }

    @Override
    public int getBtPayload() {
        return customIndex;
    }
}
