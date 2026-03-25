package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;

import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeDrawer;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;

public class StepsDailyFragment extends StepsFragment<StepsDailyFragment.StepsData> {
    protected static final Logger LOG = LoggerFactory.getLogger(StepsDailyFragment.class);

    private TextView mDateView;
    private ImageView stepsGauge;
    private TextView steps;
    private TextView distance;
    ImageView stepsStreaksButton;
    private LineChart stepsChart;

    protected int STEPS_GOAL;

    // Historical Data collection and averaging based heavily on the work done by trentsuzuki
    // for BodyEnergy
    protected int AVERAGE_LINE_COLOR;
    // Number of days to include in the average calculation
    private static final int DAYS_FOR_AVERAGE = 30;
    private static final int AVERAGE_BIN_SIZE_MINS = 60;

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_steps, container, false);

        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            getChartsHost().enableSwipeRefresh(scrollY == 0);
        });

        mDateView = rootView.findViewById(R.id.steps_date_view);
        stepsGauge = rootView.findViewById(R.id.steps_gauge);
        steps = rootView.findViewById(R.id.steps_count);
        distance = rootView.findViewById(R.id.steps_distance);
        stepsChart = rootView.findViewById(R.id.steps_daily_chart);
        AVERAGE_LINE_COLOR = Color.GRAY;
        setupStepsChart();

        STEPS_GOAL = GBApplication.getPrefs().getInt(ActivityUser.PREF_USER_STEPS_GOAL, ActivityUser.defaultUserStepsGoal);
        refresh();

        stepsStreaksButton = rootView.findViewById(R.id.steps_streaks_button);
        stepsStreaksButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FragmentManager fm = getActivity().getSupportFragmentManager();
                StepStreaksDashboard stepStreaksDashboard = StepStreaksDashboard.newInstance(STEPS_GOAL, getChartsHost().getDevice());
                stepStreaksDashboard.show(fm, "steps_streaks_dashboard");
            }
        });

        return rootView;
    }

    @Override
    public String getTitle() {
        return getString(R.string.steps);
    }

    @Override
    protected StepsDailyFragment.StepsData refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        Calendar day = Calendar.getInstance();
        day.setTime(chartsHost.getEndDate());
        List<StepsDay> stepsDayList = getMyStepsDaysData(db, day, device);
        final StepsDay stepsDay;
        if (stepsDayList.isEmpty()) {
            LOG.error("Failed to get StepsDay for {}", day);
            stepsDay = new StepsDay(day, 0, 0);
        } else {
            stepsDay = stepsDayList.get(0);
        }
        List<? extends ActivitySample> samplesOfDay = getSamplesOfDay(db, day, 0, device);
        List<List<? extends ActivitySample>> historicalData = GBApplication.getPrefs().getBoolean("charts_show_historic_average", true) ? getHistoricalStepsData(db, device, DAYS_FOR_AVERAGE) : new ArrayList<>();
        return new StepsDailyFragment.StepsData(stepsDay, samplesOfDay, historicalData);
    }

    @Override
    protected void updateChartsnUIThread(StepsDailyFragment.StepsData stepsData) {
        String formattedDate = new SimpleDateFormat("E, MMM dd").format(getEndDate());
        mDateView.setText(formattedDate);

        final int width = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                300,
                GBApplication.getContext().getResources().getDisplayMetrics()
        );

        stepsGauge.setImageBitmap(GaugeDrawer.drawCircleGauge(
                width,
                width / 15,
                getResources().getColor(R.color.steps_color),
                (int) stepsData.todayStepsDay.steps,
                STEPS_GOAL,
                getContext()
        ));

        steps.setText(String.format(String.valueOf(stepsData.todayStepsDay.steps)));

        final WorkoutValueFormatter valueFormatter = new WorkoutValueFormatter();
        distance.setText(valueFormatter.formatValue(stepsData.todayStepsDay.distance, "km"));

        List<Entry> averageLineEntries = new ArrayList<>();
        if (GBApplication.getPrefs().getBoolean("charts_show_average", true) && !stepsData.historicalSamples.isEmpty()) {
            averageLineEntries = buildAverageEntries(stepsData.historicalSamples, AVERAGE_BIN_SIZE_MINS);
        }

        // Chart
        final List<ILineDataSet> lineDataSets = new ArrayList<>();
        final List<LegendEntry> legendEntries = new ArrayList<>(1);
        final LegendEntry stepsEntry = new LegendEntry();
        stepsEntry.label = getString(R.string.steps);
        stepsEntry.formColor = getResources().getColor(R.color.steps_color);
        legendEntries.add(stepsEntry);
        stepsChart.getLegend().setTextColor(TEXT_COLOR);
        stepsChart.getLegend().setCustom(legendEntries);

        // taken from trentsuzuki's average implementation for body energy
        if (!averageLineEntries.isEmpty() && GBApplication.getPrefs().getBoolean("charts_show_average", true)) {
            final LineDataSet averageLineDataSet = new LineDataSet(averageLineEntries, getString(R.string.body_energy_legend_average));
            averageLineDataSet.setColor(AVERAGE_LINE_COLOR);
            averageLineDataSet.setLineWidth(1.5f);
            averageLineDataSet.setDrawCircles(false);
            averageLineDataSet.setDrawValues(false);
            averageLineDataSet.setDrawFilled(true);
            averageLineDataSet.setFillColor(AVERAGE_LINE_COLOR);
            averageLineDataSet.setFillAlpha(40);
            averageLineDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            averageLineDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            averageLineDataSet.setHighlightEnabled(false);

            // Add average line first so it appears underneath
            lineDataSets.add(averageLineDataSet);

            LegendEntry averageEntry = new LegendEntry();
            averageEntry.label = getString(R.string.body_energy_legend_average);
            averageEntry.formColor = AVERAGE_LINE_COLOR;
            legendEntries.add(averageEntry);
        }


        final List<Entry> lineEntries = new ArrayList<>();
        final TimestampTranslation tsTranslation = new TimestampTranslation();
        int sum = 0;
        for (final ActivitySample sample : stepsData.samples) {
            if (sample.getSteps() > 0) {
                sum += sample.getSteps();
            }
            lineEntries.add(new Entry(tsTranslation.shorten(sample.getTimestamp()), sum));
        }
        for (int i = 0; i < Math.min(10, lineEntries.size()); i++) {
            Entry thise = lineEntries.get(i);
            StepsFragment.LOG.warn("in display loop for live data: x = {}, y = {}", thise.getX(), thise.getY());
        }
        stepsChart.getXAxis().setValueFormatter(new SampleXLabelFormatter(tsTranslation, "HH:mm"));

        if (sum < STEPS_GOAL) {
            stepsChart.getAxisLeft().setAxisMaximum(STEPS_GOAL);
        } else {
            stepsChart.getAxisLeft().resetAxisMaximum();
        }

        final LineDataSet lineDataSet = new LineDataSet(lineEntries, getString(R.string.steps));
        lineDataSet.setColor(getResources().getColor(R.color.steps_color));
        lineDataSet.setDrawCircles(false);
        lineDataSet.setLineWidth(2f);
        lineDataSet.setFillAlpha(255);
        lineDataSet.setDrawCircles(false);
        lineDataSet.setCircleColor(getResources().getColor(R.color.steps_color));
        lineDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineDataSet.setDrawValues(false);
        lineDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        lineDataSet.setDrawFilled(true);
        lineDataSet.setFillAlpha(60);
        lineDataSet.setFillColor(getResources().getColor(R.color.steps_color));

        final LimitLine goalLine = new LimitLine(STEPS_GOAL);
        goalLine.setLineColor(getResources().getColor(R.color.steps_color));
        goalLine.setLineWidth(1.5f);
        goalLine.enableDashedLine(15f, 10f, 0f);
        stepsChart.getAxisLeft().removeAllLimitLines();
        stepsChart.getAxisLeft().addLimitLine(goalLine);
        stepsChart.getAxisLeft().setAxisMaximum(Math.max(lineDataSet.getYMax(), STEPS_GOAL) + 2000);

        lineDataSets.add(lineDataSet);
        final LineData lineData = new LineData(lineDataSets);
        stepsChart.setData(lineData);
    }

    /**
     * Get historical steps data for the specified number of days
     * copied from trentsuzuki's work in BodyEnergyFragment.java and adapted to Steps
     */
    private List<List<? extends ActivitySample>> getHistoricalStepsData(final DBHandler db, final GBDevice device, int daysCount) {
        List<List<? extends ActivitySample>> historicalData = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(getTSEnd() * 1000L);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);

        // Go back one more day to exclude today
        cal.add(Calendar.DAY_OF_YEAR, -1);

        for (int i = 0; i < daysCount; i++) {
            List<? extends ActivitySample> daySamples = getSamplesOfDay(db, cal, 0, device);
            StepsFragment.LOG.debug("daySamples for {}/{}/{} has {} entries", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH), cal.get(Calendar.YEAR), daySamples.size());
            historicalData.add(daySamples);

            // Move to the previous day
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }

        return historicalData;
    }

    /**
     * Build a binned average body energy curve with an arbitrary bin size.
     * copied from trentsuzuki's implementation for BodyEnergy and adjusted
     * to also contain resampling to account for uneven distribution of
     * step samples over the day
     *
     * @param historicDays   list of samples maps (timestamp -> energy)
     * @param binSizeMinutes width of a bin in minutes (must divide 24 hours evenly)
     * @return MPAndroidChart entries (x = seconds from local midnight, y = avg body energy)
     */
    private List<Entry> buildAverageEntries(List<List<? extends ActivitySample>> historicDays, int binSizeMinutes) {
        if (binSizeMinutes <= 0 || 24 * 60 % binSizeMinutes != 0) {
            throw new IllegalArgumentException("binSizeMinutes must be a positive divisor of 24 hours");
        }

        final int binSizeSeconds = binSizeMinutes * 60;
        final int binsPerDay = 24 * 60 * 60 / binSizeSeconds;

        // last bin is for end of day step count
        long[] sum = new long[binsPerDay + 1];
        int[] count = new int[binsPerDay + 1];

        // Sum steps in bins over the provided history
        // only adds final values in time slots to bins
        for (List<? extends ActivitySample> day : historicDays) {
            long dailyAccum = 0;
            int oldbin = 0;
            if (day.isEmpty()) {
                for (int bin = 0; bin <= binsPerDay; bin++) {
                    count[bin]++;
                }
                continue;
            }
            long discarded = 0;

            // fix zero point of time translation to refer to start of day
            // so the things land in the right bin
            Calendar cal = Calendar.getInstance();
            long origTS = day.get(0).getTimestamp();
            cal.setTimeInMillis(origTS * 1000);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            long calTS = cal.getTimeInMillis() / 1000;
            TimestampTranslation tsTranslation = new TimestampTranslation();
            tsTranslation.shorten((int) calTS);
            StepsFragment.LOG.debug("processing day {}/{}/{}, offset is {}, {} in translation", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR), calTS, tsTranslation.toOriginalValue(0));
            for (ActivitySample sample : day) {
                long ts = tsTranslation.shorten(sample.getTimestamp());
                int bin = (int) ((ts / binSizeSeconds) % binsPerDay);
                if (bin != oldbin) {
                    if (bin < oldbin) {
                        StepsFragment.LOG.error("The Bin index for averaging wrapped around. " + "This should not happen! date is {}/{}/{}", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
                        while (oldbin <= binsPerDay) {
                            StepsFragment.LOG.debug("adding {} steps to bin {} after wrapping", dailyAccum, oldbin);
                            sum[oldbin] += dailyAccum;
                            count[oldbin]++;
                            oldbin++;
                        }
                        oldbin = 0;
                        dailyAccum = 0;
                    }
                    while (oldbin < bin) {
                        StepsFragment.LOG.debug("adding {} steps to bin {}", dailyAccum, oldbin);
                        sum[oldbin] += dailyAccum;
                        count[oldbin]++;
                        oldbin++;
                    }
                }
                if (sample.getSteps() >= 0) {
                    dailyAccum += sample.getSteps();
                    oldbin = bin;
                } else {
                    discarded += 1;
                }
            }
            // add final step count to all other bins as well
            while (oldbin <= binsPerDay) {
                StepsFragment.LOG.debug("adding {} steps to bin {} at end of day", dailyAccum, oldbin);
                sum[oldbin] += dailyAccum;
                count[oldbin]++;
                oldbin++;
            }
            StepsFragment.LOG.debug("discarded {} samples due to no steps, {} steps total", discarded, dailyAccum);
        }

        // Calculate the average in each bin and make plot entries
        List<Entry> avgEntries = new ArrayList<>(binsPerDay);
        // at start of day there are no steps
        avgEntries.add(new Entry(0.f, 0.f));
        for (int bin = 0; bin < binsPerDay; bin++) {
            if (count[bin] != 0) {
                float x = bin * (float) binSizeSeconds + (float) binSizeSeconds / 2.f; // seconds from local midnight
                float avg = (float) sum[bin] / count[bin];
                avgEntries.add(new Entry(x, avg));
            }
        }

        // Add one extra entry to make the graph end at the total number of steps of the day
        float x = binsPerDay * (float) binSizeSeconds;
        float avg = (float) sum[binsPerDay] / count[binsPerDay];
        avgEntries.add(new Entry(x, avg));

        for (int i = 0; i < avgEntries.size(); i++) {
            Entry thise = avgEntries.get(i);
            StepsFragment.LOG.debug("in building average: x = {}, y = {}", thise.getX(), thise.getY());
            int binIndex = i - 1;
            if (binIndex >= 0) {
                StepsFragment.LOG.debug("bin {} has {} entries with a sum of {}", binIndex, count[binIndex], sum[binIndex]);
            }
        }
        return avgEntries;
    }

    @Override
    protected void renderCharts() {
        stepsChart.invalidate();
    }

    @Override
    protected void setupLegend(Chart<?> chart) {}

    private void setupStepsChart() {
        stepsChart.getDescription().setEnabled(false);
        stepsChart.setDoubleTapToZoomEnabled(false);

        final XAxis xAxisBottom = stepsChart.getXAxis();
        xAxisBottom.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxisBottom.setDrawLabels(true);
        xAxisBottom.setDrawGridLines(false);
        xAxisBottom.setEnabled(true);
        xAxisBottom.setDrawLimitLinesBehindData(true);
        xAxisBottom.setTextColor(CHART_TEXT_COLOR);
        xAxisBottom.setAxisMinimum(0f);
        xAxisBottom.setAxisMaximum(86400f);
        //xAxisBottom.setLabelCount(7, true);

        final YAxis yAxisLeft = stepsChart.getAxisLeft();
        yAxisLeft.setDrawGridLines(true);
        yAxisLeft.setAxisMinimum(0);
        yAxisLeft.setDrawTopYLabelEntry(true);
        yAxisLeft.setEnabled(true);
        yAxisLeft.setTextColor(CHART_TEXT_COLOR);

        final YAxis yAxisRight = stepsChart.getAxisRight();
        yAxisRight.setEnabled(true);
        yAxisRight.setDrawLabels(false);
        yAxisRight.setDrawGridLines(false);
        yAxisRight.setDrawAxisLine(true);
    }

    protected static class StepsData extends ChartsData {
        StepsDay todayStepsDay;
        List<? extends ActivitySample> samples;
        List<List<? extends ActivitySample>> historicalSamples;

        public StepsData(final StepsDay todayStepsDay, final List<? extends ActivitySample> samplesOfDay, final List<List<? extends ActivitySample>> historicalData) {
            this.todayStepsDay = todayStepsDay;
            this.samples = samplesOfDay;
            this.historicalSamples = historicalData;
        }
    }
}
