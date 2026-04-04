package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgDataSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgDataSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgSummarySample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgSummarySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

public class EcgChartFragment extends AbstractChartFragment<EcgChartFragment.EcgChartData> {
    private static final int DATA_INVALID = -1;

    private int backgroundColor;
    private int chartTextColor;
    private int ecgColor;

    private TextView dateView;
    private LineChart chart;
    private TextView averageHeartRateView;
    private TextView durationView;
    private TextView measurementCountView;
    private LinearLayout sessionsContainer;
    private LinearLayout sessionsList;

    @Override
    protected void init() {
        backgroundColor = GBApplication.getBackgroundColor(requireContext());
        chartTextColor = GBApplication.getSecondaryTextColor(requireContext());
        ecgColor = ContextCompat.getColor(requireContext(), R.color.chart_line_heart_rate);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.fragment_ecg, container, false);

        dateView = rootView.findViewById(R.id.date_view);
        chart = rootView.findViewById(R.id.ecg_line_chart);
        averageHeartRateView = rootView.findViewById(R.id.ecg_average);
        durationView = rootView.findViewById(R.id.ecg_duration);
        measurementCountView = rootView.findViewById(R.id.ecg_measurement_count);
        sessionsContainer = rootView.findViewById(R.id.ecgSessions);
        sessionsList = rootView.findViewById(R.id.ecgSessionsList);

        sessionsContainer.setVisibility(View.GONE);
        setupLineChart();
        return rootView;
    }

    @Override
    protected EcgChartData refreshInBackground(final ChartsHost chartsHost, final DBHandler db, final GBDevice device) {
        final Calendar day = Calendar.getInstance();
        day.setTime(getEndDate());
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);

        final long startTimestamp = day.getTimeInMillis();
        final long endTimestamp = startTimestamp + TimeUnit.DAYS.toMillis(1) - 1;
        return fetchEcgData(db, device, startTimestamp, endTimestamp);
    }

    private EcgChartData fetchEcgData(final DBHandler db,
                                      final GBDevice device,
                                      final long startTimestamp,
                                      final long endTimestamp) {
        final long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
        final List<HuaweiEcgSummarySample> summaries = db.getDaoSession().getHuaweiEcgSummarySampleDao()
                .queryBuilder()
                .where(
                        HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(deviceId),
                        HuaweiEcgSummarySampleDao.Properties.StartTimestamp.ge(startTimestamp),
                        HuaweiEcgSummarySampleDao.Properties.StartTimestamp.le(endTimestamp)
                )
                .orderAsc(HuaweiEcgSummarySampleDao.Properties.StartTimestamp)
                .build()
                .list();

        HuaweiEcgSummarySample selectedSummary = null;
        List<HuaweiEcgDataSample> waveform = Collections.emptyList();

        for (int i = summaries.size() - 1; i >= 0; i--) {
            final HuaweiEcgSummarySample candidate = summaries.get(i);
            if (candidate.getEcgId() == null) {
                continue;
            }
            final List<HuaweiEcgDataSample> candidateWaveform = db.getDaoSession().getHuaweiEcgDataSampleDao()
                    .queryBuilder()
                    .where(HuaweiEcgDataSampleDao.Properties.EcgId.eq(candidate.getEcgId()))
                    .orderAsc(HuaweiEcgDataSampleDao.Properties.TimeDelta)
                    .build()
                    .list();
            selectedSummary = candidate;
            waveform = candidateWaveform;
            if (!candidateWaveform.isEmpty()) {
                break;
            }
        }

        if (selectedSummary == null && !summaries.isEmpty()) {
            selectedSummary = summaries.get(summaries.size() - 1);
        }

        return new EcgChartData(summaries, selectedSummary, waveform);
    }

    @Override
    protected void updateChartsnUIThread(final EcgChartData data) {
        sessionsList.removeAllViews();
        sessionsContainer.setVisibility(View.GONE);

        final String emptyValue = requireContext().getString(R.string.stats_empty_value);
        averageHeartRateView.setText(formatAverageHeartRate(data.selectedSummary, emptyValue));
        durationView.setText(formatDuration(data.selectedSummary, emptyValue));
        measurementCountView.setText(String.valueOf(data.summaries.size()));
        dateView.setText(new SimpleDateFormat("E, MMM dd", Locale.getDefault()).format(new Date((long) getTSEnd() * 1000L)));

        chart.setData(null);
        chart.clear();
        chart.getAxisLeft().setAxisMinimum(-2f);
        chart.getAxisLeft().setAxisMaximum(2f);
        chart.getXAxis().setAxisMinimum(0f);
        chart.getXAxis().setAxisMaximum(30f);
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(final float value) {
                return String.format(Locale.getDefault(), "%.0fs", value);
            }
        });

        if (data.selectedSummary != null && !data.waveform.isEmpty()) {
            final ArrayList<Entry> entries = new ArrayList<>(data.waveform.size());
            float minValue = Float.MAX_VALUE;
            float maxValue = -Float.MAX_VALUE;
            int maxDelta = 0;

            for (final HuaweiEcgDataSample sample : data.waveform) {
                final float seconds = sample.getTimeDelta() / 1000f;
                final float value = sample.getValue();
                entries.add(new Entry(seconds, value));
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
                maxDelta = Math.max(maxDelta, sample.getTimeDelta());
            }

            final List<ILineDataSet> dataSets = new ArrayList<>(1);
            dataSets.add(createDataSet(entries));
            chart.setData(new LineData(dataSets));

            final float durationSeconds = Math.max(maxDelta / 1000f, 1f);
            chart.getXAxis().setAxisMaximum(durationSeconds);
            chart.getXAxis().setLabelCount(6, true);
            chart.getXAxis().setValueFormatter(new SessionXAxisFormatter(data.selectedSummary.getStartTimestamp()));

            final YAxis leftAxis = chart.getAxisLeft();
            final float span = Math.max(maxValue - minValue, 0.5f);
            final float padding = span * 0.15f;
            leftAxis.setAxisMinimum(minValue - padding);
            leftAxis.setAxisMaximum(maxValue + padding);
        }

        if (!data.summaries.isEmpty()) {
            final LayoutInflater inflater = LayoutInflater.from(requireContext());
            for (int i = data.summaries.size() - 1; i >= 0; i--) {
                final HuaweiEcgSummarySample summary = data.summaries.get(i);
                final View row = inflater.inflate(R.layout.item_spo2_manual_measurment, sessionsList, false);
                final TextView timeText = row.findViewById(R.id.timeText);
                final TextView valueText = row.findViewById(R.id.valueText);

                timeText.setText(formatSessionRange(summary));
                valueText.setText(formatAverageHeartRate(summary, emptyValue));
                sessionsList.addView(row);
            }

            final View lastSeparator = sessionsList.getChildAt(sessionsList.getChildCount() - 1).findViewById(R.id.separator);
            lastSeparator.setVisibility(View.GONE);
            sessionsContainer.setVisibility(View.VISIBLE);
        }
    }

    private String formatSessionRange(final HuaweiEcgSummarySample summary) {
        final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return getString(
                R.string.date_placeholders__start_time__end_time,
                timeFormat.format(new Date(summary.getStartTimestamp())),
                timeFormat.format(new Date(summary.getEndTimestamp()))
        );
    }

    private String formatAverageHeartRate(final HuaweiEcgSummarySample summary, final String emptyValue) {
        if (summary == null || summary.getAverageHeartRate() <= 0) {
            return emptyValue;
        }
        return getString(R.string.bpm_value_unit, summary.getAverageHeartRate());
    }

    private String formatDuration(final HuaweiEcgSummarySample summary, final String emptyValue) {
        if (summary == null || summary.getEndTimestamp() <= summary.getStartTimestamp()) {
            return emptyValue;
        }
        return DateTimeUtils.formatDurationHoursMinutes(summary.getEndTimestamp() - summary.getStartTimestamp(), TimeUnit.MILLISECONDS);
    }

    private LineDataSet createDataSet(final List<Entry> values) {
        final LineDataSet dataSet = new LineDataSet(values, getString(R.string.withings_scanwatch_screen_ecg));
        dataSet.setColor(ecgColor);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        dataSet.setLineWidth(1.5f);
        dataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        dataSet.setHighlightEnabled(false);
        return dataSet;
    }

    private void setupLineChart() {
        chart.setBackgroundColor(backgroundColor);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.setPinchZoom(true);
        chart.setScaleEnabled(true);
        chart.setNoDataText(getString(R.string.chart_no_data_synchronize));

        final XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(chartTextColor);
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(30f);
        xAxis.setLabelCount(6, true);

        final YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setTextColor(chartTextColor);
        leftAxis.setAxisMinimum(-2f);
        leftAxis.setAxisMaximum(2f);

        final YAxis rightAxis = chart.getAxisRight();
        rightAxis.setEnabled(true);
        rightAxis.setDrawLabels(false);
        rightAxis.setDrawGridLines(false);
        rightAxis.setDrawAxisLine(true);
    }

    @Override
    public String getTitle() {
        return getString(R.string.withings_scanwatch_screen_ecg);
    }

    @Override
    protected void setupLegend(final Chart<?> chart) {
    }

    @Override
    protected void renderCharts() {
        chart.invalidate();
    }

    private static class SessionXAxisFormatter extends ValueFormatter {
        private final long startTimestamp;
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        private SessionXAxisFormatter(final long startTimestamp) {
            this.startTimestamp = startTimestamp;
        }

        @Override
        public String getFormattedValue(final float value) {
            return timeFormat.format(new Date(startTimestamp + (long) (value * 1000L)));
        }
    }

    protected static class EcgChartData extends ChartsData {
        private final List<HuaweiEcgSummarySample> summaries;
        private final HuaweiEcgSummarySample selectedSummary;
        private final List<HuaweiEcgDataSample> waveform;

        protected EcgChartData(final List<HuaweiEcgSummarySample> summaries,
                               final HuaweiEcgSummarySample selectedSummary,
                               final List<HuaweiEcgDataSample> waveform) {
            this.summaries = summaries;
            this.selectedSummary = selectedSummary;
            this.waveform = waveform;
        }
    }
}
