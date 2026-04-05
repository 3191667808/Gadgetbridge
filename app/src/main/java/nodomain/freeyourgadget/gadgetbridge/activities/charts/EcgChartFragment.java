package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.os.Bundle;
import android.graphics.Color;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.repository.EcgRepository;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.EcgInterpretation;
import nodomain.freeyourgadget.gadgetbridge.model.EcgRecord;
import nodomain.freeyourgadget.gadgetbridge.model.EcgSample;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.EcgInterpretationUtil;
import nodomain.freeyourgadget.gadgetbridge.util.WithingsEcgWaveformUtil;

public class EcgChartFragment extends AbstractChartFragment<EcgChartFragment.EcgChartData> {
    private static final int DATA_INVALID = -1;
    private static final int DISPLAY_MEDIAN_WINDOW = 9;
    private static final int DISPLAY_AVERAGE_WINDOW = 7;
    private static final int DISPLAY_BASELINE_WINDOW = 121;
    private static final int DISPLAY_OUTLIER_WINDOW = 11;
    private static final float DISPLAY_TARGET_AMPLITUDE = 0.95f;
    private static final float DISPLAY_MAX_AMPLITUDE = 1.25f;
    private static final float DISPLAY_Y_AXIS_HALF_RANGE = 1.5f;
    private static final int DISPLAY_RESAMPLE_MS = 20;
    private static final int WATCH_STYLE_RESAMPLE_MS = 28;
    private static final int WATCH_STYLE_AVERAGE_WINDOW = 5;
    private static final float ECG_MAJOR_X_GRID_SECONDS = 0.2f;
    private static final float ECG_MINOR_X_GRID_SECONDS = 0.04f;
    private static final float ECG_MAJOR_Y_GRID = 0.5f;
    private static final float ECG_MINOR_Y_GRID = 0.1f;
    private static final int ECG_PAPER_BACKGROUND = 0xFFFFFCFC;
    private static final int ECG_MAJOR_GRID_COLOR = 0x33E57373;
    private static final int ECG_MINOR_GRID_COLOR = 0x1AE57373;

    private int backgroundColor;
    private int chartTextColor;
    private int ecgColor;
    private int selectedSessionBackgroundColor;
    private int selectedSessionTextColor;

    private TextView dateView;
    private LineChart chart;
    private TextView averageHeartRateView;
    private TextView durationView;
    private TextView measurementCountView;
    private TextView resultView;
    private TextView interpretationView;
    private TextView smoothingModeToggleView;
    private ScrollView scrollView;
    private LinearLayout sessionsContainer;
    private LinearLayout sessionsList;
    private Long selectedSessionStartTimestamp;
    private boolean watchStyleDisplayEnabled = true;

    @Override
    protected void init() {
        backgroundColor = GBApplication.getBackgroundColor(requireContext());
        chartTextColor = GBApplication.getSecondaryTextColor(requireContext());
        ecgColor = ContextCompat.getColor(requireContext(), R.color.chart_line_heart_rate);
        selectedSessionBackgroundColor = resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer);
        selectedSessionTextColor = resolveThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.fragment_ecg, container, false);

        scrollView = (ScrollView) rootView;
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                getChartsHost().enableSwipeRefresh(scrollY == 0));
        dateView = rootView.findViewById(R.id.date_view);
        chart = rootView.findViewById(R.id.ecg_line_chart);
        averageHeartRateView = rootView.findViewById(R.id.ecg_average);
        durationView = rootView.findViewById(R.id.ecg_duration);
        measurementCountView = rootView.findViewById(R.id.ecg_measurement_count);
        resultView = rootView.findViewById(R.id.ecg_result);
        interpretationView = rootView.findViewById(R.id.ecg_gadgetbridge_interpretation);
        smoothingModeToggleView = rootView.findViewById(R.id.ecg_smoothing_mode_toggle);
        sessionsContainer = rootView.findViewById(R.id.ecgSessions);
        sessionsList = rootView.findViewById(R.id.ecgSessionsList);

        sessionsContainer.setVisibility(View.GONE);
        smoothingModeToggleView.setOnClickListener(v -> {
            watchStyleDisplayEnabled = !watchStyleDisplayEnabled;
            updateSmoothingModeLabel();
            refresh();
        });
        updateSmoothingModeLabel();
        setupLineChart();
        setupChartTouchHandling();
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
        return fetchEcgData(db, device, startTimestamp, endTimestamp, selectedSessionStartTimestamp);
    }

    private EcgChartData fetchEcgData(final DBHandler db,
                                      final GBDevice device,
                                      final long startTimestamp,
                                      final long endTimestamp,
                                      final Long selectedSessionTimestamp) {
        final List<EcgRecord> summaries = EcgRepository.getSessions(db, device, startTimestamp, endTimestamp);

        EcgRecord selectedSummary = null;
        List<EcgSample> waveform = Collections.emptyList();

        if (selectedSessionTimestamp != null) {
            selectedSummary = findSummaryByTimestamp(summaries, selectedSessionTimestamp);
            waveform = EcgRepository.getSamples(db, selectedSummary);
        }

        if (selectedSummary == null) {
            for (int i = summaries.size() - 1; i >= 0; i--) {
                final EcgRecord candidate = summaries.get(i);
                final List<EcgSample> candidateWaveform = EcgRepository.getSamples(db, candidate);
                selectedSummary = candidate;
                waveform = candidateWaveform;
                if (!candidateWaveform.isEmpty()) {
                    break;
                }
            }
        }

        if (selectedSummary == null && !summaries.isEmpty()) {
            selectedSummary = summaries.get(summaries.size() - 1);
        }

        final EcgInterpretation interpretation = selectedSummary != null
                ? EcgInterpretationUtil.interpret(selectedSummary, waveform)
                : null;

        return new EcgChartData(summaries, selectedSummary, waveform, interpretation);
    }

    @Override
    protected void updateChartsnUIThread(final EcgChartData data) {
        sessionsList.removeAllViews();
        sessionsContainer.setVisibility(View.GONE);
        selectedSessionStartTimestamp = data.selectedSummary != null ? data.selectedSummary.getStartTimestamp() : null;

        final String emptyValue = requireContext().getString(R.string.stats_empty_value);
        averageHeartRateView.setText(formatAverageHeartRate(data.selectedSummary, emptyValue));
        durationView.setText(formatDuration(data.selectedSummary, emptyValue));
        measurementCountView.setText(String.valueOf(data.summaries.size()));
        resultView.setText(formatDeviceHint(data.selectedSummary, emptyValue));
        interpretationView.setText(formatGadgetbridgeInterpretation(data.interpretation, emptyValue));
        dateView.setText(new SimpleDateFormat("E, MMM dd", Locale.getDefault()).format(new Date((long) getTSEnd() * 1000L)));
        updateSmoothingModeLabel();

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
            final List<EcgSample> displayWaveform = smoothWaveformForDisplay(data.selectedSummary, data.waveform);
            final ArrayList<Entry> entries = new ArrayList<>(displayWaveform.size());
            float minValue = Float.MAX_VALUE;
            float maxValue = -Float.MAX_VALUE;
            int maxDelta = 0;

            for (final EcgSample sample : displayWaveform) {
                final float seconds = sample.getTimeDeltaMs() / 1000f;
                final float value = sample.getValue();
                entries.add(new Entry(seconds, value));
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
                maxDelta = Math.max(maxDelta, sample.getTimeDeltaMs());
            }

            final List<ILineDataSet> dataSets = new ArrayList<>(1);
            dataSets.add(createDataSet(entries));
            chart.setData(new LineData(dataSets));

            final long recordDurationMs = data.selectedSummary.getEndTimestamp() - data.selectedSummary.getStartTimestamp();
            final float durationSeconds = recordDurationMs > 0
                    ? Math.max(maxDelta / 1000f, recordDurationMs / 1000f)
                    : Math.max(maxDelta / 1000f, 1f);
            chart.getXAxis().setAxisMaximum(durationSeconds);
            chart.getXAxis().setLabelCount(6, true);
            chart.getXAxis().setValueFormatter(new SessionXAxisFormatter(data.selectedSummary.getStartTimestamp()));

            final YAxis leftAxis = chart.getAxisLeft();
            final float maxAbs = Math.max(Math.abs(minValue), Math.abs(maxValue));
            final float halfRange = Math.max(DISPLAY_Y_AXIS_HALF_RANGE, maxAbs * 1.25f);
            leftAxis.setAxisMinimum(-halfRange);
            leftAxis.setAxisMaximum(halfRange);
            applyEcgGrid(0f, durationSeconds, halfRange);
            Log.d("EcgChart", "durationSeconds=" + durationSeconds + " maxDelta=" + maxDelta + " recordDurationMs=" + recordDurationMs + " limitLineCount=" + chart.getXAxis().getLimitLines().size());
            chart.fitScreen();
        }

        if (!data.summaries.isEmpty()) {
            final LayoutInflater inflater = LayoutInflater.from(requireContext());
            for (int i = data.summaries.size() - 1; i >= 0; i--) {
                final EcgRecord summary = data.summaries.get(i);
                final View row = inflater.inflate(R.layout.item_ecg_session, sessionsList, false);
                final TextView timeText = row.findViewById(R.id.timeText);
                final TextView valueText = row.findViewById(R.id.valueText);
                final View clickableRow = row.findViewById(R.id.ecg_session_row);
                final boolean isSelected = data.selectedSummary != null
                        && data.selectedSummary.getStartTimestamp() == summary.getStartTimestamp();

                timeText.setText(formatDeviceHint(summary, emptyValue));
                valueText.setText(formatSessionStartTime(summary));
                row.setActivated(isSelected);
                row.setBackgroundColor(isSelected ? selectedSessionBackgroundColor : backgroundColor);
                timeText.setTextColor(isSelected ? selectedSessionTextColor : chartTextColor);
                valueText.setTextColor(isSelected ? selectedSessionTextColor : averageHeartRateView.getCurrentTextColor());

                if (summary.getSessionId() != null) {
                    clickableRow.setOnClickListener(v -> {
                        selectedSessionStartTimestamp = summary.getStartTimestamp();
                        scrollToTop();
                        refresh();
                    });
                } else {
                    clickableRow.setAlpha(0.6f);
                    clickableRow.setClickable(false);
                }

                sessionsList.addView(row);
            }

            final View lastSeparator = sessionsList.getChildAt(sessionsList.getChildCount() - 1).findViewById(R.id.separator);
            lastSeparator.setVisibility(View.GONE);
            sessionsContainer.setVisibility(View.VISIBLE);
        }
    }

    private String formatSessionStartTime(final EcgRecord summary) {
        final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return timeFormat.format(new Date(summary.getStartTimestamp()));
    }

    private String formatAverageHeartRate(final EcgRecord summary, final String emptyValue) {
        if (summary == null || summary.getAverageHeartRate() <= 0) {
            return emptyValue;
        }
        return getString(R.string.bpm_value_unit, summary.getAverageHeartRate());
    }

    private String formatDuration(final EcgRecord summary, final String emptyValue) {
        if (summary == null || summary.getEndTimestamp() <= summary.getStartTimestamp()) {
            return emptyValue;
        }
        return DateTimeUtils.formatDurationHoursMinutes(summary.getEndTimestamp() - summary.getStartTimestamp(), TimeUnit.MILLISECONDS);
    }

    private String formatDeviceHint(final EcgRecord summary, final String emptyValue) {
        if (summary == null) {
            return emptyValue;
        }

        switch (EcgInterpretationUtil.toDeviceHint(summary.getDeviceHintCode())) {
            case NORMAL:
                return getString(R.string.normal);
            case IRREGULAR:
                return getString(R.string.irregular);
            default:
                return emptyValue;
        }
    }

    private String formatGadgetbridgeInterpretation(final EcgInterpretation interpretation, final String emptyValue) {
        if (interpretation == null) {
            return emptyValue;
        }

        final String rhythm = formatRhythm(interpretation.getRhythm());
        final String quality = formatSignalQuality(interpretation.getSignalQuality());
        if (rhythm == null && quality == null) {
            return emptyValue;
        }
        if (rhythm == null) {
            return quality;
        }
        if (quality == null) {
            return rhythm;
        }
        return getString(R.string.ecg_interpretation_combined, rhythm, quality);
    }

    private String formatRhythm(final EcgInterpretation.Rhythm rhythm) {
        if (rhythm == null) {
            return null;
        }
        switch (rhythm) {
            case REGULAR:
                return getString(R.string.ecg_rhythm_regular);
            case IRREGULAR:
                return getString(R.string.ecg_rhythm_irregular);
            default:
                return getString(R.string.ecg_interpretation_inconclusive);
        }
    }

    private String formatSignalQuality(final EcgInterpretation.SignalQuality signalQuality) {
        if (signalQuality == null) {
            return null;
        }
        switch (signalQuality) {
            case GOOD:
                return getString(R.string.ecg_signal_quality_good);
            case NOISY:
                return getString(R.string.ecg_signal_quality_noisy);
            default:
                return getString(R.string.ecg_interpretation_inconclusive);
        }
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

    private List<EcgSample> smoothWaveformForDisplay(final EcgRecord summary, final List<EcgSample> waveform) {
        if (waveform.size() < 5) {
            return waveform;
        }

        final float[] values = prepareWaveformValues(summary, waveform);
        final float[] medianFiltered = applyMedianFilter(values, DISPLAY_MEDIAN_WINDOW);
        final float[] centered = subtractBaseline(medianFiltered, DISPLAY_BASELINE_WINDOW);
        final float[] despiked = suppressOutliers(centered, DISPLAY_OUTLIER_WINDOW);
        final float[] smoothed = applyMovingAverage(despiked, DISPLAY_AVERAGE_WINDOW);
        final float[] normalized = normalizeForDisplay(smoothed);

        final List<EcgSample> displayWaveform = new ArrayList<>(normalized.length);
        for (int i = 0; i < normalized.length; i++) {
            final int timeDeltaMs = getMappedTimeDeltaMs(waveform, normalized.length, i);
            displayWaveform.add(new EcgSample(timeDeltaMs, normalized[i]));
        }

        final List<EcgSample> resampled = resampleWaveformForDisplay(
                displayWaveform,
                watchStyleDisplayEnabled ? WATCH_STYLE_RESAMPLE_MS : DISPLAY_RESAMPLE_MS
        );

        if (!watchStyleDisplayEnabled || resampled.size() < 5) {
            return resampled;
        }

        final float[] watchValues = new float[resampled.size()];
        for (int i = 0; i < resampled.size(); i++) {
            watchValues[i] = resampled.get(i).getValue();
        }
        final float[] watchSmoothed = applyMovingAverage(watchValues, WATCH_STYLE_AVERAGE_WINDOW);
        final float[] watchShaped = softenBaselineForWatchStyle(watchSmoothed);
        final List<EcgSample> watchStyleWaveform = new ArrayList<>(resampled.size());
        for (int i = 0; i < resampled.size(); i++) {
            watchStyleWaveform.add(new EcgSample(resampled.get(i).getTimeDeltaMs(), watchShaped[i]));
        }
        return watchStyleWaveform;
    }

    private int getMappedTimeDeltaMs(final List<EcgSample> waveform, final int outputSize, final int index) {
        if (waveform.isEmpty()) {
            return index;
        }
        if (outputSize <= 1 || waveform.size() == 1) {
            return waveform.get(0).getTimeDeltaMs();
        }
        if (outputSize == waveform.size()) {
            return waveform.get(index).getTimeDeltaMs();
        }

        final float position = index * (waveform.size() - 1f) / (outputSize - 1f);
        final int mappedIndex = Math.min(waveform.size() - 1, Math.max(0, Math.round(position)));
        return waveform.get(mappedIndex).getTimeDeltaMs();
    }

    private float[] prepareWaveformValues(final EcgRecord summary, final List<EcgSample> waveform) {
        final float[] values = new float[waveform.size()];
        for (int i = 0; i < waveform.size(); i++) {
            values[i] = waveform.get(i).getValue();
        }

        if (summary != null && "withings".equals(summary.getSourceApp())) {
            return WithingsEcgWaveformUtil.decodeStoredValuesIfNeeded(values);
        }
        return values;
    }

    private float[] softenBaselineForWatchStyle(final float[] values) {
        if (values.length < 5) {
            return values;
        }

        final float[] absValues = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            absValues[i] = Math.abs(values[i]);
        }
        Arrays.sort(absValues);

        final float baselineThreshold = Math.max(0.03f, absValues[Math.min(absValues.length - 1, (int) (absValues.length * 0.65f))]);
        final float[] shaped = new float[values.length];

        for (int i = 0; i < values.length; i++) {
            final float value = values[i];
            final float abs = Math.abs(value);
            if (abs <= baselineThreshold) {
                shaped[i] = value * 0.35f;
            } else {
                final float excess = abs - baselineThreshold;
                final float preserved = baselineThreshold * 0.35f + (excess * 0.95f);
                shaped[i] = Math.signum(value) * preserved;
            }
        }

        return shaped;
    }

    private void updateSmoothingModeLabel() {
        if (smoothingModeToggleView != null) {
            smoothingModeToggleView.setText(watchStyleDisplayEnabled
                    ? R.string.ecg_smoothing_mode_watch_style
                    : R.string.ecg_smoothing_mode_precise);
        }
    }

    private List<EcgSample> resampleWaveformForDisplay(final List<EcgSample> waveform, final int bucketMs) {
        if (waveform.size() < 3 || bucketMs <= 1) {
            return waveform;
        }

        final List<EcgSample> resampled = new ArrayList<>();
        int bucketStart = waveform.get(0).getTimeDeltaMs();
        int lastTime = bucketStart;
        float sum = 0f;
        int count = 0;

        for (final EcgSample sample : waveform) {
            final int time = sample.getTimeDeltaMs();
            if (time - bucketStart >= bucketMs && count > 0) {
                final int midpoint = bucketStart + ((lastTime - bucketStart) / 2);
                resampled.add(new EcgSample(midpoint, sum / count));
                bucketStart = time;
                sum = 0f;
                count = 0;
            }

            sum += sample.getValue();
            count++;
            lastTime = time;
        }

        if (count > 0) {
            final int midpoint = bucketStart + ((lastTime - bucketStart) / 2);
            resampled.add(new EcgSample(midpoint, sum / count));
        }

        return resampled.size() >= 3 ? resampled : waveform;
    }

    private float[] applyMedianFilter(final float[] values, final int windowSize) {
        final float[] filtered = new float[values.length];
        final int halfWindow = windowSize / 2;

        for (int i = 0; i < values.length; i++) {
            final int start = Math.max(0, i - halfWindow);
            final int end = Math.min(values.length - 1, i + halfWindow);
            final float[] window = new float[end - start + 1];
            for (int j = start; j <= end; j++) {
                window[j - start] = values[j];
            }
            java.util.Arrays.sort(window);
            filtered[i] = window[window.length / 2];
        }

        return filtered;
    }

    private float[] applyMovingAverage(final float[] values, final int windowSize) {
        final float[] filtered = new float[values.length];
        final int halfWindow = windowSize / 2;

        for (int i = 0; i < values.length; i++) {
            final int start = Math.max(0, i - halfWindow);
            final int end = Math.min(values.length - 1, i + halfWindow);
            float sum = 0f;
            for (int j = start; j <= end; j++) {
                sum += values[j];
            }
            filtered[i] = sum / (end - start + 1);
        }

        return filtered;
    }

    private float[] subtractBaseline(final float[] values, final int windowSize) {
        final float[] baseline = applyMovingAverage(values, windowSize);
        final float[] flattened = new float[values.length];

        for (int i = 0; i < values.length; i++) {
            flattened[i] = values[i] - baseline[i];
        }

        return flattened;
    }

    private float[] suppressOutliers(final float[] values, final int windowSize) {
        final float[] filtered = new float[values.length];
        final int halfWindow = windowSize / 2;

        for (int i = 0; i < values.length; i++) {
            final int start = Math.max(0, i - halfWindow);
            final int end = Math.min(values.length - 1, i + halfWindow);
            final float[] window = new float[end - start + 1];
            for (int j = start; j <= end; j++) {
                window[j - start] = values[j];
            }
            Arrays.sort(window);

            final float median = window[window.length / 2];
            final float[] deviations = new float[window.length];
            for (int j = 0; j < window.length; j++) {
                deviations[j] = Math.abs(window[j] - median);
            }
            Arrays.sort(deviations);
            final float mad = deviations[deviations.length / 2];
            final float threshold = Math.max(2.5f, mad * 3f);

            filtered[i] = Math.abs(values[i] - median) > threshold ? median : values[i];
        }

        return filtered;
    }

    private float[] normalizeForDisplay(final float[] values) {
        final float[] absValues = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            absValues[i] = Math.abs(values[i]);
        }
        Arrays.sort(absValues);

        final int percentileIndex = Math.min(absValues.length - 1, Math.max(0, (int) (absValues.length * 0.95f)));
        final float referenceAmplitude = Math.max(1f, absValues[percentileIndex]);
        final float scale = DISPLAY_TARGET_AMPLITUDE / referenceAmplitude;

        final float[] normalized = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            final float scaled = values[i] * scale;
            normalized[i] = (float) (DISPLAY_MAX_AMPLITUDE * Math.tanh(scaled / DISPLAY_MAX_AMPLITUDE));
        }

        return normalized;
    }

    private void setupLineChart() {
        chart.setBackgroundColor(ECG_PAPER_BACKGROUND);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.setPinchZoom(false);
        chart.setScaleEnabled(true);
        chart.setScaleXEnabled(true);
        chart.setScaleYEnabled(false);
        chart.setNoDataText(getString(R.string.chart_no_data_synchronize));

        final XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(chartTextColor);
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(30f);
        xAxis.setLabelCount(6, true);
        xAxis.setGranularity(1f);
        xAxis.setDrawLimitLinesBehindData(true);

        final YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(false);
        leftAxis.setTextColor(chartTextColor);
        leftAxis.setAxisMinimum(-2f);
        leftAxis.setAxisMaximum(2f);
        leftAxis.setDrawLimitLinesBehindData(true);

        final YAxis rightAxis = chart.getAxisRight();
        rightAxis.setEnabled(true);
        rightAxis.setDrawLabels(false);
        rightAxis.setDrawGridLines(false);
        rightAxis.setDrawAxisLine(true);

        applyEcgGrid(0f, 30f, 2f);
    }

    private void applyEcgGrid(final float minX, final float maxX, final float halfRange) {
        final XAxis xAxis = chart.getXAxis();
        xAxis.removeAllLimitLines();
        final int minorPerMajorX = Math.round(ECG_MAJOR_X_GRID_SECONDS / ECG_MINOR_X_GRID_SECONDS);
        final int firstMinorIndex = (int) Math.floor(minX / ECG_MINOR_X_GRID_SECONDS);
        for (int i = firstMinorIndex; ; i++) {
            final float second = i * ECG_MINOR_X_GRID_SECONDS;
            if (second > maxX + 0.0001f) break;
            final boolean major = (i % minorPerMajorX) == 0;
            xAxis.addLimitLine(createGridLine(second, major ? ECG_MAJOR_GRID_COLOR : ECG_MINOR_GRID_COLOR, major ? 0.9f : 0.45f));
        }

        final YAxis leftAxis = chart.getAxisLeft();
        leftAxis.removeAllLimitLines();
        final int minorPerMajorY = Math.round(ECG_MAJOR_Y_GRID / ECG_MINOR_Y_GRID);
        for (int i = 0; ; i++) {
            final float value = i * ECG_MINOR_Y_GRID;
            if (value > halfRange + 0.0001f) break;
            final boolean major = (i % minorPerMajorY) == 0;
            leftAxis.addLimitLine(createGridLine(value, major ? ECG_MAJOR_GRID_COLOR : ECG_MINOR_GRID_COLOR, major ? 0.9f : 0.45f));
            if (i > 0) {
                leftAxis.addLimitLine(createGridLine(-value, major ? ECG_MAJOR_GRID_COLOR : ECG_MINOR_GRID_COLOR, major ? 0.9f : 0.45f));
            }
        }
    }

    private com.github.mikephil.charting.components.LimitLine createGridLine(final float value,
                                                                             final int color,
                                                                             final float width) {
        final com.github.mikephil.charting.components.LimitLine line = new com.github.mikephil.charting.components.LimitLine(value);
        line.setLineColor(color);
        line.setLineWidth(width);
        line.setLabel("");
        return line;
    }

    private void setupChartTouchHandling() {
        chart.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(final View v, final MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        getChartsHost().enableSwipeRefresh(scrollView != null && scrollView.getScrollY() == 0);
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        getChartsHost().enableSwipeRefresh(false);
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        final float dx = event.getX() - downX;
                        final float dy = event.getY() - downY;
                        final boolean chartGesture = event.getPointerCount() > 1 || Math.abs(dx) > Math.abs(dy);
                        final boolean atTop = scrollView != null && scrollView.getScrollY() == 0;
                        final boolean pullToRefreshGesture = atTop && dy > 0 && Math.abs(dy) > Math.abs(dx);
                        getChartsHost().enableSwipeRefresh(!chartGesture && atTop);
                        v.getParent().requestDisallowInterceptTouchEvent(chartGesture && !pullToRefreshGesture);
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        getChartsHost().enableSwipeRefresh(scrollView != null && scrollView.getScrollY() == 0);
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private void scrollToTop() {
        if (scrollView != null) {
            scrollView.post(() -> scrollView.smoothScrollTo(0, 0));
        }
    }

    private EcgRecord findSummaryByTimestamp(final List<EcgRecord> summaries,
                                             final long timestamp) {
        for (final EcgRecord summary : summaries) {
            if (summary.getStartTimestamp() == timestamp) {
                return summary;
            }
        }
        return null;
    }

    private int resolveThemeColor(final int attr) {
        final TypedValue typedValue = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
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
        private final List<EcgRecord> summaries;
        private final EcgRecord selectedSummary;
        private final List<EcgSample> waveform;
        private final EcgInterpretation interpretation;

        protected EcgChartData(final List<EcgRecord> summaries,
                               final EcgRecord selectedSummary,
                               final List<EcgSample> waveform,
                               final EcgInterpretation interpretation) {
            this.summaries = summaries;
            this.selectedSummary = selectedSummary;
            this.waveform = waveform;
            this.interpretation = interpretation;
        }
    }
}
