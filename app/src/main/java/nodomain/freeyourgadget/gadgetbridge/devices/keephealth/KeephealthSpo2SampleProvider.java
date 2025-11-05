package nodomain.freeyourgadget.gadgetbridge.devices.keephealth;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class KeephealthSpo2SampleProvider extends AbstractTimeSampleProvider<KeephealthSpo2Sample> {
    public KeephealthSpo2SampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<KeephealthSpo2Sample, ?> getSampleDao() {
        return getSession().getKeephealthSpo2SampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return KeephealthSpo2SampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return KeephealthSpo2SampleDao.Properties.DeviceId;
    }

    @Override
    public KeephealthSpo2Sample createSample() {
        return new KeephealthSpo2Sample();
    }
}
