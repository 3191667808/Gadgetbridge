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
package nodomain.freeyourgadget.gadgetbridge.devices.gloryfitpro;

import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.preference.Preference;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Hides the settings the shared screens offer but this watch has no command for.
 *
 * The blood oxygen screen carries an interval and a time window alongside the on/off switch.
 * The watch does have both - the official app shows them - but the commands behind them have
 * not been decoded, so leaving them on screen would give the wearer switches that quietly do
 * nothing.
 */
public class GloryFitProSettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    public static final Creator<GloryFitProSettingsCustomizer> CREATOR = new Creator<GloryFitProSettingsCustomizer>() {
        @Override
        public GloryFitProSettingsCustomizer createFromParcel(final Parcel in) {
            return new GloryFitProSettingsCustomizer();
        }

        @Override
        public GloryFitProSettingsCustomizer[] newArray(final int size) {
            return new GloryFitProSettingsCustomizer[size];
        }
    };

    private static final String[] NOT_IMPLEMENTED = {
            "spo2_measurement_interval",
            "spo2_measurement_time",
            "spo2_measurement_start",
            "spo2_measurement_end",
    };

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        for (final String key : NOT_IMPLEMENTED) {
            final Preference pref = handler.findPreference(key);
            if (pref != null) {
                pref.setVisible(false);
            }
        }
    }

    @NonNull
    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        return Collections.emptySet();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
    }
}
