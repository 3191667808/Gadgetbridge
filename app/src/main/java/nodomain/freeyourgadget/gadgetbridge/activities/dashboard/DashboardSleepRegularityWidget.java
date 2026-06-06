/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.activities.dashboard;

import static nodomain.freeyourgadget.gadgetbridge.devices.GenericMetricSampleProvider.getLatestMetricSampleBefore;
import static nodomain.freeyourgadget.gadgetbridge.model.MetricSample.Metric.GENERIC_SLEEP_REGULARITY;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.DashboardFragment;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericMetricSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.MetricSample;

/**
 * Dashboard gauge for the generic Sleep Regularity Index metric.
 */
public class DashboardSleepRegularityWidget extends AbstractGaugeWidget {
    private static final Logger LOG = LoggerFactory.getLogger(DashboardSleepRegularityWidget.class);
    private static final String WIDGET_KEY = "sleepregularity";

    public DashboardSleepRegularityWidget() {
        super(R.string.dashboard_sleep_regularity_title, null);
    }

    public static DashboardSleepRegularityWidget newInstance(final DashboardFragment.DashboardData dashboardData) {
        final DashboardSleepRegularityWidget fragment = new DashboardSleepRegularityWidget();
        final Bundle args = new Bundle();
        args.putSerializable(ARG_DASHBOARD_DATA, dashboardData);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View fragmentView = super.onCreateView(inflater, container, savedInstanceState);
        final String details = getString(R.string.dashboard_sleep_regularity_title) + " — Phillips 2017\n"
                + getString(R.string.dashboard_sleep_regularity_summary);
        fragmentView.setContentDescription(details);
        fragmentView.setOnClickListener(v -> Toast.makeText(requireContext(), details, Toast.LENGTH_LONG).show());
        return fragmentView;
    }

    @Override
    protected void populateData(final DashboardFragment.DashboardData dashboardData) {
        final List<GBDevice> devices = getSupportedDevices(dashboardData);
        final SleepRegularityData data = new SleepRegularityData();

        MetricSample sample = null;
        try (DBHandler dbHandler = GBApplication.acquireDbReadOnly()) {
            for (GBDevice dev : devices) {
                final MetricSample latestSample = getLatestMetricSampleBefore(
                        dbHandler,
                        dev,
                        GENERIC_SLEEP_REGULARITY,
                        dashboardData.timeTo * 1000L
                );
                if (latestSample != null && (sample == null || latestSample.getTimestamp() > sample.getTimestamp())) {
                    sample = latestSample;
                }
            }

            if (sample != null) {
                data.value = (float) sample.getMetricScore();
            }
        } catch (final Exception e) {
            LOG.error("Could not get sleep regularity index", e);
        }

        dashboardData.put(WIDGET_KEY, data);
    }

    @Override
    protected void draw(final DashboardFragment.DashboardData dashboardData) {
        final SleepRegularityData data = (SleepRegularityData) dashboardData.get(WIDGET_KEY);
        if (data == null || data.value < 0) {
            setText("-");
            drawSimpleGauge(color_unknown, -1);
            return;
        }

        final float value = Math.max(0, Math.min(100, data.value));
        setText(String.valueOf(Math.round(value)));
        drawSimpleGauge(getColorForValue(value), value / 100f);
    }

    @Override
    protected boolean isSupportedBy(final GBDevice device) {
        return GenericMetricSampleProvider.supportsMetrics(device, GENERIC_SLEEP_REGULARITY);
    }

    private int getColorForValue(final float value) {
        if (value < 60) {
            return ContextCompat.getColor(GBApplication.getContext(), R.color.vo2max_value_poor_color);
        }
        if (value <= 80) {
            return ContextCompat.getColor(GBApplication.getContext(), R.color.vo2max_value_fair_color);
        }
        return ContextCompat.getColor(GBApplication.getContext(), R.color.hrv_status_balanced);
    }

    private static class SleepRegularityData implements Serializable {
        private float value = -1;
    }
}
