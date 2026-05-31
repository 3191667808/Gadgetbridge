package nodomain.freeyourgadget.gadgetbridge.model;

public class EcgSample {
    private final int timeDeltaMs;
    private final float value;

    public EcgSample(final int timeDeltaMs, final float value) {
        this.timeDeltaMs = timeDeltaMs;
        this.value = value;
    }

    public int getTimeDeltaMs() {
        return timeDeltaMs;
    }

    public float getValue() {
        return value;
    }
}
