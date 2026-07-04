/*  Copyright (C) 2017-2024 Andreas Shimokawa, Carsten Pfeiffer, José Rebelo,
    Pavel Elagin, Petr Vaněk, a0z

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

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.ChartData;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentWeeksleepChartBinding;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.SleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepRange;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepSessionService;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepSessionService.SleepTotals;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepTimeline;
import nodomain.freeyourgadget.gadgetbridge.util.Accumulator;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

public class SleepPeriodFragment extends SleepFragment<SleepPeriodFragment.MyChartsData> {
    protected static final Logger LOG = LoggerFactory.getLogger(SleepPeriodFragment.class);

    protected int TOTAL_DAYS = getRangeDays();
    protected int TOTAL_DAYS_FOR_AVERAGE = 0;
    private MySleepWeeklyData mySleepWeeklyData;

    private FragmentWeeksleepChartBinding binding;
    protected Locale mLocale;
    protected int mTargetValue = 0;

    private final int mCutOffHour = GBApplication.getPrefs().getString("chart_sleep_range_mode", "18:00").equals("18:00") ? 18 : 12;

    protected boolean SHOW_BALANCE;

    public static SleepPeriodFragment newInstance(int totalDays) {
        SleepPeriodFragment fragmentFirst = new SleepPeriodFragment();
        Bundle args = new Bundle();
        args.putInt("totalDays", totalDays);
        fragmentFirst.setArguments(args);
        return fragmentFirst;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TOTAL_DAYS = getArguments() != null ? getArguments().getInt("totalDays") : 0;
    }

    private PeriodSleepData getPeriodSleepData(DBHandler db, Calendar day, GBDevice device) {
        day = (Calendar) day.clone(); // do not modify the caller's argument
        day.add(Calendar.DATE, -TOTAL_DAYS + 1);

        final List<SleepRange> ranges = new ArrayList<>();
        for (int counter = 0; counter < TOTAL_DAYS; counter++) {
            ranges.add(SleepRange.forDayEndingAtCutoff(day, mCutOffHour));
            day.add(Calendar.DATE, 1);
        }
        if (ranges.isEmpty()) {
            return new PeriodSleepData(new ArrayList<>(), new MySleepWeeklyData(0, 0, 0, 0, 0), false);
        }

        final SleepRange firstRange = ranges.get(0);
        final SleepRange lastRange = ranges.get(ranges.size() - 1);
        final List<? extends ActivitySample> rawSamples = getSamples(db, device,
                firstRange.getSampleQueryStartTs(),
                lastRange.getSampleQueryEndTs());
        final SleepTimeline periodTimeline = SleepSessionService.getTimeline(
                db.getDaoSession(),
                device,
                rawSamples,
                firstRange.getIntervalStartTs(),
                lastRange.getIntervalEndTs()
        );

        long awakeWeeklyTotal = 0;
        long remWeeklyTotal = 0;
        long deepWeeklyTotal = 0;
        long lightWeeklyTotal = 0;
        int totalDaysForAverage = 0;

        final List<SleepTotals> totalsByDay = new ArrayList<>(ranges.size());
        for (SleepRange range : ranges) {
            final SleepTotals totals = SleepSessionService.calculateTotals(
                    periodTimeline.getSessions(),
                    range.getIntervalStartTs(),
                    range.getIntervalEndTs());
            totalsByDay.add(totals);
            if (calculateBalance(totals) > 0) {
                totalDaysForAverage++;
            }

            float[] totalAmounts = getTotalsForSleepTotals(totals);
            int i = 0;
            deepWeeklyTotal += (long) totalAmounts[i++];
            lightWeeklyTotal += (long) totalAmounts[i++];
            remWeeklyTotal += (long) totalAmounts[i++];
            awakeWeeklyTotal += (long) totalAmounts[i++];
        }

        return new PeriodSleepData(
                totalsByDay,
                new MySleepWeeklyData(awakeWeeklyTotal, remWeeklyTotal, deepWeeklyTotal, lightWeeklyTotal, totalDaysForAverage),
                periodTimeline.hasCorrections()
        );
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mLocale = getResources().getConfiguration().locale;
        binding = FragmentWeeksleepChartBinding.inflate(inflater, container, false);

        View rootView = binding.getRoot();
        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            getChartsHost().enableSwipeRefresh(scrollY == 0);
        });

        final int goal = getGoal();
        if (goal >= 0) {
            mTargetValue = goal;
        }

        SHOW_BALANCE = GBApplication.getPrefs().getBoolean("charts_show_balance_sleep", true);
        if (SHOW_BALANCE) {
            binding.balance.setVisibility(View.VISIBLE);
        } else {
            binding.balance.setVisibility(View.GONE);
        }

        if (!supportsSleepScore()) {
            binding.sleepScoreWrapper.setVisibility(View.GONE);
        } else {
            setupSleepScoreChart();
            setupSleepScoreLegend();
        }

        setupWeekChart();
        // refresh immediately instead of use refreshIfVisible(), for perceived performance
        refresh();

        return rootView;
    }

    protected void setupWeekChart() {
        BarChart weekSleepChart = binding.weekSleepChart;
        weekSleepChart.setBackgroundColor(BACKGROUND_COLOR);
        weekSleepChart.getDescription().setTextColor(DESCRIPTION_COLOR);
        weekSleepChart.getDescription().setText("");
        weekSleepChart.setFitBars(true);

        configureBarLineChartDefaults(weekSleepChart);

        XAxis x = weekSleepChart.getXAxis();
        x.setDrawLabels(true);
        x.setDrawGridLines(false);
        x.setEnabled(true);
        x.setTextColor(CHART_TEXT_COLOR);
        x.setDrawLimitLinesBehindData(true);
        x.setPosition(XAxis.XAxisPosition.BOTTOM);

        YAxis y = weekSleepChart.getAxisLeft();
        y.setDrawGridLines(false);
        y.setDrawTopYLabelEntry(false);
        y.setTextColor(CHART_TEXT_COLOR);
        y.setDrawZeroLine(true);
        y.setSpaceBottom(0);
        y.setAxisMinimum(0);
        y.setValueFormatter(getYAxisFormatter());
        y.setEnabled(true);

        YAxis yAxisRight = weekSleepChart.getAxisRight();
        yAxisRight.setDrawGridLines(false);
        yAxisRight.setEnabled(false);
        yAxisRight.setDrawLabels(false);
        yAxisRight.setDrawTopYLabelEntry(false);
        yAxisRight.setTextColor(CHART_TEXT_COLOR);

        if (TOTAL_DAYS > 7) {
            weekSleepChart.setRenderer(new AngledLabelsChartRenderer(weekSleepChart, weekSleepChart.getAnimator(), weekSleepChart.getViewPortHandler()));
        } else {
            weekSleepChart.setScaleEnabled(false);
            weekSleepChart.setTouchEnabled(false);
        }
    }

    @Override
    protected void updateChartsnUIThread(MyChartsData mcd) {
        BarChart weekSleepChart = binding.weekSleepChart;
        setupLegend(weekSleepChart);

        WeekChartsData<BarData> weekBeforeData = mcd.getWeekBeforeData();
        weekSleepChart.setData(null); // workaround for https://github.com/PhilJay/MPAndroidChart/issues/2317
        weekSleepChart.setData(weekBeforeData.getData());
        weekSleepChart.getXAxis().setValueFormatter(mcd.getWeekBeforeData().getXValueFormatter());
        weekSleepChart.getBarData().setValueTextSize(10f);
        weekSleepChart.getBarData().setValueTextColor(LEGEND_TEXT_COLOR);

        if (supportsSleepScore()) {
            binding.sleepScoreChart.setData(null);
            binding.sleepScoreChart.getXAxis().setValueFormatter(weekBeforeData.getXValueFormatter());
            binding.sleepScoreChart.getLegend().setTextColor(LEGEND_TEXT_COLOR);
            binding.sleepScoreChart.setData(weekBeforeData.getSleepScoreData());
            binding.sleepScoreHighest.setText(weekBeforeData.getHighestSleepScore() > 0 ? String.valueOf(weekBeforeData.getHighestSleepScore()) : getString(R.string.stats_empty_value));
            binding.sleepScoreLowest.setText(weekBeforeData.getLowestSleepScore() > 0 ? String.valueOf(weekBeforeData.getLowestSleepScore()) : getString(R.string.stats_empty_value));
            binding.sleepScoreAverage.setText(weekBeforeData.getAvgSleepScore() > 0 ? String.valueOf(weekBeforeData.getAvgSleepScore()) : getString(R.string.stats_empty_value));
        }

        // The last value is for awake time, which we do not want to include in the "total sleep time"
        final int barIgnoreLast = 1;
        weekSleepChart.getBarData().setValueFormatter(new BarChartStackedTimeValueFormatter(false, "", 0, barIgnoreLast));

        if (TOTAL_DAYS_FOR_AVERAGE > 0) {
            float avgDeep = Math.abs(this.mySleepWeeklyData.getTotalDeep() / TOTAL_DAYS_FOR_AVERAGE);
            binding.sleepChartLegendDeepTime.setText(DateTimeUtils.formatDurationHoursMinutes((int) avgDeep, TimeUnit.MINUTES));
            float avgLight = Math.abs(this.mySleepWeeklyData.getTotalLight() / TOTAL_DAYS_FOR_AVERAGE);
            binding.sleepChartLegendLightTime.setText(DateTimeUtils.formatDurationHoursMinutes((int) avgLight, TimeUnit.MINUTES));
            float avgRem = Math.abs(this.mySleepWeeklyData.getTotalRem() / TOTAL_DAYS_FOR_AVERAGE);
            binding.sleepChartLegendRemTime.setText(DateTimeUtils.formatDurationHoursMinutes((int) avgRem, TimeUnit.MINUTES));
            float avgAwake = Math.abs(this.mySleepWeeklyData.getTotalAwake() / TOTAL_DAYS_FOR_AVERAGE);
            binding.sleepChartLegendAwakeTime.setText(DateTimeUtils.formatDurationHoursMinutes((int) avgAwake, TimeUnit.MINUTES));
        } else {
            binding.sleepChartLegendDeepTime.setText("-");
            binding.sleepChartLegendLightTime.setText("-");
            binding.sleepChartLegendRemTime.setText("-");
            binding.sleepChartLegendAwakeTime.setText("-");
        }

        if (!supportsRemSleep(getChartsHost().getDevice()) && mySleepWeeklyData.getTotalRem() <= 0) {
            binding.sleepChartLegendRemTimeWrapper.setVisibility(View.GONE);
        } else {
            binding.sleepChartLegendRemTimeWrapper.setVisibility(View.VISIBLE);
        }

        if (!supportsAwakeSleep(getChartsHost().getDevice()) && mySleepWeeklyData.getTotalAwake() <= 0) {
            binding.sleepChartLegendAwakeTimeWrapper.setVisibility(View.GONE);
        } else {
            binding.sleepChartLegendAwakeTimeWrapper.setVisibility(View.VISIBLE);
        }

        binding.sleepDates.setText(DateTimeUtils.formatDaysUntil(TOTAL_DAYS, getTSEnd()));
        binding.sleepEditedIndicator.setVisibility(mcd.hasEditedSessions() ? View.VISIBLE : View.GONE);

        binding.balance.setText(mcd.getWeekBeforeData().getBalanceMessage());
    }

    @Override
    protected MyChartsData refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        Calendar day = Calendar.getInstance();
        day.setTime(chartsHost.getEndDate());
        //NB: we could have omitted the day, but this way we can move things to the past easily
        final PeriodSleepData periodSleepData = getPeriodSleepData(db, day, device);
        mySleepWeeklyData = periodSleepData.getWeeklyData();
        TOTAL_DAYS_FOR_AVERAGE = mySleepWeeklyData.getTotalDaysForAverage();
        WeekChartsData<BarData> weekBeforeData = refreshWeekBeforeData(db, binding.weekSleepChart, day, device, periodSleepData);

        return new MyChartsData(weekBeforeData, periodSleepData.hasEditedSessions());
    }

    protected WeekChartsData<BarData> refreshWeekBeforeData(DBHandler db, BarChart barChart, Calendar day, GBDevice device, PeriodSleepData periodSleepData) {
        day = (Calendar) day.clone(); // do not modify the caller's argument
        day.add(Calendar.DATE, -TOTAL_DAYS + 1);
        List<BarEntry> entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<String>();

        long balance = 0;
        long daily_balance = 0;
        List<Entry> sleepScoreEntities = new ArrayList<>();
        final Accumulator sleepScoreAccumulator = new Accumulator();
        final List<ILineDataSet> sleepScoreDataSets = new ArrayList<>();
        for (int counter = 0; counter < TOTAL_DAYS; counter++) {
            // Sleep stages
            final SleepTotals totals = periodSleepData.getTotals(counter);
            daily_balance = calculateBalance(totals);
            balance += daily_balance;
            entries.add(new BarEntry(counter, getTotalsForSleepTotals(totals)));
            labels.add(getWeeksChartsLabel(day));
            // Sleep score
            if (supportsSleepScore()) {
                List<? extends SleepScoreSample> sleepScoreSamples = getSleepScoreSamples(db, device, day);
                if (!sleepScoreSamples.isEmpty() && sleepScoreSamples.get(sleepScoreSamples.size() - 1).getSleepScore() > 0) {
                    int sleepScore = sleepScoreSamples.get(sleepScoreSamples.size() - 1).getSleepScore();
                    sleepScoreAccumulator.add(sleepScore);
                    sleepScoreEntities.add(new Entry(counter, sleepScore));
                } else {
                    if (!sleepScoreEntities.isEmpty()) {
                        List<Entry> clone = new ArrayList<>(sleepScoreEntities.size());
                        clone.addAll(sleepScoreEntities);
                        sleepScoreDataSets.add(createSleepScoreDataSet(clone));
                        sleepScoreEntities.clear();
                    }
                }
            }
            day.add(Calendar.DATE, 1);
        }
        if (!sleepScoreEntities.isEmpty()) {
            sleepScoreDataSets.add(createSleepScoreDataSet(sleepScoreEntities));
        }
        final LineData sleepScoreLineData = new LineData(sleepScoreDataSets);
        sleepScoreLineData.setHighlightEnabled(false);

        BarDataSet set = new BarDataSet(entries, "");
        set.setColors(getColors());
        set.setValueFormatter(getBarValueFormatter());

        BarData barData = new BarData(set);
        barData.setValueTextColor(Color.GRAY); //prevent tearing other graph elements with the black text. Another approach would be to hide the values completely with data.setDrawValues(false);
        barData.setValueTextSize(10f);

        barChart.getAxisLeft().setAxisMaximum(Math.max(set.getYMax(), mTargetValue) + 60);

        LimitLine target = new LimitLine(mTargetValue);
        target.setLineWidth(1.5f);
        target.enableDashedLine(15f, 10f, 0f);
        target.setLineColor(getResources().getColor(R.color.chart_deep_sleep_dark));
        barChart.getAxisLeft().removeAllLimitLines();
        barChart.getAxisLeft().addLimitLine(target);

        float average = 0;
        if (TOTAL_DAYS_FOR_AVERAGE > 0) {
            average = Math.abs(balance / TOTAL_DAYS_FOR_AVERAGE);
        }
        LimitLine average_line = new LimitLine(average);
        average_line.setLineWidth(1.5f);
        average_line.enableDashedLine(15f, 10f, 0f);
        average_line.setLabel(getString(R.string.average, getAverage(average)));

        if (average > (mTargetValue)) {
            average_line.setLineColor(Color.GREEN);
            average_line.setTextColor(Color.GREEN);
        } else {
            average_line.setLineColor(Color.RED);
            average_line.setTextColor(Color.RED);
        }
        if (average > 0) {
            if (GBApplication.getPrefs().getBoolean("charts_show_average", true)) {
                barChart.getAxisLeft().addLimitLine(average_line);
            }
        }

        if (supportsSleepScore()) {
            return new WeekChartsData(
                    barData,
                    new PreformattedXIndexLabelFormatter(labels),
                    getBalanceMessage(balance, mTargetValue),
                    sleepScoreLineData,
                    (int) Math.round(sleepScoreAccumulator.getAverage()),
                    (int) Math.round(sleepScoreAccumulator.getMax()),
                    (int) Math.round(sleepScoreAccumulator.getMin())
            );
        }
        return new WeekChartsData(barData, new PreformattedXIndexLabelFormatter(labels), getBalanceMessage(balance, mTargetValue));
    }

    protected String getWeeksChartsLabel(Calendar day) {
        if (TOTAL_DAYS > 7) {
            //month, show day date
            return String.valueOf(day.get(Calendar.DAY_OF_MONTH));
        } else {
            //week, show short day name
            return day.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, mLocale);
        }
    }

    protected LineDataSet createSleepScoreDataSet(final List<Entry> values) {
        final LineDataSet lineDataSet = new LineDataSet(values, getString(R.string.sleep_score));
        lineDataSet.setColor(getResources().getColor(R.color.chart_light_sleep_light));
        lineDataSet.setLineWidth(2f);
        lineDataSet.setFillAlpha(255);
        lineDataSet.setCircleRadius(5f);
        lineDataSet.setDrawCircles(true);
        lineDataSet.setDrawCircleHole(false);
        lineDataSet.setCircleColor(getResources().getColor(R.color.chart_light_sleep_light));
        lineDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineDataSet.setDrawValues(true);
        lineDataSet.setValueTextSize(10f);
        lineDataSet.setValueTextColor(LEGEND_TEXT_COLOR);
        lineDataSet.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.ROOT, "%d", (int) value);
            }
        });
        return lineDataSet;
    }

    private void setupSleepScoreChart() {
        final XAxis xAxisBottom = binding.sleepScoreChart.getXAxis();
        xAxisBottom.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxisBottom.setDrawLabels(true);
        xAxisBottom.setDrawGridLines(false);
        xAxisBottom.setEnabled(true);
        xAxisBottom.setDrawLimitLinesBehindData(true);
        xAxisBottom.setTextColor(CHART_TEXT_COLOR);
        xAxisBottom.setAxisMinimum(0f);
        xAxisBottom.setAxisMaximum(TOTAL_DAYS - 1);
        xAxisBottom.setGranularity(1f);
        xAxisBottom.setGranularityEnabled(true);

        final YAxis yAxisLeft = binding.sleepScoreChart.getAxisLeft();
        yAxisLeft.setDrawGridLines(true);
        yAxisLeft.setAxisMaximum(100);
        yAxisLeft.setAxisMinimum(0);
        yAxisLeft.setDrawTopYLabelEntry(true);
        yAxisLeft.setEnabled(true);
        yAxisLeft.setTextColor(CHART_TEXT_COLOR);

        final YAxis yAxisRight = binding.sleepScoreChart.getAxisRight();
        yAxisRight.setEnabled(true);
        yAxisRight.setDrawLabels(false);
        yAxisRight.setDrawGridLines(false);
        yAxisRight.setDrawAxisLine(true);

        binding.sleepScoreChart.setDoubleTapToZoomEnabled(false);
        binding.sleepScoreChart.getDescription().setEnabled(false);
        binding.sleepScoreChart.getDescription().setTextColor(LEGEND_TEXT_COLOR);
        if (TOTAL_DAYS <= 7) {
            binding.sleepScoreChart.setScaleEnabled(false);
            binding.sleepScoreChart.setTouchEnabled(false);
        }
    }

    protected void setupSleepScoreLegend() {
        List<LegendEntry> legendEntries = new ArrayList<>(1);
        LegendEntry sleepScoreValue = new LegendEntry();
        sleepScoreValue.label = getString(R.string.sleep_score);
        sleepScoreValue.formColor = getResources().getColor(R.color.chart_light_sleep_light);
        legendEntries.add(sleepScoreValue);
        binding.sleepScoreChart.getLegend().setCustom(legendEntries);
        binding.sleepScoreChart.getLegend().setTextColor(LEGEND_TEXT_COLOR);
        binding.sleepScoreChart.getLegend().setWordWrapEnabled(true);
        binding.sleepScoreChart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
    }

    @Override
    protected void renderCharts() {
        binding.weekSleepChart.invalidate();
        binding.sleepScoreChart.invalidate();
    }

    @Override
    public String getTitle() {
        if (GBApplication.getPrefs().getBoolean("charts_range", true)) {
            return getString(R.string.weeksleepchart_sleep_a_month);
        } else {
            return getString(R.string.weeksleepchart_sleep_a_week);
        }
    }

    String getPieDescription(int targetValue) {
        return getString(R.string.weeksleepchart_today_sleep_description, DateTimeUtils.formatDurationHoursMinutes(targetValue, TimeUnit.MINUTES));
    }

    int getGoal() {
        return GBApplication.getPrefs().getInt(ActivityUser.PREF_USER_SLEEP_DURATION_MINUTES, ActivityUser.defaultUserSleepDurationGoal);
    }

    int getOffsetHours() {
        return GBApplication.getPrefs().getString("chart_sleep_range_mode", "18:00").equals("18:00") ? -18 : -12;
    }


    protected long calculateBalance(final SleepTotals totals) {
        return totals.getTotalSleepSeconds() / 60;
    }

    protected String getBalanceMessage(long balance, int targetValue) {
        if (balance > 0) {
            final long totalBalance = balance - ((long) targetValue * TOTAL_DAYS_FOR_AVERAGE);
            if (totalBalance > 0)
                return getString(R.string.overslept, getHM(totalBalance));
            else
                return getString(R.string.lack_of_sleep, getHM(Math.abs(totalBalance)));
        } else
            return getString(R.string.no_data);
    }

    float[] getTotalsForSleepTotals(final SleepTotals totals) {
        final int totalMinutesDeepSleep = (int) (totals.getDeepSleepSeconds() / 60);
        final int totalMinutesLightSleep = (int) (totals.getLightSleepSeconds() / 60);
        final int totalMinutesRemSleep = (int) (totals.getRemSleepSeconds() / 60);
        final int totalMinutesAwakeSleep = (int) (totals.getAwakeSleepSeconds() / 60);

        return new float[]{totalMinutesDeepSleep, totalMinutesLightSleep, totalMinutesRemSleep, totalMinutesAwakeSleep};
    }

    protected String formatPieValue(long value) {
        return DateTimeUtils.formatDurationHoursMinutes(value, TimeUnit.MINUTES);
    }

    String[] getPieLabels() {
        return new String[]{
                getString(R.string.abstract_chart_fragment_kind_deep_sleep),
                getString(R.string.abstract_chart_fragment_kind_light_sleep),
                getString(R.string.abstract_chart_fragment_kind_rem_sleep),
                getString(R.string.abstract_chart_fragment_kind_awake_sleep)
        };
    }

    ValueFormatter getPieValueFormatter() {
        return new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return formatPieValue((long) value);
            }
        };
    }

    ValueFormatter getBarValueFormatter() {
        return new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return DateTimeUtils.minutesToHHMM((int) value);
            }
        };
    }

    ValueFormatter getYAxisFormatter() {
        return new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return DateTimeUtils.minutesToHHMM((int) value);
            }
        };
    }

    int[] getColors() {
        return new int[]{akDeepSleep.color, akLightSleep.color, akRemSleep.color, akAwakeSleep.color};
    }

    @Override
    protected void setupLegend(Chart<?> chart) {
        List<LegendEntry> legendEntries = super.createLegendEntries(chart);
        chart.getLegend().setCustom(legendEntries);

        chart.getLegend().setTextColor(LEGEND_TEXT_COLOR);
        chart.getLegend().setWordWrapEnabled(true);
        chart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
    }

    private String getHM(long value) {
        return DateTimeUtils.formatDurationHoursMinutes(value, TimeUnit.MINUTES);
    }

    String getAverage(float value) {
        return getHM((long) value);
    }

    protected static class MyChartsData extends ChartsData {
        private final WeekChartsData<BarData> weekBeforeData;
        private final boolean hasEditedSessions;

        MyChartsData(WeekChartsData<BarData> weekBeforeData, boolean hasEditedSessions) {
            this.weekBeforeData = weekBeforeData;
            this.hasEditedSessions = hasEditedSessions;
        }

        WeekChartsData<BarData> getWeekBeforeData() {
            return weekBeforeData;
        }

        boolean hasEditedSessions() {
            return hasEditedSessions;
        }
    }

    private int getRangeDays() {
        if (GBApplication.getPrefs().getBoolean("charts_range", true)) {
            return 30;
        } else {
            return 7;
        }
    }

    protected class WeekChartsData<T extends ChartData<?>> extends DefaultChartsData<T> {
        private final String balanceMessage;
        private LineData sleepScoresLineData;
        private int avgSleepScore;
        private int highestSleepScore;
        private int lowestSleepScore;

        public WeekChartsData(T data, PreformattedXIndexLabelFormatter xIndexLabelFormatter, String balanceMessage) {
            super(data, xIndexLabelFormatter);
            this.balanceMessage = balanceMessage;
        }

        public WeekChartsData(T data, PreformattedXIndexLabelFormatter xIndexLabelFormatter, String balanceMessage, LineData sleepScores, int avgSleepScore, int highestSleepScore, int lowestSleepScore) {
            super(data, xIndexLabelFormatter);
            this.balanceMessage = balanceMessage;
            this.sleepScoresLineData = sleepScores;
            this.avgSleepScore = avgSleepScore;
            this.highestSleepScore = highestSleepScore;
            this.lowestSleepScore = lowestSleepScore;
        }

        public int getHighestSleepScore() {
            return highestSleepScore;
        }

        public int getLowestSleepScore() {
            return lowestSleepScore;
        }

        public int getAvgSleepScore() {
            return avgSleepScore;
        }

        public String getBalanceMessage() {
            return balanceMessage;
        }

        public LineData getSleepScoreData() {
            return sleepScoresLineData;
        }
    }

    private static class MySleepWeeklyData {
        private final long totalAwake;
        private final long totalRem;
        private final long totalDeep;
        private final long totalLight;
        private final int totalDaysForAverage;

        public MySleepWeeklyData(long totalAwake, long totalRem, long totalDeep, long totalLight, int totalDaysForAverage) {
            this.totalDeep = totalDeep;
            this.totalRem = totalRem;
            this.totalAwake = totalAwake;
            this.totalLight = totalLight;
            this.totalDaysForAverage = totalDaysForAverage;
        }

        public long getTotalAwake() {
            return this.totalAwake;
        }

        public long getTotalRem() {
            return this.totalRem;
        }

        public long getTotalDeep() {
            return this.totalDeep;
        }

        public long getTotalLight() {
            return this.totalLight;
        }

        public int getTotalDaysForAverage() {
            return this.totalDaysForAverage;
        }
    }

    protected static class PeriodSleepData {
        private final List<SleepTotals> totalsByDay;
        private final MySleepWeeklyData weeklyData;
        private final boolean hasEditedSessions;

        PeriodSleepData(final List<SleepTotals> totalsByDay,
                        final MySleepWeeklyData weeklyData,
                        final boolean hasEditedSessions) {
            this.totalsByDay = totalsByDay;
            this.weeklyData = weeklyData;
            this.hasEditedSessions = hasEditedSessions;
        }

        SleepTotals getTotals(final int index) {
            return totalsByDay.get(index);
        }

        MySleepWeeklyData getWeeklyData() {
            return weeklyData;
        }

        boolean hasEditedSessions() {
            return hasEditedSessions;
        }
    }

}
