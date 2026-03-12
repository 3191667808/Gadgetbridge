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
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.withingsscanwatch.WithingsScanwatchSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractWithingsActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.WithingsUUIDs;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.GetShortcutHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.EndOfTransmission;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ScreenSettings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ShortcutAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WithingsScreenId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessageType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;

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

    @Override
    protected void addExtraSyncCommands() {
        addSimpleConversationToQueue(
                new WithingsMessage(WithingsMessageType.GET_SHORTCUT),
                new GetShortcutHandler(this)
        );
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
        return false;
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
