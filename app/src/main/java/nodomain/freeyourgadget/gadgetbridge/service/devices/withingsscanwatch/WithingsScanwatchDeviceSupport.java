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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingsscanwatch;

import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.withingsscanwatch.WithingsScanwatchSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractWithingsActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.WithingsUUIDs;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.GetShortcutHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.GetGlanceHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.GetLuminosityHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.GetMoveHandsHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.GetWearPosHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.EndOfTransmission;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.FeatureTagDeprecated;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.FeatureTagsUserId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.GlanceStatus;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.LocalNotification;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.LuminosityLevel;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ScreenSettings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ShortcutAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.TrackerMoveHands;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.TrackerWearPos;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WithingsScreenId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessageType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;

import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.IconHelper;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.activity.WithingsActivityType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ImageData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ImageMetaData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WorkoutScreen;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.ExpectedResponse;

/**
 * Device support for Withings Scanwatch. The Scanwatch uses the same BLE protocol as the
 * Steel HR but with a different service UUID suffix (005d vs 0037).
 * All protocol logic is in {@link WithingsBaseDeviceSupport}.
 */
public class WithingsScanwatchDeviceSupport extends WithingsBaseDeviceSupport {

    private static final Logger logger = LoggerFactory.getLogger(WithingsScanwatchDeviceSupport.class);

    static final String PREF_SCREENS_SORTABLE = "withings_scanwatch_screens_sortable";
    private static final String PREF_SCREENS_LAST_SENT = "withings_scanwatch_screens_last_sent";

    @Override
    protected WithingsUUIDs getWithingsUUIDs() {
        return WithingsUUIDs.SCANWATCH;
    }

    @Override
    public AbstractSampleProvider<? extends AbstractWithingsActivitySample> createSampleProvider(GBDevice device, DaoSession session) {
        return new WithingsScanwatchSampleProvider(device, session);
    }

    private static final String PREF_SHORTCUT_ACTION = "withings_scanwatch_shortcut_action";

    /** Whether the ECG measurement feature is enabled on the watch. */
    static final String PREF_ECG_ENABLED        = "withings_scanwatch_ecg_enabled";
    /** Respiratory scan mode: "off", "automatic" (some nights), or "always" (every night). */
    static final String PREF_RESPIRATORY_SCAN   = "withings_scanwatch_respiratory_scan";
    /** Whether daytime AFib detection is enabled on the watch. */
    static final String PREF_AFIB_DAY_ENABLED   = "withings_scanwatch_afib_day_enabled";
    /** Whether night-time AFib detection is enabled on the watch. */
    static final String PREF_AFIB_NIGHT_ENABLED = "withings_scanwatch_afib_night_enabled";
    /** Whether quicklook / glance (raise-to-wake) is enabled on the watch. */
    static final String PREF_QUICKLOOK = "withings_scanwatch_quicklook";
    /** Whether auto-brightness is enabled on the watch. */
    static final String PREF_AUTO_BRIGHTNESS = "withings_scanwatch_auto_brightness";
    /** Manual brightness level (0-100) when auto-brightness is off. */
    static final String PREF_BRIGHTNESS_LEVEL = "withings_scanwatch_brightness_level";
    /** Whether tracker move-hands (move hands to 10:10 when screen turns on) is enabled. */
    static final String PREF_MOVE_HANDS = "withings_scanwatch_move_hands";

    @Override
    protected void addExtraSyncCommands() {
        addSimpleConversationToQueue(
                new WithingsMessage(WithingsMessageType.GET_SHORTCUT),
                new GetShortcutHandler(this)
        );
        addSimpleConversationToQueue(
                new WithingsMessage(WithingsMessageType.GLANCE_GET),
                new GetGlanceHandler(this)
        );
        addSimpleConversationToQueue(
                new WithingsMessage(WithingsMessageType.GET_LUMINOSITY_LEVEL),
                new GetLuminosityHandler(this)
        );
        addSimpleConversationToQueue(
                new WithingsMessage(WithingsMessageType.GET_TRACKER_MOVE_HANDS),
                new GetMoveHandsHandler(this)
        );
        addSimpleConversationToQueue(
                new WithingsMessage(WithingsMessageType.GET_TRACKER_WEAR_POS),
                new GetWearPosHandler(this)
        );

        // Re-push feature tags and local notifications on every sync so the watch
        // retains the correct state after a Bluetooth reconnect or reboot.
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
        final boolean ecg       = prefs.getBoolean(PREF_ECG_ENABLED,        false);
        final String  respScan  = prefs.getString(PREF_RESPIRATORY_SCAN,    "off");
        final boolean afibDay   = prefs.getBoolean(PREF_AFIB_DAY_ENABLED,   false);
        final boolean afibNight = prefs.getBoolean(PREF_AFIB_NIGHT_ENABLED, false);
        addFeatureTagsCommand(ecg, respScan, afibDay, afibNight);
        addLocalNotificationsCommand(afibDay, afibNight);
    }

    @Override
    protected boolean handleExtraConfiguration(String config) {
        if (PREF_SCREENS_SORTABLE.equals(config)) {
            clearQueue();
            addScreenListCommands();
            sendQueue();
            return true;
        }
        if (PREF_SHORTCUT_ACTION.equals(config)) {
            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
            final String val = prefs.getString(PREF_SHORTCUT_ACTION, "0");
            final byte action;
            try {
                action = Byte.parseByte(val);
            } catch (NumberFormatException e) {
                logger.warn("Invalid shortcut action value: {}", val);
                return true;
            }
            final WithingsMessage msg = new WithingsMessage(WithingsMessageType.SET_SHORTCUT);
            msg.addDataStructure(new ShortcutAction(action));
            clearQueue();
            addSimpleConversationToQueue(msg);
            sendQueue();
            return true;
        }
        if (PREF_ECG_ENABLED.equals(config) || PREF_RESPIRATORY_SCAN.equals(config)
                || PREF_AFIB_DAY_ENABLED.equals(config) || PREF_AFIB_NIGHT_ENABLED.equals(config)) {
            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
            final boolean ecg       = prefs.getBoolean(PREF_ECG_ENABLED,        false);
            final String  respScan  = prefs.getString(PREF_RESPIRATORY_SCAN,    "off");
            final boolean afibDay   = prefs.getBoolean(PREF_AFIB_DAY_ENABLED,   false);
            final boolean afibNight = prefs.getBoolean(PREF_AFIB_NIGHT_ENABLED, false);
            clearQueue();
            addFeatureTagsCommand(ecg, respScan, afibDay, afibNight);
            addLocalNotificationsCommand(afibDay, afibNight);
            sendQueue();
            return true;
        }
        if (PREF_QUICKLOOK.equals(config)) {
            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
            final boolean enabled = prefs.getBoolean(PREF_QUICKLOOK, false);
            final WithingsMessage msg = new WithingsMessage(WithingsMessageType.GLANCE_SET,
                    new GlanceStatus(enabled));
            clearQueue();
            addSimpleConversationToQueue(msg);
            sendQueue();
            return true;
        }
        if (PREF_AUTO_BRIGHTNESS.equals(config) || PREF_BRIGHTNESS_LEVEL.equals(config)) {
            sendLuminosityLevel();
            return true;
        }
        if (PREF_MOVE_HANDS.equals(config)) {
            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
            final boolean enabled = prefs.getBoolean(PREF_MOVE_HANDS, false);
            final WithingsMessage msg = new WithingsMessage(WithingsMessageType.SET_TRACKER_MOVE_HANDS,
                    new TrackerMoveHands(enabled));
            clearQueue();
            addSimpleConversationToQueue(msg);
            sendQueue();
            return true;
        }
        if (DeviceSettingsPreferenceConst.PREF_WEARLOCATION.equals(config)) {
            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
            final String location = prefs.getString(DeviceSettingsPreferenceConst.PREF_WEARLOCATION, "left");
            final byte pos = "left".equals(location) ? TrackerWearPos.POS_LEFT_WRIST : TrackerWearPos.POS_RIGHT_WRIST;
            final WithingsMessage msg = new WithingsMessage(WithingsMessageType.SET_TRACKER_WEAR_POS,
                    new TrackerWearPos(pos));
            clearQueue();
            addSimpleConversationToQueue(msg);
            sendQueue();
            return true;
        }
        return false;
    }

    /**
     * Reads the current auto-brightness / brightness-level preferences and sends the
     * corresponding {@code CMD_SET_LUMINOSITY_LEVEL} (0x0941) command to the watch.
     */
    private void sendLuminosityLevel() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
        final boolean autoMode = prefs.getBoolean(PREF_AUTO_BRIGHTNESS, true);
        final byte mode;
        final byte level;
        if (autoMode) {
            mode = LuminosityLevel.MODE_AUTO;
            level = 0;
        } else {
            mode = LuminosityLevel.MODE_MANUAL;
            int rawLevel;
            try {
                rawLevel = Integer.parseInt(prefs.getString(PREF_BRIGHTNESS_LEVEL, "50"));
            } catch (NumberFormatException e) {
                rawLevel = 50;
            }
            level = (byte) Math.max(0, Math.min(100, rawLevel));
        }
        final WithingsMessage msg = new WithingsMessage(WithingsMessageType.SET_LUMINOSITY_LEVEL,
                new LuminosityLevel(mode, level));
        clearQueue();
        addSimpleConversationToQueue(msg);
        sendQueue();
    }

    /**
     * Queues a {@code CMD_FEATURE_TAGS_SET_DEPRECATED_V2} (0x0987) message activating the
     * feature tags required for ECG, respiratory scan, and/or AFib.
     *
     * <p>Tag set from HCI captures:
     * <ul>
     *   <li>ECG on: tags 0x0004, 0x000F, 0x0035, 0x0058</li>
     *   <li>Respiratory automatic: tag 0x0005 + 0x000F, 0x0035, 0x0058</li>
     *   <li>Respiratory always: tags 0x000E, 0x0011 + 0x000F, 0x0035, 0x0058</li>
     *   <li>AFib daytime: tags 0x0009, 0x000A</li>
     *   <li>AFib night: tag 0x000B</li>
     * </ul>
     * 0x0035 and 0x0058 are sent whenever any health feature is active (purpose unknown).
     * 0x000F (TAG_ECG_MEAS) is shared between ECG and respiratory scan; sent once even if both on.
     */
    private void addFeatureTagsCommand(final boolean ecgEnabled, final String respiratoryScan,
                                       final boolean afibDayEnabled, final boolean afibNightEnabled) {
        final WithingsMessage msg = new WithingsMessage(WithingsMessageType.SET_FEATURE_TAGS_DEPRECATED);
        msg.addDataStructure(new FeatureTagsUserId());  // userId = 0

        final boolean respAutomatic = "automatic".equals(respiratoryScan);
        final boolean respAlways    = "always".equals(respiratoryScan);
        final boolean respActive    = respAutomatic || respAlways;

        if (ecgEnabled) {
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_ECG_TERMS));
        }
        if (respAutomatic) {
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_SPO2_SLEEP));
        }
        if (respAlways) {
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_RESP_ALWAYS));
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_0x0011));
        }
        if (afibDayEnabled) {
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_AFIB_WINDOW));
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_AFIB_EXTRA));
        }
        if (afibNightEnabled) {
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_AFIB_NIGHT));
        }
        if (ecgEnabled || respActive) {
            // TAG_ECG_MEAS (0x000F) enables ECG/respiratory measurements; send once even if both on
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_ECG_MEAS));
        }
        if (ecgEnabled || respActive || afibDayEnabled || afibNightEnabled) {
            // Baseline tags always present when any health feature is active
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_0x0035));
            msg.addDataStructure(new FeatureTagDeprecated(FeatureTagDeprecated.TAG_0x0058));
        }
        msg.addDataStructure(new EndOfTransmission());
        addSimpleConversationToQueue(msg);
    }

    /**
     * Queues a {@code CMD_LOCAL_NOTIFICATIONS_CONFIG_SET} (0x0990) message configuring the
     * five on-watch notification slots.
     *
     * <p>All five slots must always be sent together. Slot mapping from HCI captures:
     * <ol>
     *   <li>PPG_AFIB - enabled when AFib daytime is on</li>
     *   <li>HIGH_HR  - enabled when AFib daytime is on (high HR alert, tied to AFib/ECG state)</li>
     *   <li>LOW_HR   - enabled when AFib daytime is on (low HR alert, tied to AFib/ECG state)</li>
     *   <li>SLOT_4   - purpose unknown; always disabled</li>
     *   <li>PPG_AFIB_NIGHT - enabled when AFib night is on</li>
     * </ol>
     */
    private void addLocalNotificationsCommand(final boolean afibDayEnabled,
                                              final boolean afibNightEnabled) {
        final WithingsMessage msg = new WithingsMessage(WithingsMessageType.SET_LOCAL_NOTIFICATIONS);
        msg.addDataStructure(new LocalNotification(LocalNotification.NOTIF_PPG_AFIB,
                afibDayEnabled ? LocalNotification.STATUS_ENABLED : LocalNotification.STATUS_DISABLED));
        msg.addDataStructure(new LocalNotification(LocalNotification.NOTIF_HIGH_HR,
                afibDayEnabled ? LocalNotification.STATUS_ENABLED : LocalNotification.STATUS_DISABLED));
        msg.addDataStructure(new LocalNotification(LocalNotification.NOTIF_LOW_HR,
                afibDayEnabled ? LocalNotification.STATUS_ENABLED : LocalNotification.STATUS_DISABLED));
        msg.addDataStructure(new LocalNotification(LocalNotification.NOTIF_SLOT_4,
                LocalNotification.STATUS_DISABLED));
        msg.addDataStructure(new LocalNotification(LocalNotification.NOTIF_PPG_AFIB_NIGHT,
                afibNightEnabled ? LocalNotification.STATUS_ENABLED : LocalNotification.STATUS_DISABLED));
        msg.addDataStructure(new EndOfTransmission());
        addSimpleConversationToQueue(msg);
    }

    /**
     * Builds a SET_WORKOUT_SCREEN message for the ScanWatch with correct mode/flags and
     * two icon sizes (20x20 at idx=0, 28x28 at idx=1) as required by the ScanWatch protocol.
     */
    @NonNull
    @Override
    protected Message createWorkoutScreenMessage(String workoutType) {
        final WithingsActivityType activityType = WithingsActivityType.fromPrefValue(workoutType);
        final int code = activityType.getCode();

        Message message = new WithingsMessage(WithingsMessageType.SET_WORKOUT_SCREEN, ExpectedResponse.NONE);

        // Workout screen settings with correct mode and flags
        WorkoutScreen workoutScreen = new WorkoutScreen();
        workoutScreen.setId(code);
        final int stringId = getContext().getResources().getIdentifier(
                "activity_type_" + workoutType, "string", getContext().getPackageName());
        workoutScreen.setName(getContext().getString(stringId));
        workoutScreen.setMode(activityType.getWorkoutMode());
        workoutScreen.yetunknown2 = activityType.getWorkoutFlags();
        message.addDataStructure(workoutScreen);

        // Get the drawable for this activity type
        final int drawableId = activityType.toActivityKind().getIcon();
        final Drawable drawable = getContext().getDrawable(drawableId);

        // Icon 0: 20x20
        ImageMetaData meta0 = new ImageMetaData();
        meta0.setWidth((byte) 20);
        meta0.setHeight((byte) 20);
        message.addDataStructure(meta0);

        ImageData data0 = new ImageData();
        data0.setImageData(IconHelper.getIconBytesFromDrawable(drawable, 20, 20));
        message.addDataStructure(data0);

        // Icon 1: 28x28
        ImageMetaData meta1 = new ImageMetaData();
        meta1.setIndex((byte) 1);
        meta1.setWidth((byte) 28);
        meta1.setHeight((byte) 28);
        message.addDataStructure(meta1);

        ImageData data1 = new ImageData();
        data1.setImageData(IconHelper.getIconBytesFromDrawable(drawable, 28, 28));
        message.addDataStructure(data1);

        return message;
    }

    /**
     * Builds the Scanwatch screen list from the user's drag-sort preference.
     *
     * <p>The preference {@code withings_scanwatch_screens_sortable} stores a comma-separated list
     * of screen value keys in the order the user has arranged them. Only screens present in the
     * list are enabled; removing an entry from the list disables that screen on the watch.
     *
     * <p>Each screen has a fixed internal slot number (confirmed from {@code reorder_screens.zip}
     * BLE capture). The watch determines display order by ascending slot number, so the slot values
     * are fixed per screen - reordering is achieved by which screens are included, not by changing
     * slot numbers.
     *
     * <p>Screen ID <-> slot mapping is defined in {@link WithingsScreenId#getScanwatchSlot(int)}.
     *
     * <p>This method is a no-op if the current screen list matches what was last successfully sent
     * to the watch.
     */
    @Override
    protected void addScreenListCommands() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());

        final List<String> defaultScreens = Arrays.asList(
                getContext().getResources().getStringArray(R.array.pref_withings_scanwatch_screens_default));

        final String screensPref = prefs.getString(PREF_SCREENS_SORTABLE, null);
        final List<String> enabledScreens;
        if (screensPref == null || screensPref.isEmpty()) {
            enabledScreens = defaultScreens;
        } else {
            enabledScreens = Arrays.asList(screensPref.split(","));
        }

        // Normalise to a canonical comma-separated string for comparison
        final String currentValue = String.join(",", enabledScreens);
        final String lastSentValue = prefs.getString(PREF_SCREENS_LAST_SENT, null);

        if (Objects.equals(currentValue, lastSentValue)) {
            logger.debug("Screen list unchanged ({}), skipping SET_SCREEN_LIST", currentValue);
            return;
        }

        Message message = new WithingsMessage(WithingsMessageType.SET_SCREEN_LIST);
        for (final String screenKey : enabledScreens) {
            final int screenId = screenKeyToId(screenKey);
            if (screenId < 0) {
                logger.warn("Unknown screen key '{}', skipping", screenKey);
                continue;
            }
            final byte slot = WithingsScreenId.getScanwatchSlot(screenId);
            if (slot < 0) {
                logger.warn("No fixed slot for screen key '{}' (id=0x{:04x}), skipping", screenKey, screenId);
                continue;
            }
            message.addDataStructure(buildScreen(screenId, slot));
        }
        message.addDataStructure(new EndOfTransmission());
        addSimpleConversationToQueue(message);

        // Record what we sent so we can skip on future syncs if nothing changed
        prefs.edit().putString(PREF_SCREENS_LAST_SENT, currentValue).apply();
    }

    /**
     * Maps a screen preference value key (as stored in the DragSortListPreference) to the
     * corresponding {@link WithingsScreenId} constant.
     *
     * @param key the string value from {@code pref_withings_scanwatch_screens_values}
     * @return the screen ID constant, or {@code -1} if the key is not recognised
     */
    private static int screenKeyToId(final String key) {
        switch (key) {
            case "date":       return WithingsScreenId.DATE;
            case "sleep":      return WithingsScreenId.SLEEP;
            case "ecg":        return WithingsScreenId.ECG;
            case "elevation":  return WithingsScreenId.ELEVATION;
            case "heart_rate": return WithingsScreenId.HEART_RATE;
            case "spo2":       return WithingsScreenId.SPO2;
            case "calories":   return WithingsScreenId.CALORIES;
            case "settings":   return WithingsScreenId.SETTINGS;
            case "workouts":   return WithingsScreenId.WORKOUTS;
            case "distance":   return WithingsScreenId.DISTANCE;
            case "steps":      return WithingsScreenId.STEPS;
            case "breathe":    return WithingsScreenId.BREATHE;
            case "clock":      return WithingsScreenId.CLOCK;
            default:           return -1;
        }
    }
}
