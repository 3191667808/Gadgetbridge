/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;

public class MiScaleS400HeartRatePeriodFragment extends HeartRatePeriodFragment {
    public static MiScaleS400HeartRatePeriodFragment newInstance(final int totalDays) {
        final MiScaleS400HeartRatePeriodFragment fragment = new MiScaleS400HeartRatePeriodFragment();
        final Bundle args = new Bundle();
        args.putInt("totalDays", totalDays);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    protected List<? extends AbstractActivitySample> getActivitySamples(final DBHandler db,
                                                                        final GBDevice device,
                                                                        final int tsFrom,
                                                                        final int tsTo) {
        final TimeSampleProvider<? extends HeartRateSample> heartRateProvider =
                device.getDeviceCoordinator().getHeartRateMaxSampleProvider(device, db.getDaoSession());
        if (heartRateProvider == null) {
            return Collections.emptyList();
        }

        final List<? extends HeartRateSample> heartRateSamples =
                heartRateProvider.getAllSamples(tsFrom * 1000L, tsTo * 1000L);
        if (heartRateSamples.isEmpty()) {
            return Collections.emptyList();
        }

        final List<GenericActivitySample> fallbackSamples = new ArrayList<>(heartRateSamples.size());
        for (final HeartRateSample heartRateSample : heartRateSamples) {
            final GenericActivitySample sample = new GenericActivitySample();
            sample.setTimestamp((int) (heartRateSample.getTimestamp() / 1000L));
            sample.setHeartRate(heartRateSample.getHeartRate());
            fallbackSamples.add(sample);
        }

        return fallbackSamples;
    }
}
