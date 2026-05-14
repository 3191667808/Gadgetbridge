/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.devices.oura;

public final class OuraConstants {
    // Stored cursor is the ring's internal boot-counter seconds, NOT unix wall-clock.
    // Key was renamed from "pref_oura_last_event_ts" (which previously stored wall-clock
    // seconds in error) so any pre-fix value is auto-discarded — a stale wall-clock cursor
    // is larger than any boot-counter and would make the ring return received=0 forever.
    public static final String PREF_OURA_LAST_EVENT_TS = "pref_oura_last_event_boot";

    public static final String PREF_OURA_DAYTIME_HR = "pref_oura_daytime_hr";
    public static final String PREF_OURA_RESTING_HR = "pref_oura_resting_hr";
    public static final String PREF_OURA_EXERCISE_HR = "pref_oura_exercise_hr";
    public static final String PREF_OURA_REAL_STEPS = "pref_oura_real_steps";
    public static final String PREF_OURA_TAP_TO_TAG = "pref_oura_tap_to_tag";
    public static final String PREF_OURA_CHARGING_CONTROL = "pref_oura_charging_control";

    public static final int SLEEP_STAGE_AWAKE = 1;
    public static final int SLEEP_STAGE_LIGHT = 2;
    public static final int SLEEP_STAGE_DEEP = 3;
    public static final int SLEEP_STAGE_REM = 4;

    public static final String AUTH_HELP_URL = "https://gadgetbridge.org/basics/pairing/oura-ring/";

    private OuraConstants() {
    }
}
