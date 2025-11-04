package nodomain.freeyourgadget.gadgetbridge.devices.keephealth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class KeephealthSampleProvider extends AbstractSampleProvider<KeephealthActivitySample> {
    private static final Logger LOG = LoggerFactory.getLogger(KeephealthSampleProvider.class);
    public KeephealthSampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<KeephealthActivitySample, ?> getSampleDao() {
        return getSession().getKeephealthActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return KeephealthActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return KeephealthActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return KeephealthActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(int rawType) {
        switch (rawType) {
            case 1: // fall asleep in vendor app, is there a better kind?
                return ActivityKind.SLEEP_ANY;
            case 2: // LIGHT_SLEEP
                return ActivityKind.LIGHT_SLEEP;
            case 3: // DEEP_SLEEP
            case 5: // deep sleep in vendor app, but might be some other thing
                return ActivityKind.DEEP_SLEEP;
            case 4: // awake
                return ActivityKind.AWAKE_SLEEP;
            default:
                return ActivityKind.UNKNOWN;
        }
    }

    @Override
    public int toRawActivityKind(ActivityKind activityKind) {
        switch (activityKind) {
            case SLEEP_ANY: // fall asleep
                return 1;
            case LIGHT_SLEEP: // LIGHT_SLEEP
                return 2;
            case DEEP_SLEEP: // DEEP_SLEEP
                return 3;
            case AWAKE_SLEEP: // awake
                return 4;
            default:
                return 0;
        }
    }


    @Override
    public float normalizeIntensity(int rawIntensity) {
        return rawIntensity / 255.0f;
    }

    @Override
    public KeephealthActivitySample createActivitySample() {
        return new KeephealthActivitySample();
    }

    @Override
    protected List<KeephealthActivitySample> getGBActivitySamples(final int timestamp_from, final int timestamp_to) {
        LOG.trace(
                "Getting Keephealth activity samples between {} and {}",
                timestamp_from,
                timestamp_to
        );
        final long nanoStart = System.nanoTime();

        return super.getGBActivitySamples(timestamp_from, timestamp_to);

        // TODO implement
//        final List<KeephealthActivitySample> samples = fillGaps(
//                super.getGBActivitySamples(timestamp_from, timestamp_to),
//                timestamp_from,
//                timestamp_to
//        );
//
//        final Map<Integer, KeephealthActivitySample> sampleByTs = new HashMap<>();
//        for (final KeephealthActivitySample sample : samples) {
//            sampleByTs.put(sample.getTimestamp(), sample);
//        }
//
////        overlayHeartRate(sampleByTs, timestamp_from, timestamp_to);
////        overlaySleep(sampleByTs, timestamp_from, timestamp_to);
//
//        final List<KeephealthActivitySample> finalSamples = new ArrayList<>(sampleByTs.values());
//        Collections.sort(finalSamples, (a, b) -> Integer.compare(a.getTimestamp(), b.getTimestamp()));
//
//        final long nanoEnd = System.nanoTime();
//        final long executionTime = (nanoEnd - nanoStart) / 1000000;
//        LOG.trace("Getting Keephealth samples took {}ms", executionTime);
//
//        return finalSamples;
    }

//    private void overlayHeartRate(final Map<Integer, KeephealthActivitySample> sampleByTs, final int timestamp_from, final int timestamp_to) {
//        final KeephealthHeartRateSampleProvider heartRateSampleProvider = new KeephealthHeartRateSampleProvider(getDevice(), getSession());
//        final List<KeephealthHeartRateSample> hrSamples = heartRateSampleProvider.getAllSamples(timestamp_from * 1000L, timestamp_to * 1000L);
//
//        for (final KeephealthHeartRateSample hrSample : hrSamples) {
//            // round to the nearest minute, we don't need per-second granularity
//            final int tsSeconds = (int) ((hrSample.getTimestamp() / 1000) / 60) * 60;
//            KeephealthActivitySample sample = sampleByTs.get(tsSeconds);
////            if (sample == null) {
////                sample = new KeephealthActivitySample();
////                sample.setTimestamp(tsSeconds);
////                sample.setProvider(this);
////                sampleByTs.put(tsSeconds, sample);
////            }
//            if (sample != null) {
//                sample.setHeartRate(hrSample.getHeartRate());
//            }
//        }
//    }
}