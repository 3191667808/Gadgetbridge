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
package nodomain.freeyourgadget.gadgetbridge.devices.withingsscanwatch;

import android.os.Parcel;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class WithingsScanwatchSettingsCustomizer implements DeviceSpecificSettingsCustomizer {

    static final String PREF_SCREENS_SORTABLE  = "withings_scanwatch_screens_sortable";
    static final String PREF_SHORTCUT_ACTION   = "withings_scanwatch_shortcut_action";
    static final String PREF_ECG_ENABLED       = "withings_scanwatch_ecg_enabled";
    static final String PREF_RESPIRATORY_SCAN  = "withings_scanwatch_respiratory_scan";
    static final String PREF_AFIB_DAY_ENABLED  = "withings_scanwatch_afib_day_enabled";
    static final String PREF_AFIB_NIGHT_ENABLED= "withings_scanwatch_afib_night_enabled";
    static final String PREF_QUICKLOOK         = "withings_scanwatch_quicklook";
    static final String PREF_AUTO_BRIGHTNESS    = "withings_scanwatch_auto_brightness";
    static final String PREF_BRIGHTNESS_LEVEL   = "withings_scanwatch_brightness_level";
    static final String PREF_MOVE_HANDS         = "withings_scanwatch_move_hands";

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        handler.addPreferenceHandlerFor(PREF_SCREENS_SORTABLE);
        handler.addPreferenceHandlerFor(PREF_SHORTCUT_ACTION);
        handler.addPreferenceHandlerFor(PREF_ECG_ENABLED);
        handler.addPreferenceHandlerFor(PREF_RESPIRATORY_SCAN);
        handler.addPreferenceHandlerFor(PREF_AFIB_DAY_ENABLED);
        handler.addPreferenceHandlerFor(PREF_AFIB_NIGHT_ENABLED);
        handler.addPreferenceHandlerFor(PREF_QUICKLOOK);
        handler.addPreferenceHandlerFor(PREF_AUTO_BRIGHTNESS);
        handler.addPreferenceHandlerFor(PREF_MOVE_HANDS);

        // Brightness level is only meaningful when auto-brightness is OFF (inverse dependency)
        final EditTextPreference brightnessPref = handler.findPreference(PREF_BRIGHTNESS_LEVEL);
        if (brightnessPref != null) {
            final boolean autoOn = prefs.getBoolean(PREF_AUTO_BRIGHTNESS, true);
            brightnessPref.setEnabled(!autoOn);
        }

        // Register brightness level handler with validation: integer only, clamped to 0-100
        handler.addPreferenceHandlerFor(PREF_BRIGHTNESS_LEVEL, (pref, newValue) -> {
            final String raw = String.valueOf(newValue).trim();
            try {
                final int val = Integer.parseInt(raw);
                final int clamped = Math.max(0, Math.min(100, val));
                if (clamped != val && pref instanceof EditTextPreference) {
                    // Persist the clamped value instead of the out-of-range one
                    ((EditTextPreference) pref).setText(String.valueOf(clamped));
                    return false; // reject original; clamped value already persisted
                }
            } catch (NumberFormatException e) {
                return false; // reject non-integer input
            }
            return true;
        });

        final ListPreference shortcutPref = handler.findPreference(PREF_SHORTCUT_ACTION);
        if (shortcutPref != null) {
            shortcutPref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        final ListPreference respiratoryPref = handler.findPreference(PREF_RESPIRATORY_SCAN);
        if (respiratoryPref != null) {
            respiratoryPref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }
    }

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
        if (PREF_AUTO_BRIGHTNESS.equals(preference.getKey())) {
            final EditTextPreference brightnessPref = handler.findPreference(PREF_BRIGHTNESS_LEVEL);
            if (brightnessPref != null) {
                // preference value has already been persisted at this point
                final boolean autoOn = preference.getSharedPreferences().getBoolean(PREF_AUTO_BRIGHTNESS, true);
                brightnessPref.setEnabled(!autoOn);
            }
        }
    }

    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        return Collections.emptySet();
    }

    public static final Creator<WithingsScanwatchSettingsCustomizer> CREATOR = new Creator<WithingsScanwatchSettingsCustomizer>() {
        @Override
        public WithingsScanwatchSettingsCustomizer createFromParcel(final Parcel in) {
            return new WithingsScanwatchSettingsCustomizer();
        }

        @Override
        public WithingsScanwatchSettingsCustomizer[] newArray(final int size) {
            return new WithingsScanwatchSettingsCustomizer[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
    }
}
