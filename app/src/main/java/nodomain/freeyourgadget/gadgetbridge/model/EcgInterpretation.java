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
    private final int peakCount;

    public EcgInterpretation(final DeviceHint deviceHint,
                             final SignalQuality signalQuality,
                             final Rhythm rhythm) {
        this(deviceHint, signalQuality, rhythm, 0);
    }

    public EcgInterpretation(final DeviceHint deviceHint,
                             final SignalQuality signalQuality,
                             final Rhythm rhythm,
                             final int peakCount) {
        this.deviceHint = deviceHint;
        this.signalQuality = signalQuality;
        this.rhythm = rhythm;
        this.peakCount = peakCount;
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

    public int getPeakCount() {
        return peakCount;
    }
}
