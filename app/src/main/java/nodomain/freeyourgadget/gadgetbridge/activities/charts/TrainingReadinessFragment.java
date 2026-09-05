/*  Copyright (C) 2026

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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeDrawer;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericMetricSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.MetricSample;

public class TrainingReadinessFragment extends AbstractChartFragment<TrainingReadinessFragment.TrainingReadinessData> {
    // Garmin's published Training Readiness zones, in ascending score order (matches
    // GaugeDrawer.drawSegmentedGauge's left-to-right convention).
    private static final int[] ZONE_COLOR_RES = {
            R.color.training_readiness_poor,
            R.color.training_readiness_low,
            R.color.training_readiness_moderate,
            R.color.training_readiness_high,
            R.color.training_readiness_prime,
    };
    private static final int[] ZONE_NAME_RES = {
            R.string.training_readiness_zone_poor,
            R.string.training_readiness_zone_low,
            R.string.training_readiness_zone_moderate,
            R.string.training_readiness_zone_high,
            R.string.training_readiness_zone_prime,
    };
    private static final int[] ZONE_DESC_RES = {
            R.string.training_readiness_zone_poor_desc,
            R.string.training_readiness_zone_low_desc,
            R.string.training_readiness_zone_moderate_desc,
            R.string.training_readiness_zone_high_desc,
            R.string.training_readiness_zone_prime_desc,
    };
    // Score bands: 1-24, 25-49, 50-74, 75-94, 95-100.
    private static final float[] ZONE_SEGMENTS = {0.24f, 0.25f, 0.25f, 0.20f, 0.06f};

    private final GaugeDrawer gaugeDrawer = new GaugeDrawer();

    private TextView dateView;
    private ImageView gaugeBar;
    private TextView gaugeValue;
    private TextView gaugeZone;
    private TextView zoneDescription;
    private TextView lastUpdated;

    @Override
    public String getTitle() {
        return getString(R.string.metric_garmin_training_readiness);
    }

    @Override
    protected void init() {
        // Nothing to precompute - colors are resolved lazily via zoneColors().
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.fragment_training_readiness, container, false);

        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            getChartsHost().enableSwipeRefresh(scrollY == 0);
        });

        dateView = rootView.findViewById(R.id.training_readiness_date_view);
        gaugeBar = rootView.findViewById(R.id.training_readiness_gauge_bar);
        gaugeValue = rootView.findViewById(R.id.training_readiness_gauge_value);
        gaugeZone = rootView.findViewById(R.id.training_readiness_gauge_zone);
        zoneDescription = rootView.findViewById(R.id.training_readiness_zone_description);
        lastUpdated = rootView.findViewById(R.id.training_readiness_last_updated);

        refresh();

        return rootView;
    }

    @Override
    protected TrainingReadinessData refreshInBackground(final ChartsHost chartsHost, final DBHandler db, final GBDevice device) {
        final long asOfMillis = getTSEnd() * 1000L;

        final MetricSample latest = GenericMetricSampleProvider.getLatestMetricSampleBefore(db, device, MetricSample.Metric.GARMIN_TRAINING_READINESS, asOfMillis);

        Integer score = null;
        Long timestamp = null;
        if (latest != null) {
            score = (int) Math.round(latest.getMetricScore());
            timestamp = latest.getTimestamp();
        }

        return new TrainingReadinessData(score, timestamp);
    }

    @Override
    protected void updateChartsnUIThread(final TrainingReadinessData data) {
        dateView.setText(new SimpleDateFormat("E, MMM dd", Locale.getDefault()).format(getEndDate()));

        final int[] zoneColors = zoneColors();
        if (data.score != null) {
            final int zoneIndex = zoneIndexForScore(data.score);

            gaugeDrawer.drawSegmentedGauge(gaugeBar, zoneColors, ZONE_SEGMENTS, data.score / 100f, false, true);
            gaugeValue.setText(String.valueOf(data.score));
            gaugeZone.setText(getString(ZONE_NAME_RES[zoneIndex]));
            gaugeZone.setTextColor(zoneColors[zoneIndex]);
            zoneDescription.setText(getString(ZONE_DESC_RES[zoneIndex]));
            lastUpdated.setText(data.timestamp != null
                    ? getString(R.string.training_readiness_last_updated, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(data.timestamp)))
                    : "");
        } else {
            gaugeDrawer.drawSegmentedGauge(gaugeBar, zoneColors, ZONE_SEGMENTS, -1f, false, true);
            gaugeValue.setText(getString(R.string.stats_empty_value));
            gaugeZone.setText("");
            zoneDescription.setText("");
            lastUpdated.setText("");
        }
    }

    private int[] zoneColors() {
        final int[] colors = new int[ZONE_COLOR_RES.length];
        for (int i = 0; i < ZONE_COLOR_RES.length; i++) {
            colors[i] = ContextCompat.getColor(requireContext(), ZONE_COLOR_RES[i]);
        }
        return colors;
    }

    private static int zoneIndexForScore(final int score) {
        if (score >= 95) {
            return 4;
        } else if (score >= 75) {
            return 3;
        } else if (score >= 50) {
            return 2;
        } else if (score >= 25) {
            return 1;
        }
        return 0;
    }

    @Override
    protected void renderCharts() {
        // No MPAndroidChart view on this fragment - the gauge is drawn directly in updateChartsnUIThread.
    }

    @Override
    protected void setupLegend(final Chart<?> chart) {
    }

    protected static class TrainingReadinessData extends ChartsData {
        @Nullable
        final Integer score;
        @Nullable
        final Long timestamp;

        TrainingReadinessData(@Nullable final Integer score, @Nullable final Long timestamp) {
            this.score = score;
            this.timestamp = timestamp;
        }
    }
}
