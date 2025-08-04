package nodomain.freeyourgadget.gadgetbridge.devices.sr08ring.samples;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08Spo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class SR08Spo2SampleProvider extends AbstractTimeSampleProvider<SR08Spo2Sample> {
    public SR08Spo2SampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<SR08Spo2Sample, ?> getSampleDao() {
        return getSession().getSR08Spo2SampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return SR08Spo2SampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return SR08Spo2SampleDao.Properties.DeviceId;
    }

    @Override
    public SR08Spo2Sample createSample() {
        return new SR08Spo2Sample();
    }
}
