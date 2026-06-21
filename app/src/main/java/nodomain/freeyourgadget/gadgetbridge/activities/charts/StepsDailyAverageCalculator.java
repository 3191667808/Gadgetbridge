package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import com.github.mikephil.charting.data.Entry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

final class StepsDailyAverageCalculator {
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private StepsDailyAverageCalculator() {
    }

    static List<Entry> buildAverageEntries(final List<? extends ActivitySample> historicalSamples,
                                           final int historyStart,
                                           final int daysCount,
                                           final int binSizeMinutes) {
        return buildAverageEntries(historicalSamples, historyStart, daysCount, binSizeMinutes, TimeZone.getDefault());
    }

    static List<Entry> buildAverageEntries(final List<? extends ActivitySample> historicalSamples,
                                           final int historyStart,
                                           final int daysCount,
                                           final int binSizeMinutes,
                                           final TimeZone timeZone) {
        if (binSizeMinutes <= 0 || 24 * 60 % binSizeMinutes != 0) {
            throw new IllegalArgumentException("binSizeMinutes must be a positive divisor of 24 hours");
        }

        if (daysCount <= 0 || historicalSamples.isEmpty()) {
            return Collections.emptyList();
        }

        final int binSizeSeconds = binSizeMinutes * 60;
        final int binsPerDay = SECONDS_PER_DAY / binSizeSeconds;
        final long[] sum = new long[binsPerDay + 1];
        final boolean[] dayHasSteps = new boolean[daysCount];
        final long historyStartLocalSec = toLocalSecond(historyStart, timeZone);

        int currentDay = 0;
        int currentBin = 0;
        long dailyAccum = 0;

        final List<? extends ActivitySample> orderedSamples = new ArrayList<>(historicalSamples);
        orderedSamples.sort(Comparator.comparingInt(ActivitySample::getTimestamp));

        for (final ActivitySample sample : orderedSamples) {
            final long timestamp = sample.getTimestamp();
            final long localSec = toLocalSecond(timestamp, timeZone);
            final int dayIndex = (int) ((localSec - historyStartLocalSec) / SECONDS_PER_DAY);

            if (dayIndex < 0) {
                continue;
            }
            if (dayIndex >= daysCount) {
                break;
            }

            while (currentDay < dayIndex) {
                addAverageDayBins(sum, currentBin, binsPerDay, dailyAccum);
                currentDay++;
                currentBin = 0;
                dailyAccum = 0;
            }

            final int secondsIntoDay = (int) Math.floorMod(localSec, (long) SECONDS_PER_DAY);
            final int bin = Math.min(secondsIntoDay / binSizeSeconds, binsPerDay - 1);
            while (currentBin < bin) {
                sum[currentBin] += dailyAccum;
                currentBin++;
            }

            final int steps = sample.getSteps();
            if (steps >= 0) {
                dayHasSteps[dayIndex] = true;
            }
            if (steps > 0) {
                dailyAccum += steps;
            }
        }

        addAverageDayBins(sum, currentBin, binsPerDay, dailyAccum);

        int daysWithSteps = 0;
        for (final boolean hasSteps : dayHasSteps) {
            if (hasSteps) {
                daysWithSteps++;
            }
        }
        if (daysWithSteps == 0) {
            return Collections.emptyList();
        }

        final List<Entry> avgEntries = new ArrayList<>(binsPerDay + 2);
        avgEntries.add(new Entry(0f, 0f));
        for (int bin = 0; bin < binsPerDay; bin++) {
            final float x = bin * (float) binSizeSeconds + (float) binSizeSeconds / 2f;
            final float avg = (float) sum[bin] / daysWithSteps;
            avgEntries.add(new Entry(x, avg));
        }

        final float x = binsPerDay * (float) binSizeSeconds;
        final float avg = (float) sum[binsPerDay] / daysWithSteps;
        avgEntries.add(new Entry(x, avg));

        return avgEntries;
    }

    private static long toLocalSecond(final long timestamp, final TimeZone timeZone) {
        return timestamp + timeZone.getOffset(timestamp * 1000L) / 1000L;
    }

    private static void addAverageDayBins(final long[] sum,
                                          final int fromBin,
                                          final int binsPerDay,
                                          final long dailyAccum) {
        for (int bin = fromBin; bin <= binsPerDay; bin++) {
            sum[bin] += dailyAccum;
        }
    }
}
