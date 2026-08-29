package nodomain.freeyourgadget.gadgetbridge.util;

import java.util.Calendar;
import java.util.GregorianCalendar;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;

public final class SleepRangeUtils {
    private static final String PREF_KEY = "chart_sleep_range_mode";
    private static final String DEFAULT_VALUE = "18:00";

    private SleepRangeUtils() {
    }

    public enum SleepRangeMode {
        ROLLING_24H,
        NOON,
        EVENING,
    }

    public static SleepRangeMode getMode() {
        final String value = GBApplication.getPrefs().getString(PREF_KEY, DEFAULT_VALUE);
        if ("24h".equals(value)) {
            return SleepRangeMode.ROLLING_24H;
        } else if ("12:00".equals(value)) {
            return SleepRangeMode.NOON;
        } else {
            return SleepRangeMode.EVENING;
        }
    }

    /**
     * Returns the 24-hour sleep window {@code {from, to}} (epoch seconds, to exclusive) ending at
     * anchorTs, per the current {@code chart_sleep_range_mode} preference.
     */
    public static int[] getSleepRange(final int anchorTs) {
        return getSleepRange(anchorTs, getMode());
    }

    public static int[] getSleepRange(final int anchorTs, final SleepRangeMode mode) {
        final int to = switch (mode) {
            case NOON -> snapToHour(anchorTs, 12);
            case EVENING -> snapToHour(anchorTs, 18);
            default -> anchorTs;
        };
        return new int[]{to - 24 * 60 * 60, to};
    }

    private static int snapToHour(final int ts, final int hourOfDay) {
        final Calendar calendar = new GregorianCalendar();
        calendar.setTimeInMillis(ts * 1000L);
        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return (int) (calendar.getTimeInMillis() / 1000L);
    }
}
