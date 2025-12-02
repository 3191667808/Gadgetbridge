package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.freepro3;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_AMBIENT_SOUND_CONTROL;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_ANC_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_EQUALIZER_PRESET;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_TRANSPARENCY_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.Equalizer.TenBandEqualizerPresets;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.Equalizer;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Settings customizer for EarFun Free Pro 3 (TW400)
 */
public class EarFunFreePro3SettingsCustomizer extends EarFunSettingsCustomizer {

    @Override
    public void onPreferenceChange(Preference preference, DeviceSpecificSettingsHandler handler) {
        super.onPreferenceChange(preference, handler);
        String key = preference.getKey();
        if (key == null) {
            return;
        }

        switch (key) {
            case PREF_EARFUN_AMBIENT_SOUND_CONTROL:
                // Update visibility of ANC/ambient mode settings based on selected mode
                onPreferenceChangeAmbientSoundControl(handler);
                break;
            case PREF_EARFUN_EQUALIZER_PRESET:
                // Apply selected equalizer preset to all band sliders
                onPreferenceChangeEqualizerPreset(handler, Equalizer.TenBandEqualizer, TenBandEqualizerPresets);
                break;
        }

        // If any equalizer band slider changes, check if the current configuration
        // matches a preset and update the preset selector accordingly
        if (Equalizer.containsKey(Equalizer.TenBandEqualizer, key)) {
            int equalizerPreset = getSelectedPresetFromEqualizerBands(handler,
                    Equalizer.TenBandEqualizer, TenBandEqualizerPresets);
            ListPreference listPreferenceEqualizerPreset = handler.findPreference(PREF_EARFUN_EQUALIZER_PRESET);
            if (listPreferenceEqualizerPreset != null) {
                listPreferenceEqualizerPreset.setValue(Integer.toString(equalizerPreset));
            }
        }
    }

    @Override
    public void customizeSettings(DeviceSpecificSettingsHandler handler, Prefs prefs, String rootKey) {
        super.customizeSettings(handler, prefs, rootKey);
        // Initialize the equalizer preset list with 10-band presets
        initializeEqualizerPresetListPreference(handler, TenBandEqualizerPresets);
    }

    private void onPreferenceChangeAmbientSoundControl(DeviceSpecificSettingsHandler handler) {
        ListPreference listPreferenceAmbientSound = handler.findPreference(PREF_EARFUN_AMBIENT_SOUND_CONTROL);
        ListPreference listPreferenceTransparencyMode = handler.findPreference(PREF_EARFUN_TRANSPARENCY_MODE);
        ListPreference listPreferenceAncMode = handler.findPreference(PREF_EARFUN_ANC_MODE);

        if (listPreferenceAmbientSound == null || listPreferenceTransparencyMode == null || listPreferenceAncMode == null) {
            return;
        }

        switch (listPreferenceAmbientSound.getValue()) {
            case "1":
                listPreferenceTransparencyMode.setVisible(false);
                listPreferenceAncMode.setVisible(true);
                break;
            case "2":
                listPreferenceTransparencyMode.setVisible(true);
                listPreferenceAncMode.setVisible(false);
                break;
            default:
                listPreferenceTransparencyMode.setVisible(false);
                listPreferenceAncMode.setVisible(false);
        }
    }

    public EarFunFreePro3SettingsCustomizer(final GBDevice device) {
        super(device);
    }
}
