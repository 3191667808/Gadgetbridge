package nodomain.freeyourgadget.gadgetbridge.util;

import android.util.Pair;

import java.util.Calendar;

public class WorkoutFilterUtils {

    public static Pair<Long, Long> getDateRangeForFilter(String filterSelection) {
        if (filterSelection == null || filterSelection.equals("noselection")) {
            return null;
        }

        Calendar date = Calendar.getInstance();
        date.set(Calendar.HOUR_OF_DAY, 0);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        long firstdate;
        long lastdate;

        switch (filterSelection) {
            case "today":
                firstdate = date.getTimeInMillis();
                lastdate = Calendar.getInstance().getTimeInMillis();
                break;
            case "thisweek":
                date.set(Calendar.DAY_OF_WEEK, date.getFirstDayOfWeek());
                firstdate = date.getTimeInMillis();
                lastdate = Calendar.getInstance().getTimeInMillis();
                break;
            case "thismonth":
                date.set(Calendar.DAY_OF_MONTH, 1);
                firstdate = date.getTimeInMillis();
                lastdate = Calendar.getInstance().getTimeInMillis();
                break;
            case "lastweek":
                int i = date.get(Calendar.DAY_OF_WEEK) - date.getFirstDayOfWeek();
                date.add(Calendar.DATE, -i - 7);
                firstdate = date.getTimeInMillis();
                date.add(Calendar.DATE, 6);
                lastdate = date.getTimeInMillis();
                break;
            case "lastmonth":
                date.set(Calendar.DATE, 1);
                date.add(Calendar.DAY_OF_MONTH, -1);
                lastdate = date.getTimeInMillis();
                date.set(Calendar.DATE, 1);
                firstdate = date.getTimeInMillis();
                break;
            case "7days":
                date.add(Calendar.DATE, -7);
                firstdate = date.getTimeInMillis();
                lastdate = Calendar.getInstance().getTimeInMillis();
                break;
            case "30days":
                date.add(Calendar.DATE, -30);
                firstdate = date.getTimeInMillis();
                lastdate = Calendar.getInstance().getTimeInMillis();
                break;
            case "distantpast":
                firstdate = 0L;
                lastdate = 0L;
                break;
            default:
                return null;
        }

        return new Pair<>(firstdate, lastdate);
    }
}
