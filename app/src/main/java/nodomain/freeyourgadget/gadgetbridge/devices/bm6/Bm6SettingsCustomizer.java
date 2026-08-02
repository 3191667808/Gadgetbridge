package nodomain.freeyourgadget.gadgetbridge.devices.bm6;

import android.os.Parcel;

import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.bm6.Bm6Constants;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class Bm6SettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    private final GBDevice device;

    public Bm6SettingsCustomizer(final GBDevice device) {
        this.device = device;
    }

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
        // Nothing extra to do here; the device uses the stored preference during the next BM6 setup.
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        handler.addXmlPreferences(R.xml.bm6_device_settings);
        handler.addPreferenceHandlerFor(Bm6Constants.PREF_BATTERY_TYPE);
    }

    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        return Collections.emptySet();
    }

    public static final Creator<Bm6SettingsCustomizer> CREATOR = new Creator<Bm6SettingsCustomizer>() {
        @Override
        public Bm6SettingsCustomizer createFromParcel(final Parcel in) {
            final GBDevice device = in.readParcelable(Bm6SettingsCustomizer.class.getClassLoader());
            return new Bm6SettingsCustomizer(device);
        }

        @Override
        public Bm6SettingsCustomizer[] newArray(final int size) {
            return new Bm6SettingsCustomizer[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeParcelable(device, 0);
    }
}
