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
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.HeartRate;

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
        LOG.debug(
                "Getting Keephealth activity samples between {} and {}",
                timestamp_from,
                timestamp_to
        );
        final long nanoStart = System.nanoTime();

        final List<KeephealthActivitySample> samples = super.getGBActivitySamples(timestamp_from, timestamp_to);
        final Map<Integer, KeephealthActivitySample> sampleByTs = new HashMap<>();
        for (final KeephealthActivitySample sample : samples) {
            sampleByTs.put(sample.getTimestamp(), sample);
        }

        overlayHeartRate(sampleByTs, timestamp_from, timestamp_to);

        final List<KeephealthActivitySample> finalSamples = new ArrayList<>(sampleByTs.values());
        Collections.sort(finalSamples, (a, b) -> Integer.compare(a.getTimestamp(), b.getTimestamp()));

        final long nanoEnd = System.nanoTime();
        final long executionTime = (nanoEnd - nanoStart) / 1000000;
        LOG.debug("Getting Keephealth samples took {}ms", executionTime);

        return finalSamples;
    }

    private void overlayHeartRate(final Map<Integer, KeephealthActivitySample> sampleByTs, final int timestamp_from, final int timestamp_to) {
        final KeephealthHeartRateSampleProvider heartRateSampleProvider = new KeephealthHeartRateSampleProvider(getDevice(), getSession());
        final List<KeephealthHeartRateSample> hrSamples = heartRateSampleProvider.getAllSamples(timestamp_from * 1000L, timestamp_to * 1000L);

        // build anchor map (seconds)
        final Map<Integer, Integer> hrByAnchor = new HashMap<>();
        for (KeephealthHeartRateSample s : hrSamples) {
//            LOG.debug("hrTimestamp {}", s.getTimestamp());
            hrByAnchor.put((int) (s.getTimestamp() / 1000), s.getHeartRate());
        }

        // interpolate hr using weighted average
        // 00:00 -- steps <-- 00:00 -- hr000
        // 00:10 -- steps <-- (hr000 + (2 * hr015)) / 3
        //                    00:15 -- hr015
        // 00:20 -- steps <-- ((2 * hr015) + hr030) / 3
        // 00:30 -- steps <-- 00:30 -- hr030
        // 00:40 -- steps <-- (hr030 + (2 * hr045)) / 3
        //                    00:45 -- hr045
        // 00:50 -- steps <-- ((2 * hr045) + hr100) / 3
        // 01:00 -- steps <-- 01:00 -- hr100

        int start = (timestamp_from / 60) * 60;
        int end = (timestamp_to / 60) * 60;
        int startMin = (start / 60) % 60;
        if (startMin % 10 != 0) start += (10 - (startMin % 10)) * 60;

        for (int ts = start; ts <= end; ts += 10 * 60) {
            KeephealthActivitySample sample = sampleByTs.computeIfAbsent(ts, k -> {
                KeephealthActivitySample n = new KeephealthActivitySample();
                n.setTimestamp(k);
                n.setProvider(this);
                return n;
            });

            int m = (ts / 60) % 60;
            Integer hr = null;
            switch (m) {
                case 0, 30 -> {
                    Integer v = hrByAnchor.get(ts);
                    if (v != null && v != 0) hr = v;
                    //LOG.debug("Anchor ts={} hr={}", ts, v);
                }
                case 10 -> {
                    Integer a = hrByAnchor.get(ts - 10 * 60), b = hrByAnchor.get(ts + 5 * 60);
                    //LOG.debug("10-min ts={} a={} b={}", ts, a, b);
                    if (a != null && b != null && a != 0 && b != 0) hr = (a + 2 * b) / 3;
                }
                case 20 -> {
                    Integer a = hrByAnchor.get(ts - 5 * 60), b = hrByAnchor.get(ts + 10 * 60);
                    //LOG.debug("20-min ts={} a={} b={}", ts, a, b);
                    if (a != null && b != null && a != 0 && b != 0) hr = (2 * a + b) / 3;
                }
                case 40 -> {
                    Integer a = hrByAnchor.get(ts - 10 * 60), b = hrByAnchor.get(ts + 5 * 60);
                    //LOG.debug("40-min ts={} a={} b={}", ts, a, b);
                    if (a != null && b != null && a != 0 && b != 0) hr = (a + 2 * b) / 3;
                }
                case 50 -> {
                    Integer a = hrByAnchor.get(ts - 5 * 60), b = hrByAnchor.get(ts + 10 * 60);
                    //LOG.debug("50-min ts={} a={} b={}", ts, a, b);
                    if (a != null && b != null && a != 0 && b != 0) hr = (2 * a + b) / 3;
                }
                default -> LOG.debug("Skipping non-10-min ts={} m={}", ts, m);
            }

            sample.setHeartRate(hr != null ? hr : ActivitySample.NOT_MEASURED);
            sampleByTs.put(ts, sample);
        }
    }
}