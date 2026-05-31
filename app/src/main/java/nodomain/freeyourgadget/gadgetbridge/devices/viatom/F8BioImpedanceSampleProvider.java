package nodomain.freeyourgadget.gadgetbridge.devices.viatom;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.F8BioImpedanceSample;
import nodomain.freeyourgadget.gadgetbridge.entities.F8BioImpedanceSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class F8BioImpedanceSampleProvider extends AbstractTimeSampleProvider<F8BioImpedanceSample> {
    public F8BioImpedanceSampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public @NotNull AbstractDao<F8BioImpedanceSample, ?> getSampleDao() {
        return getSession().getF8BioImpedanceSampleDao();
    }

    @NonNull
    @Override
    protected @NotNull Property getTimestampSampleProperty() {
        return F8BioImpedanceSampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected @NotNull Property getDeviceIdentifierSampleProperty() {
        return F8BioImpedanceSampleDao.Properties.DeviceId;
    }

    @Override
    public F8BioImpedanceSample createSample() {
        return new F8BioImpedanceSample();
    }
}
