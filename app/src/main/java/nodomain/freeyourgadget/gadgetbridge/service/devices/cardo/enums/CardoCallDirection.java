package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

public enum CardoCallDirection implements CardoEnums {
    UNKNOWN,
    OUTGOING,
    INCOMING,
    ;

    @Override
    public int getBtPayload() {
        return ordinal();
    }

}
