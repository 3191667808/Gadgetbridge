package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

public enum CardoFmState implements CardoEnums {
    IDLE,
    ACTIVE,
    SCANNING_FORWARD,
    SCANNING_BACKWARD,
    AUTOTUNE_FORWARD,
    AUTOTUNE_BACKWARD;


    @Override
    public int getBtPayload() {
        return ordinal();
    }
}
