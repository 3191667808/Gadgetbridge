package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

public enum CardoCallState implements CardoEnums {
    IDLE,
    ESTABLISHING,
    ACTIVE_SCO,
    HOLD,
    WAITING_3_WAY,
    ACTIVE_3_WAY,
    ACTIVE_NO_SCO,
    ;

    @Override
    public int getBtPayload() {
        return ordinal();
    }

}
