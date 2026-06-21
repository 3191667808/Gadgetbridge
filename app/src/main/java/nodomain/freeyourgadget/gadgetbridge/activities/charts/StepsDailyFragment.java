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
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineDataSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeDrawer;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
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
    protected int AVERAGE_LINE_COLOR;

    private static final int DAYS_FOR_AVERAGE = 30;
    private static final int AVERAGE_BIN_SIZE_MINS = 60;

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    protected void init() {
        super.init();
        AVERAGE_LINE_COLOR = Color.GRAY;
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
        final int dayStartTimestamp = getDayStartTimestamp(day);
        final List<Entry> averageLineEntries = shouldShowAverage()
                ? getHistoricalAverageStepsData(db, device, day, DAYS_FOR_AVERAGE, AVERAGE_BIN_SIZE_MINS)
                : Collections.emptyList();
        return new StepsDailyFragment.StepsData(stepsDay, samplesOfDay, averageLineEntries, dayStartTimestamp);
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

        steps.setText(NumberFormat.getInstance().format(stepsData.todayStepsDay.steps));

        final WorkoutValueFormatter valueFormatter = new WorkoutValueFormatter();
        distance.setText(valueFormatter.formatValue(stepsData.todayStepsDay.distance, "km"));

        final List<Entry> lineEntries = new ArrayList<>();
        final TimestampTranslation tsTranslation = new TimestampTranslation();
        tsTranslation.shorten(stepsData.dayStartTimestamp);
        int sum = 0;
        for (final ActivitySample sample : stepsData.samples) {
            if (sample.getSteps() > 0) {
                sum += sample.getSteps();
            }
            lineEntries.add(new Entry(tsTranslation.shorten(sample.getTimestamp()), sum));
        }

        final int stepsColor = getResources().getColor(R.color.steps_color);
        final boolean showAverageLine = shouldShowAverage() && !stepsData.averageLineEntries.isEmpty();
        final List<LineDataSet> backgroundDataSets = new ArrayList<>(showAverageLine ? 1 : 0);

        float axisMaximum = Math.max(DailyCumulativeLineChartHelper.maxY(lineEntries), STEPS_GOAL);
        if (showAverageLine) {
            final String averageLabel = getString(R.string.body_energy_legend_average);
            final LineDataSet averageLineDataSet = new LineDataSet(stepsData.averageLineEntries, averageLabel);
            averageLineDataSet.setColor(AVERAGE_LINE_COLOR);
            averageLineDataSet.setLineWidth(1.5f);
            averageLineDataSet.setDrawCircles(false);
            averageLineDataSet.setDrawValues(false);
            averageLineDataSet.setDrawFilled(true);
            averageLineDataSet.setFillColor(AVERAGE_LINE_COLOR);
            averageLineDataSet.setFillAlpha(40);
            averageLineDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            averageLineDataSet.setMode(LineDataSet.Mode.LINEAR);
            averageLineDataSet.setHighlightEnabled(false);
            backgroundDataSets.add(averageLineDataSet);

            axisMaximum = Math.max(axisMaximum, averageLineDataSet.getYMax());
        }

        DailyCumulativeLineChartHelper.setCumulativeData(
                stepsChart,
                lineEntries,
                new SampleXLabelFormatter(tsTranslation, "HH:mm"),
                getString(R.string.steps),
                stepsColor,
                TEXT_COLOR,
                STEPS_GOAL,
                axisMaximum + 2000,
                backgroundDataSets
        );
    }

    private List<Entry> getHistoricalAverageStepsData(final DBHandler db,
                                                      final GBDevice device,
                                                      final Calendar day,
                                                      final int daysCount,
                                                      final int binSizeMinutes) {
        if (daysCount <= 0) {
            return Collections.emptyList();
        }

        final Calendar historyDay = getDayStart(day);

        final int tsTo = (int) (historyDay.getTimeInMillis() / 1000L) - 1;
        historyDay.add(Calendar.DAY_OF_YEAR, -daysCount);
        final int tsFrom = (int) (historyDay.getTimeInMillis() / 1000L);

        if (tsTo < tsFrom) {
            return Collections.emptyList();
        }

        final SampleProvider<? extends ActivitySample> provider = getSampleProvider(db, device);
        if (!provider.supportsFastStepsQuery()) {
            LOG.debug("Skipping historical steps average for {} because the sample provider does not support fast step queries", device);
            return Collections.emptyList();
        }

        return StepsDailyAverageCalculator.buildAverageEntries(
                provider.getFastStepsSamples(tsFrom, tsTo),
                tsFrom,
                daysCount,
                binSizeMinutes
        );
    }

    private boolean shouldShowAverage() {
        return GBApplication.getPrefs().getBoolean("charts_show_average", true);
    }

    private SampleProvider<? extends ActivitySample> getSampleProvider(final DBHandler db, final GBDevice device) {
        return device.getDeviceCoordinator().getSampleProvider(device, db.getDaoSession());
    }

    private static int getDayStartTimestamp(final Calendar day) {
        return (int) (getDayStart(day).getTimeInMillis() / 1000L);
    }

    private static Calendar getDayStart(final Calendar day) {
        final Calendar dayStart = (Calendar) day.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);
        return dayStart;
    }

    @Override
    protected void renderCharts() {
        stepsChart.invalidate();
    }

    @Override
    protected void setupLegend(Chart<?> chart) {}

    private void setupStepsChart() {
        DailyCumulativeLineChartHelper.setup(stepsChart, CHART_TEXT_COLOR);
    }

    protected static class StepsData extends ChartsData {
        StepsDay todayStepsDay;
        List<? extends ActivitySample> samples;
        List<Entry> averageLineEntries;
        int dayStartTimestamp;

        public StepsData(final StepsDay todayStepsDay,
                         final List<? extends ActivitySample> samplesOfDay,
                         final List<Entry> averageLineEntries,
                         final int dayStartTimestamp) {
            this.todayStepsDay = todayStepsDay;
            this.samples = samplesOfDay;
            this.averageLineEntries = averageLineEntries;
            this.dayStartTimestamp = dayStartTimestamp;
        }
    }
}
