package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums;

public enum CardoMicrophoneSensitivity implements CardoEnums {
    LOW,
    MEDIUM,
    HIGH,
    ;

    @Override
    public int getBtPayload() {
        return ordinal();
    }
}
