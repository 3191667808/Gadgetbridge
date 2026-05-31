package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

public enum CardoFmRegion implements CardoEnums {
    WORLDWIDE(8700, 10800),
    JAPAN(7600, 9500),
    ;

    private final int minFreq;
    private final int maxFreq;

    CardoFmRegion(int minFreq, int maxFreq) {
        this.minFreq = minFreq;
        this.maxFreq = maxFreq;
    }

    public int getMaxFreq() {
        return maxFreq;
    }

    public int getMinFreq() {
        return minFreq;
    }

    @Override
    public int getBtPayload() {
        return ordinal();
    }
}
