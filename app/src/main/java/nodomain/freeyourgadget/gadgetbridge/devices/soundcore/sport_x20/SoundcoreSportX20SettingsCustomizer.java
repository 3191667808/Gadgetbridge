package nodomain.freeyourgadget.gadgetbridge.devices.soundcore.sport_x20;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ENABLE_PAIRING_MODE;

import android.os.Parcel;

import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreSportX20SettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    public static final Creator<SoundcoreSportX20SettingsCustomizer> CREATOR = new Creator<SoundcoreSportX20SettingsCustomizer>() {
        @Override
        public SoundcoreSportX20SettingsCustomizer createFromParcel(final Parcel in) {
            return new SoundcoreSportX20SettingsCustomizer();
        }

        @Override
        public SoundcoreSportX20SettingsCustomizer[] newArray(final int size) {
            return new SoundcoreSportX20SettingsCustomizer[size];
        }
    };

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
        // No-op.
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        final Preference pairingMode = handler.findPreference(PREF_SOUNDCORE_ENABLE_PAIRING_MODE);
        if (pairingMode != null) {
            pairingMode.setOnPreferenceClickListener(pref -> {
                handler.notifyPreferenceChanged(PREF_SOUNDCORE_ENABLE_PAIRING_MODE);
                return true;
            });
        }
    }

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
        // No-op.
    }
}
