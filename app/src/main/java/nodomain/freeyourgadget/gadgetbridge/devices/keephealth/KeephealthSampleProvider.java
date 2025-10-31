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
        // TODO
//        switch (rawType) {
//            case 1: //DEEP_NAP
//            case 2: //DEEP_SLEEP
//                return ActivityKind.DEEP_SLEEP;
//            case 3: //LIGHT_NAP
//            case 4: //LIGHT_SLEEP
//                return ActivityKind.LIGHT_SLEEP;
//            case 5: //ACTIVITY
//            case 6: //WALK
//            case 7: //RUN
//                return ActivityKind.ACTIVITY;
//            default:
//                return ActivityKind.UNKNOWN;
//        }
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(ActivityKind activityKind) {
        // TODO
//        switch (activityKind) {
//            case ActivityKind.ACTIVITY:
//                return 5; // ACTIVITY
//            case ActivityKind.SLEEP:
//                return 2; // DEEP_SLEEP
//            case ActivityKind.SLEEP:
//                return 4; // LIGH_SLEEP
//            default:
//                return 5; //ACTIVITY
//        }
        return activityKind.getCode();
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