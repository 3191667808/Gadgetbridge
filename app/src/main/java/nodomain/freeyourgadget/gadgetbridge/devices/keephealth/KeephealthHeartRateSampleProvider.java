package nodomain.freeyourgadget.gadgetbridge.devices.keephealth;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class KeephealthHeartRateSampleProvider extends AbstractTimeSampleProvider<KeephealthHeartRateSample> {
    public KeephealthHeartRateSampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<KeephealthHeartRateSample, ?> getSampleDao() {
        return getSession().getKeephealthHeartRateSampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return KeephealthHeartRateSampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return KeephealthHeartRateSampleDao.Properties.DeviceId;
    }

    @Override
    public KeephealthHeartRateSample createSample() {
        return new KeephealthHeartRateSample();
    }
}