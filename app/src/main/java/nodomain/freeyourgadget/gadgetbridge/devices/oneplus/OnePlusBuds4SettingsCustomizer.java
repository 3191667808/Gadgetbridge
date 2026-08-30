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
package nodomain.freeyourgadget.gadgetbridge.devices.oneplus;

import android.content.SharedPreferences;
import android.os.Parcel;
import android.util.Pair;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.bbk.BBKSettingsHelper;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigValue;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class OnePlusBuds4SettingsCustomizer implements DeviceSpecificSettingsCustomizer {

    private final Map<Pair<OnePlusBuds4TouchConfigSide, OnePlusBuds4TouchConfigType>, List<OnePlusBuds4TouchConfigValue>> touchOptions;

    public static final Creator<OnePlusBuds4SettingsCustomizer> CREATOR = new Creator<OnePlusBuds4SettingsCustomizer>() {
        @Override
        public OnePlusBuds4SettingsCustomizer createFromParcel(final Parcel in) {
            final Map<Pair<OnePlusBuds4TouchConfigSide, OnePlusBuds4TouchConfigType>, List<OnePlusBuds4TouchConfigValue>> touchOptions = new LinkedHashMap<>();
            final int numOptions = in.readInt();
            for (int i = 0; i < numOptions; i++) {
                final OnePlusBuds4TouchConfigSide touchConfigSide = OnePlusBuds4TouchConfigSide.valueOf(in.readString());
                final OnePlusBuds4TouchConfigType touchConfigType = OnePlusBuds4TouchConfigType.valueOf(in.readString());
                final List<OnePlusBuds4TouchConfigValue> values = new ArrayList<>();
                in.readList(values, OnePlusBuds4TouchConfigValue.class.getClassLoader());
                touchOptions.put(Pair.create(touchConfigSide, touchConfigType), values);
            }

            return new OnePlusBuds4SettingsCustomizer(touchOptions);
        }

        @Override
        public OnePlusBuds4SettingsCustomizer[] newArray(final int size) {
            return new OnePlusBuds4SettingsCustomizer[size];
        }
    };

    public OnePlusBuds4SettingsCustomizer(final Map<Pair<OnePlusBuds4TouchConfigSide, OnePlusBuds4TouchConfigType>, List<OnePlusBuds4TouchConfigValue>> touchOptions) {
        this.touchOptions = touchOptions;
    }

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
        if (OnePlusBuds4Preferences.ANC_TOUCH_CYCLE_MODES.equals(preference.getKey())) {
            BBKSettingsHelper.validateAncTouchCycleModes(preference, handler);
        }
        if (OnePlusBuds4Preferences.EQ_PRESET.equals(preference.getKey())) {
            setEqBandsEnabled(handler, isCustomPreset(
                    preference.getSharedPreferences().getString(preference.getKey(), null)));
        }
    }

    private static void addPreferenceHandler(final DeviceSpecificSettingsHandler handler, final String key) {
        final Preference pref = handler.findPreference(key);
        if (pref != null) {
            handler.addPreferenceHandlerFor(key);
        }
    }

    private static void addEqBuiltinPreset(final DeviceSpecificSettingsHandler handler,
                                          final List<CharSequence> entries,
                                          final List<CharSequence> values,
                                          final Map<String, String> labelByValue,
                                          final int stringRes,
                                          final String value) {
        final String label = handler.getContext().getString(stringRes);
        entries.add(label);
        values.add(value);
        labelByValue.put(value, label);
    }

    private static boolean isCustomPreset(final String value) {
        return value != null
                && value.startsWith(OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX)
                && value.length() > OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX.length();
    }

    private static void setEqBandsEnabled(final DeviceSpecificSettingsHandler handler, final boolean enabled) {
        for (int i = 0; i < 6; i++) {
            final SeekBarPreference band = (SeekBarPreference) handler.findPreference(
                    OnePlusBuds4Preferences.getEqBandKey(i));
            if (band != null) {
                band.setEnabled(enabled);
            }
        }
    }

    private static void applyEqPresetSelection(final DeviceSpecificSettingsHandler handler,
                                               final List<OnePlusEqPreset> customPresets,
                                               final String value) {
        if (!isCustomPreset(value)) {
            return;
        }
        try {
            final int id = Integer.parseInt(value.substring(OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX.length()));
            final OnePlusEqPreset preset = OnePlusEqPreset.findBySubtype(customPresets, id);
            if (preset != null) {
                SharedPreferences.Editor editor = null;
                for (int i = 0; i < 6 && i < preset.gains.length; i++) {
                    final SeekBarPreference band = (SeekBarPreference) handler.findPreference(
                            OnePlusBuds4Preferences.getEqBandKey(i));
                    if (band != null) {
                        band.setValue(preset.gains[i]);
                        if (editor == null) {
                            editor = band.getSharedPreferences().edit();
                        }
                        editor.putInt(band.getKey(), preset.gains[i]);
                    }
                }
                if (editor != null) {
                    editor.apply();
                }
            }
        } catch (final NumberFormatException ignored) {
        }
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        addPreferenceHandler(handler, OnePlusBuds4Preferences.ANC_SELECTOR);
        addPreferenceHandler(handler, OnePlusBuds4Preferences.ANC_TOUCH_CYCLE_MODES);
        addPreferenceHandler(handler, OnePlusBuds4Preferences.ALARM_VOLUME);
        if ("oneplus_buds4_equalizer".equals(rootKey) && handler.getDevice() != null) {
            GBApplication.deviceService(handler.getDevice()).onReadConfiguration(OnePlusBuds4Preferences.EQ_ACTIVE_STATE_QUERY);
        }
        if (handler.findPreference(OnePlusBuds4Preferences.ALARM_VOLUME) != null && handler.getDevice() != null) {
            GBApplication.deviceService(handler.getDevice()).onReadConfiguration(OnePlusBuds4Preferences.ALARM_VOLUME_QUERY);
        }
        setupEqualizer(handler, prefs);
        setupBassBoost(handler);
        addPreferenceHandler(handler, OnePlusBuds4Preferences.GAME_MODE);
        addPreferenceHandler(handler, OnePlusBuds4Preferences.DUAL_CONNECTION);
        addPreferenceHandler(handler, OnePlusBuds4Preferences.SPATIAL_AUDIO);
        addPreferenceHandler(handler, OnePlusBuds4Preferences.AUTO_PLAY_PAUSE);
        addPreferenceHandler(handler, OnePlusBuds4Preferences.WEAR_DETECTION);
        setupTouch(handler);
    }

    private void setupEqualizer(final DeviceSpecificSettingsHandler handler, final Prefs prefs) {
        final String eqPresetValue = prefs.getString(OnePlusBuds4Preferences.EQ_PRESET, OnePlusBuds4Preferences.EQ_PRESET_UNSET);
        final ListPreference eqPresetPref = (ListPreference) handler.findPreference(OnePlusBuds4Preferences.EQ_PRESET);
        if (eqPresetPref != null) {
            final List<CharSequence> entries = new ArrayList<>();
            final List<CharSequence> values = new ArrayList<>();
            final Map<String, String> labelByValue = new LinkedHashMap<>();
            addEqBuiltinPreset(handler, entries, values, labelByValue, R.string.balanced, OnePlusBuds4Preferences.EQ_PRESET_BUILTIN_PREFIX + OnePlusEqPreset.BUILTIN_BALANCED_ID);
            addEqBuiltinPreset(handler, entries, values, labelByValue, R.string.oneplus_buds4_eq_preset_serenade, OnePlusBuds4Preferences.EQ_PRESET_BUILTIN_PREFIX + OnePlusEqPreset.BUILTIN_SERENADE_ID);
            addEqBuiltinPreset(handler, entries, values, labelByValue, R.string.haylou_s35_anc_eq_bass, OnePlusBuds4Preferences.EQ_PRESET_BUILTIN_PREFIX + OnePlusEqPreset.BUILTIN_BASS_ID);
            for (final OnePlusEqPreset p : OnePlusEqPreset.parse(
                    prefs.getString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, ""))) {
                final String val = OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX + p.subtype;
                entries.add(p.name);
                values.add(val);
                labelByValue.put(val, p.name);
            }
            eqPresetPref.setEntries(entries.toArray(new CharSequence[0]));
            eqPresetPref.setEntryValues(values.toArray(new CharSequence[0]));
            eqPresetPref.setSummaryProvider(preference -> {
                final String v = ((ListPreference) preference).getValue();
                if (v == null || v.isEmpty()) {
                    return handler.getContext().getString(R.string.n_a);
                }
                return labelByValue.getOrDefault(v, v);
            });
            handler.addPreferenceHandlerFor(OnePlusBuds4Preferences.EQ_PRESET, (preference, newValue) -> {
                final String v = newValue == null ? "" : newValue.toString();
                final List<OnePlusEqPreset> liveCustomPresets = OnePlusEqPreset.parse(
                        prefs.getString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, ""));
                applyEqPresetSelection(handler, liveCustomPresets, v);
                return true;
            });
        }
        final boolean eqCustom = isCustomPreset(eqPresetValue);
        if (eqCustom) {
            applyEqPresetSelection(handler, OnePlusEqPreset.parse(
                    prefs.getString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, "")), eqPresetValue);
        }
        setEqBandsEnabled(handler, eqCustom);
        for (int i = 0; i < 6; i++) {
            final SeekBarPreference eqBandPref = (SeekBarPreference) handler.findPreference(
                    OnePlusBuds4Preferences.getEqBandKey(i));
            if (eqBandPref != null) {
                eqBandPref.setSummaryProvider(preference -> {
                    final int db = ((SeekBarPreference) preference).getValue();
                    return (db > 0 ? "+" : "") + db + " dB";
                });
                addPreferenceHandler(handler, OnePlusBuds4Preferences.getEqBandKey(i));
            }
        }
    }

    private void setupBassBoost(final DeviceSpecificSettingsHandler handler) {
        final SeekBarPreference bassBoostPref = (SeekBarPreference) handler.findPreference(
                OnePlusBuds4Preferences.BASS_BOOST);
        if (bassBoostPref != null) {
            bassBoostPref.setSummaryProvider(preference -> {
                final int level = ((SeekBarPreference) preference).getValue();
                if (level == 0) {
                    return handler.getContext().getString(R.string.oneplus_buds4_bass_boost_summary);
                }
                return (level > 0 ? "+" : "") + level;
            });
            addPreferenceHandler(handler, OnePlusBuds4Preferences.BASS_BOOST);
        }
    }

    private void setupTouch(final DeviceSpecificSettingsHandler handler) {
        BBKSettingsHelper.filterTouchPreferences(handler, touchOptions, OnePlusBuds4Preferences::getTouchKey);
        BBKSettingsHelper.hideUnsupportedTouchPreferences(
                handler,
                touchOptions,
                OnePlusBuds4Preferences::getTouchKey,
                "oneplus_buds4_touch_header_",
                OnePlusBuds4TouchConfigSide.values(),
                OnePlusBuds4TouchConfigType.values()
        );
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
        dest.writeInt(touchOptions.size());
        for (final Map.Entry<Pair<OnePlusBuds4TouchConfigSide, OnePlusBuds4TouchConfigType>, List<OnePlusBuds4TouchConfigValue>> e : touchOptions.entrySet()) {
            dest.writeString(e.getKey().first.name());
            dest.writeString(e.getKey().second.name());
            dest.writeList(e.getValue());
        }
    }
}
