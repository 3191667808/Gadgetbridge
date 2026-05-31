package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

public enum CardoDmcGroupState implements CardoEnums {
    READY,
    TALK,
    MUTE,
    ;

    @Override
    public int getBtPayload() {
        return ordinal();
    }
}
