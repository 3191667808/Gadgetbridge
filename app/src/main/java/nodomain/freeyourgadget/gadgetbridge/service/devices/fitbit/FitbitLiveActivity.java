/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.proto.fitbit.FitbitMobileDataProto;

final class FitbitLiveActivity {
    private static final int MILLIMETERS_PER_CENTIMETER = 10;

    private FitbitLiveActivity() {
    }

    static int timestampSeconds(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasTimestamp() ? liveActivity.getTimestamp() : ActivitySample.NOT_MEASURED;
    }

    static int steps(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasSteps() ? liveActivity.getSteps() : ActivitySample.NOT_MEASURED;
    }

    static int distanceCentimeters(final FitbitMobileDataProto.LiveActivity liveActivity) {
        if (!liveActivity.hasDistance()) {
            return ActivitySample.NOT_MEASURED;
        }

        return liveActivity.getDistance() / MILLIMETERS_PER_CENTIMETER;
    }

    static int calories(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasCalories() ? liveActivity.getCalories() : ActivitySample.NOT_MEASURED;
    }

    static int heartRate(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasHeartRate() ? liveActivity.getHeartRate() : ActivitySample.NOT_MEASURED;
    }

    static int heartRateConfidence(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasHeartRateConfidence()
                ? liveActivity.getHeartRateConfidence()
                : ActivitySample.NOT_MEASURED;
    }

    static int elevation(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasElevation() ? liveActivity.getElevation() : ActivitySample.NOT_MEASURED;
    }

    static int vaMinutes(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasVaMinutes() ? liveActivity.getVaMinutes() : ActivitySample.NOT_MEASURED;
    }

    static int dailyZoneMinutes(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasDailyZoneMinutes()
                ? liveActivity.getDailyZoneMinutes()
                : ActivitySample.NOT_MEASURED;
    }

    static int weeklyZoneMinutes(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return liveActivity.hasWeeklyZoneMinutes()
                ? liveActivity.getWeeklyZoneMinutes()
                : ActivitySample.NOT_MEASURED;
    }

    static String describe(final FitbitMobileDataProto.LiveActivity liveActivity) {
        return "timestamp=" + fieldValue(liveActivity.hasTimestamp(), liveActivity.getTimestamp())
                + ", steps=" + fieldValue(liveActivity.hasSteps(), liveActivity.getSteps())
                + ", distanceMm=" + fieldValue(liveActivity.hasDistance(), liveActivity.getDistance())
                + ", distanceCm=" + fieldValue(liveActivity.hasDistance(), distanceCentimeters(liveActivity))
                + ", calories=" + fieldValue(liveActivity.hasCalories(), liveActivity.getCalories())
                + ", elevation=" + fieldValue(liveActivity.hasElevation(), liveActivity.getElevation())
                + ", vaMinutes=" + fieldValue(liveActivity.hasVaMinutes(), liveActivity.getVaMinutes())
                + ", heartRate=" + fieldValue(liveActivity.hasHeartRate(), liveActivity.getHeartRate())
                + ", heartRateConfidence=" + fieldValue(liveActivity.hasHeartRateConfidence(), liveActivity.getHeartRateConfidence())
                + ", dailyZoneMinutes=" + fieldValue(liveActivity.hasDailyZoneMinutes(), liveActivity.getDailyZoneMinutes())
                + ", weeklyZoneMinutes=" + fieldValue(liveActivity.hasWeeklyZoneMinutes(), liveActivity.getWeeklyZoneMinutes());
    }

    private static String fieldValue(final boolean hasValue, final int value) {
        return hasValue ? Integer.toString(value) : "(missing)";
    }
}
