package nodomain.freeyourgadget.gadgetbridge.devices.keephealth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class KeephealthSampleProvider extends AbstractSampleProvider<KeephealthActivitySample> {
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
}