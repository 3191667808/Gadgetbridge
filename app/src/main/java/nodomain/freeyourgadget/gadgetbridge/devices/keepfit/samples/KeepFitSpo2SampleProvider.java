/*  Copyright (C) 2025 Daniel Giritzer, MSc (giri@nwrk.biz)
    Copyright (C) 2025 De_Coder (de_coder@posteo.de)

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class KeepFitSpo2SampleProvider extends AbstractTimeSampleProvider<KeepFitSpo2Sample> {
    public KeepFitSpo2SampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<KeepFitSpo2Sample, ?> getSampleDao() {
        return getSession().getKeepFitSpo2SampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return KeepFitSpo2SampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return KeepFitSpo2SampleDao.Properties.DeviceId;
    }

    @Override
    public KeepFitSpo2Sample createSample() {
        return new KeepFitSpo2Sample();
    }
}
