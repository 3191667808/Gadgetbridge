/*  Copyright (C) 2026 Gadgetbridge contributors

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

import androidx.preference.Preference;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class WithingsScanwatchLightSettingsCustomizer extends WithingsScanwatchSettingsCustomizer {
    private static final String[] UNSUPPORTED_HEALTH_PREFERENCES = {
            PREF_ECG_ENABLE,
            PREF_SPO2_ENABLE,
            PREF_SPO2_MODE,
            PREF_RESPIRATORY_SCAN,
            PREF_AFIB_DAY_ENABLED,
            PREF_AFIB_NIGHT_ENABLED
    };

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        super.customizeSettings(handler, prefs, rootKey);
        for (final String key : UNSUPPORTED_HEALTH_PREFERENCES) {
            final Preference preference = handler.findPreference(key);
            if (preference != null) {
                preference.setVisible(false);
            }
        }
    }

    public static final Creator<WithingsScanwatchLightSettingsCustomizer> CREATOR = new Creator<WithingsScanwatchLightSettingsCustomizer>() {
        @Override
        public WithingsScanwatchLightSettingsCustomizer createFromParcel(final Parcel in) {
            return new WithingsScanwatchLightSettingsCustomizer();
        }

        @Override
        public WithingsScanwatchLightSettingsCustomizer[] newArray(final int size) {
            return new WithingsScanwatchLightSettingsCustomizer[size];
        }
    };
}
