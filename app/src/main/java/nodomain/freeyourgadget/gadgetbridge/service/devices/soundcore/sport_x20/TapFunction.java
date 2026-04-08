package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sport_x20;

enum TapFunction {
    VOLUME_DOWN(0x01),
    VOLUME_UP(0x00),
    MEDIA_NEXT(0x03),
    MEDIA_PREV(0x02),
    PLAYPAUSE(0x06),
    VOICE_ASSISTANT(0x05),
    AMBIENT_SOUND_CONTROL(0x04),
    NONE(0x0f);

    private final int code;

    TapFunction(final int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static TapFunction fromPreferenceValue(final String value) {
        try {
            return TapFunction.valueOf(value);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }
}
