/*  Copyright (C) 2024 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures;

/**
 * Known screen IDs used in the Withings protocol SET_SCREEN_LIST command (message type 1292).
 *
 * <p>Each constant represents the {@code id} field sent inside a {@link ScreenSettings} structure
 * (bytes 2-3 of the 18-byte TYPE_SCREEN_LIST payload, i.e. the lower 16 bits of the 4-byte int).
 * The {@code idOnDevice} field (slot/position) is a separate fixed value that determines the
 * display order on the watch (ascending slot = earlier in the list).
 *
 * <p><b>ScanWatch screen IDs</b> were confirmed from {@code reorder_screens.zip}: a BLE capture
 * of the Withings HealthMate app sending CMD_SCREEN_LIST_SET (0x050C) with all 13 screens enabled
 * in the order: data, sleep, ecg, elevation, heart rate, spo2, calories, settings, workouts,
 * distance, steps, breathe, clock. Sorting the 13 TYPE_SCREEN_LIST entries by ascending slot
 * number maps 1-to-1 onto that order, giving confirmed ID <-> name <-> slot bindings.
 *
 * <p><b>Steel HR screen IDs</b> come from the original Gadgetbridge implementation and have not
 * been reverified against a fresh packet capture.
 */
public final class WithingsScreenId {

    private WithingsScreenId() {}

    // -----------------------------------------------------------------------
    // Screens - Withings Steel HR (source: original GB implementation)
    // -----------------------------------------------------------------------

    /** Notification screen. */
    public static final int NOTIFICATIONS = 0xFF;

    /** Heart rate (BPM) screen. */
    public static final int HEART_RATE_STEEL = 0x3D;

    /** Activity / step counter screen. */
    public static final int ACTIVITY = 0x33;

    /** Calories screen. */
    public static final int CALORIES_STEEL = 0x2D;

    /** Alarm/timer screen. */
    public static final int ALARM = 0x2A;

    /** Config / battery info screen. */
    public static final int CONFIG = 0x26;

    /** Chronometer / countdown screen. */
    public static final int CHRONOMETER = 0x39;

    // -----------------------------------------------------------------------
    // Screens - Withings ScanWatch (confirmed from reorder_screens.zip capture)
    // Fixed slot numbers are the watch's internal position values; display order
    // follows ascending slot number.
    // -----------------------------------------------------------------------

    /** Date / activity data screen. Screen ID 0x007f, fixed slot 1. */
    public static final int DATE = 0x007f;

    /** Sleep screen. Screen ID 0x0080, fixed slot 2. */
    public static final int SLEEP = 0x0080;

    /** ECG screen. Screen ID 0x0081, fixed slot 3. */
    public static final int ECG = 0x0081;

    /** Elevation screen. Screen ID 0x0082, fixed slot 4. */
    public static final int ELEVATION = 0x0082;

    /** Heart rate screen. Screen ID 0x0084, fixed slot 6. */
    public static final int HEART_RATE = 0x0084;

    /** SpO2 / blood oxygen screen. Screen ID 0x008a, fixed slot 9. */
    public static final int SPO2 = 0x008a;

    /** Calories screen. Screen ID 0x008b, fixed slot 10. */
    public static final int CALORIES = 0x008b;

    /** Settings screen. Screen ID 0x0089, fixed slot 11. */
    public static final int SETTINGS = 0x0089;

    /** Workouts screen. Screen ID 0x008c, fixed slot 12. */
    public static final int WORKOUTS = 0x008c;

    /** Distance screen. Screen ID 0x0096, fixed slot 16. */
    public static final int DISTANCE = 0x0096;

    /** Steps screen. Screen ID 0x0097, fixed slot 17. */
    public static final int STEPS = 0x0097;

    /** Breathe / breathing exercises screen. Screen ID 0x00a1, fixed slot 18. */
    public static final int BREATHE = 0x00a1;

    /** Clock (alarms, stopwatch, timer) screen. Screen ID 0x0160, fixed slot 22. */
    public static final int CLOCK = 0x0160;

    /**
     * Returns the fixed slot number for a given ScanWatch screen ID.
     * Slot numbers are the watch's internal position values; display order follows ascending slot.
     *
     * @param screenId one of the ScanWatch screen ID constants in this class
     * @return the fixed slot byte, or -1 if the screen ID is not a known ScanWatch screen
     */
    public static byte getScanwatchSlot(int screenId) {
        switch (screenId) {
            case DATE:      return 1;
            case SLEEP:     return 2;
            case ECG:       return 3;
            case ELEVATION: return 4;
            case HEART_RATE: return 6;
            case SPO2:      return 9;
            case CALORIES:  return 10;
            case SETTINGS:  return 11;
            case WORKOUTS:  return 12;
            case DISTANCE:  return 16;
            case STEPS:     return 17;
            case BREATHE:   return 18;
            case CLOCK:     return 22;
            default:        return -1;
        }
    }
}
