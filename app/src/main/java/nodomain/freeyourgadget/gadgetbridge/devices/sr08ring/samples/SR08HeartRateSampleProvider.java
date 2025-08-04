package nodomain.freeyourgadget.gadgetbridge.devices.sr08ring.samples;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08HeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class SR08HeartRateSampleProvider extends AbstractTimeSampleProvider<SR08HeartRateSample> {
    public SR08HeartRateSampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<SR08HeartRateSample, ?> getSampleDao() {
        return getSession().getSR08HeartRateSampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return SR08HeartRateSampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return SR08HeartRateSampleDao.Properties.DeviceId;
    }

    @Override
    public SR08HeartRateSample createSample() {
        return new SR08HeartRateSample();
    }
}
