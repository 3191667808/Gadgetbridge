/*  Copyright (C) 2026 d3vv3

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

import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.charts.CombinedChart;
import com.github.mikephil.charting.charts.ScatterChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.CandleData;
import com.github.mikephil.charting.data.CandleDataSet;
import com.github.mikephil.charting.data.CandleEntry;
import com.github.mikephil.charting.data.CombinedData;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.ScatterData;
import com.github.mikephil.charting.data.ScatterDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.repository.EcgRepository;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.EcgRecord;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

public class EcgPeriodFragment extends AbstractChartFragment<EcgPeriodFragment.EcgPeriodData> {
    private static final int DATA_INVALID = -1;

    private int backgroundColor;
    private int chartTextColor;
    private int legendTextColor;
    private int ecgRangeColor;
    private int ecgAverageColor;

    private TextView dateView;
    private TextView minimumView;
    private TextView maximumView;
    private TextView averageView;
    private TextView measurementCountView;
    private CombinedChart chart;
    private int totalDays;

    @Override
    protected boolean isSingleDay() {
        return false;
    }

    public static EcgPeriodFragment newInstance(final int totalDays) {
        final EcgPeriodFragment fragment = new EcgPeriodFragment();
        final Bundle args = new Bundle();
        args.putInt("totalDays", totalDays);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        totalDays = getArguments() != null ? getArguments().getInt("totalDays") : 7;
    }

    @Override
    protected void init() {
        final TypedValue averageColor = new TypedValue();
        backgroundColor = GBApplication.getBackgroundColor(requireContext());
        legendTextColor = GBApplication.getTextColor(requireContext());
        chartTextColor = GBApplication.getSecondaryTextColor(requireContext());
        ecgRangeColor = ContextCompat.getColor(requireContext(), R.color.chart_line_heart_rate);
        requireContext().getTheme().resolveAttribute(R.attr.spo2_avg_color, averageColor, true);
        ecgAverageColor = averageColor.data;
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.fragment_ecg_period, container, false);
        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> getChartsHost().enableSwipeRefresh(scrollY == 0));

        dateView = rootView.findViewById(R.id.date_view);
        minimumView = rootView.findViewById(R.id.ecg_minimum);
        maximumView = rootView.findViewById(R.id.ecg_maximum);
        averageView = rootView.findViewById(R.id.ecg_average);
        measurementCountView = rootView.findViewById(R.id.ecg_measurement_count);
        chart = rootView.findViewById(R.id.ecg_chart);

        setupChart();
        setupLegend(chart);
        refresh();
        return rootView;
    }

    @Override
    public String getTitle() {
        return getString(R.string.withings_scanwatch_screen_ecg);
    }

    private long getStartTimestamp() {
        final Calendar day = Calendar.getInstance();
        day.setTime(getEndDate());
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        day.add(Calendar.DAY_OF_MONTH, -(totalDays - 1));
        return day.getTimeInMillis();
    }

    @Override
    protected EcgPeriodData refreshInBackground(final ChartsHost chartsHost, final DBHandler db, final GBDevice device) {
        final long periodStart = getStartTimestamp();
        final long periodEnd = ((long) getTSEnd() * 1000L) + 999L;
        final List<EcgRecord> summaries = EcgRepository.getSessions(db, device, periodStart, periodEnd);

        final List<EcgDayData> days = new ArrayList<>(totalDays);
        final long[] dayBoundaries = new long[totalDays + 1];
        final Calendar dayBoundary = Calendar.getInstance();
        dayBoundary.setTimeInMillis(periodStart);
        for (int i = 0; i < totalDays; i++) {
            dayBoundaries[i] = dayBoundary.getTimeInMillis();
            dayBoundary.add(Calendar.DAY_OF_MONTH, 1);
            days.add(new EcgDayData());
        }
        dayBoundaries[totalDays] = dayBoundary.getTimeInMillis();

        int validMeasurementCount = 0;
        int totalHeartRate = 0;
        int minimumHeartRate = Integer.MAX_VALUE;
        int maximumHeartRate = Integer.MIN_VALUE;

        for (final EcgRecord summary : summaries) {
            final int index = getDayIndex(summary.getStartTimestamp(), dayBoundaries);
            if (index < 0 || index >= totalDays) {
                continue;
            }

            final EcgDayData dayData = days.get(index);
            dayData.measurementCount++;

            final int averageHeartRate = summary.getAverageHeartRate();
            if (averageHeartRate > 0) {
                dayData.validMeasurementCount++;
                dayData.totalHeartRate += averageHeartRate;
                dayData.minimumHeartRate = dayData.minimumHeartRate == DATA_INVALID ? averageHeartRate : Math.min(dayData.minimumHeartRate, averageHeartRate);
                dayData.maximumHeartRate = dayData.maximumHeartRate == DATA_INVALID ? averageHeartRate : Math.max(dayData.maximumHeartRate, averageHeartRate);

                validMeasurementCount++;
                totalHeartRate += averageHeartRate;
                minimumHeartRate = Math.min(minimumHeartRate, averageHeartRate);
                maximumHeartRate = Math.max(maximumHeartRate, averageHeartRate);
            }
        }

        final int averageHeartRate = validMeasurementCount > 0 ? Math.round((float) totalHeartRate / validMeasurementCount) : DATA_INVALID;
        return new EcgPeriodData(
                days,
                summaries.size(),
                averageHeartRate,
                minimumHeartRate == Integer.MAX_VALUE ? DATA_INVALID : minimumHeartRate,
                maximumHeartRate == Integer.MIN_VALUE ? DATA_INVALID : maximumHeartRate
        );
    }

    static int getDayIndex(final long timestamp, final long[] dayBoundaries) {
        for (int i = 0; i < dayBoundaries.length - 1; i++) {
            if (timestamp >= dayBoundaries[i] && timestamp < dayBoundaries[i + 1]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void updateChartsnUIThread(final EcgPeriodData data) {
        dateView.setText(DateTimeUtils.formatDaysUntil(totalDays, getTSEnd()));

        final String emptyValue = requireContext().getString(R.string.stats_empty_value);
        minimumView.setText(data.minimumHeartRate > 0 ? getString(R.string.bpm_value_unit, data.minimumHeartRate) : emptyValue);
        maximumView.setText(data.maximumHeartRate > 0 ? getString(R.string.bpm_value_unit, data.maximumHeartRate) : emptyValue);
        averageView.setText(data.averageHeartRate > 0 ? getString(R.string.bpm_value_unit, data.averageHeartRate) : emptyValue);
        measurementCountView.setText(String.valueOf(data.measurementCount));

        final ArrayList<CandleEntry> rangeEntries = new ArrayList<>();
        final ArrayList<Entry> averageEntries = new ArrayList<>();
        for (int i = 0; i < data.days.size(); i++) {
            final EcgDayData dayData = data.days.get(i);
            if (dayData.minimumHeartRate > 0 && dayData.maximumHeartRate > 0) {
                rangeEntries.add(new CandleEntry(i, dayData.maximumHeartRate, dayData.minimumHeartRate, dayData.minimumHeartRate, dayData.maximumHeartRate));
                averageEntries.add(new Entry(i, dayData.getAverageHeartRate()));
            }
        }

        final String pattern = totalDays == 7 ? "EEE" : "dd";
        final SimpleDateFormat formatDay = new SimpleDateFormat(pattern, Locale.getDefault());
        final long startTimestamp = getStartTimestamp();
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(final float value) {
                final int dayIndex = Math.round(value);
                if (dayIndex < 0 || dayIndex >= totalDays) {
                    return "";
                }
                final Calendar day = Calendar.getInstance();
                day.setTimeInMillis(startTimestamp);
                day.add(Calendar.DAY_OF_MONTH, dayIndex);
                return formatDay.format(day.getTime());
            }
        });

        chart.getAxisLeft().setAxisMinimum(40f);
        chart.getAxisLeft().setAxisMaximum(120f);
        if (data.minimumHeartRate > 0) {
            chart.getAxisLeft().setAxisMinimum(Math.max((float) (10 * Math.floor((data.minimumHeartRate - 10) / 10.0f)), 0f));
        }
        if (data.maximumHeartRate > 0) {
            chart.getAxisLeft().setAxisMaximum(Math.max(data.maximumHeartRate + 10f, 100f));
        }

        final CombinedData combinedData = new CombinedData();
        if (!rangeEntries.isEmpty()) {
            final CandleDataSet rangeDataSet = new CandleDataSet(rangeEntries, getString(R.string.withings_scanwatch_screen_ecg));
            rangeDataSet.setDrawValues(false);
            rangeDataSet.setDrawIcons(false);
            rangeDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            rangeDataSet.setShadowColor(ecgRangeColor);
            rangeDataSet.setShadowWidth(2f);
            rangeDataSet.setDecreasingColor(ecgRangeColor);
            rangeDataSet.setDecreasingPaintStyle(Paint.Style.FILL);
            rangeDataSet.setIncreasingColor(ecgRangeColor);
            rangeDataSet.setIncreasingPaintStyle(Paint.Style.FILL);
            rangeDataSet.setNeutralColor(ecgRangeColor);
            rangeDataSet.setBarSpace(0.15f);
            rangeDataSet.setShowCandleBar(true);
            combinedData.setData(new CandleData(rangeDataSet));
        }

        if (!averageEntries.isEmpty()) {
            final ScatterDataSet averageDataSet = new ScatterDataSet(averageEntries, getString(R.string.hr_average));
            averageDataSet.setScatterShape(ScatterChart.ScatterShape.CIRCLE);
            averageDataSet.setScatterShapeSize(15f);
            averageDataSet.setColor(ecgAverageColor);
            averageDataSet.setDrawValues(false);
            averageDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            combinedData.setData(new ScatterData(averageDataSet));
        }

        chart.setData(combinedData);
    }

    private void setupChart() {
        chart.setBackgroundColor(backgroundColor);
        chart.getDescription().setEnabled(false);
        chart.setDrawOrder(new CombinedChart.DrawOrder[]{
                CombinedChart.DrawOrder.CANDLE,
                CombinedChart.DrawOrder.SCATTER
        });

        if (totalDays <= 7) {
            chart.setTouchEnabled(false);
            chart.setPinchZoom(false);
        }
        chart.setDoubleTapToZoomEnabled(false);

        final XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawLabels(true);
        xAxis.setDrawGridLines(false);
        xAxis.setEnabled(true);
        xAxis.setDrawLimitLinesBehindData(true);
        xAxis.setTextColor(chartTextColor);
        xAxis.setGranularity(1f);
        xAxis.setGranularityEnabled(true);
        xAxis.setAxisMinimum(-0.5f);
        xAxis.setAxisMaximum(totalDays - 0.5f);

        final YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setAxisMaximum(120f);
        leftAxis.setAxisMinimum(40f);
        leftAxis.setDrawTopYLabelEntry(true);
        leftAxis.setTextColor(chartTextColor);
        leftAxis.setEnabled(true);
        leftAxis.setGranularity(10f);
        leftAxis.setGranularityEnabled(true);

        final YAxis rightAxis = chart.getAxisRight();
        rightAxis.setEnabled(true);
        rightAxis.setDrawLabels(false);
        rightAxis.setDrawGridLines(false);
        rightAxis.setDrawAxisLine(true);
    }

    @Override
    protected void setupLegend(final Chart<?> chartView) {
        final List<LegendEntry> legendEntries = new ArrayList<>(2);

        final LegendEntry rangeEntry = new LegendEntry();
        rangeEntry.label = getString(R.string.withings_scanwatch_screen_ecg);
        rangeEntry.formColor = ecgRangeColor;
        legendEntries.add(rangeEntry);

        final LegendEntry averageEntry = new LegendEntry();
        averageEntry.label = getString(R.string.hr_average);
        averageEntry.formColor = ecgAverageColor;
        averageEntry.form = Legend.LegendForm.CIRCLE;
        legendEntries.add(averageEntry);

        chart.getLegend().setCustom(legendEntries);
        chart.getLegend().setTextColor(legendTextColor);
        chart.getLegend().setWordWrapEnabled(true);
    }

    @Override
    protected void renderCharts() {
        chart.invalidate();
    }

    protected static class EcgPeriodData extends ChartsData {
        private final List<EcgDayData> days;
        private final int measurementCount;
        private final int averageHeartRate;
        private final int minimumHeartRate;
        private final int maximumHeartRate;

        protected EcgPeriodData(final List<EcgDayData> days,
                                final int measurementCount,
                                final int averageHeartRate,
                                final int minimumHeartRate,
                                final int maximumHeartRate) {
            this.days = days;
            this.measurementCount = measurementCount;
            this.averageHeartRate = averageHeartRate;
            this.minimumHeartRate = minimumHeartRate;
            this.maximumHeartRate = maximumHeartRate;
        }
    }

    protected static class EcgDayData {
        private int measurementCount;
        private int validMeasurementCount;
        private int totalHeartRate;
        private int minimumHeartRate = DATA_INVALID;
        private int maximumHeartRate = DATA_INVALID;

        private int getAverageHeartRate() {
            return validMeasurementCount > 0 ? Math.round((float) totalHeartRate / validMeasurementCount) : DATA_INVALID;
        }
    }
}
