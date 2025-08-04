package nodomain.freeyourgadget.gadgetbridge.devices.sr08ring.samples;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08SleepSample;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08SleepSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class SR08SleepSampleProvider extends AbstractTimeSampleProvider<SR08SleepSample> {
    public SR08SleepSampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<SR08SleepSample, ?> getSampleDao() {
        return getSession().getSR08SleepSampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return SR08SleepSampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return SR08SleepSampleDao.Properties.DeviceId;
    }

    @Override
    public SR08SleepSample createSample() {
        return new SR08SleepSample();
    }
}
