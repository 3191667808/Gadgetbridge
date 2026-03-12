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

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class WithingsScanwatchSettingsCustomizer implements DeviceSpecificSettingsCustomizer {

    static final String PREF_SCREENS_SORTABLE = "withings_scanwatch_screens_sortable";
    static final String PREF_SHORTCUT_ACTION = "withings_scanwatch_shortcut_action";

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        handler.addPreferenceHandlerFor(PREF_SCREENS_SORTABLE);
        handler.addPreferenceHandlerFor(PREF_SHORTCUT_ACTION);

        final ListPreference shortcutPref = handler.findPreference(PREF_SHORTCUT_ACTION);
        if (shortcutPref != null) {
            shortcutPref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }
    }

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
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
