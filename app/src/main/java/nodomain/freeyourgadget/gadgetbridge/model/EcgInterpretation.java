package nodomain.freeyourgadget.gadgetbridge.model;

public class EcgInterpretation {
    public enum DeviceHint {
        NORMAL,
        IRREGULAR,
        UNKNOWN
    }

    public enum SignalQuality {
        GOOD,
        NOISY,
        INCONCLUSIVE
    }

    public enum Rhythm {
        REGULAR,
        IRREGULAR,
        INCONCLUSIVE
    }

    private final DeviceHint deviceHint;
    private final SignalQuality signalQuality;
    private final Rhythm rhythm;

    public EcgInterpretation(final DeviceHint deviceHint,
                             final SignalQuality signalQuality,
                             final Rhythm rhythm) {
        this.deviceHint = deviceHint;
        this.signalQuality = signalQuality;
        this.rhythm = rhythm;
    }

    public DeviceHint getDeviceHint() {
        return deviceHint;
    }

    public SignalQuality getSignalQuality() {
        return signalQuality;
    }

    public Rhythm getRhythm() {
        return rhythm;
    }
}
