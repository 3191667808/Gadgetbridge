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
package nodomain.freeyourgadget.gadgetbridge.service.devices.bbk;

import android.content.Context;
import android.util.Pair;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;

/**
 * Shared helpers for BBK (OnePlus/Oppo/Realme) settings customizers.
 */
public final class BBKSettingsHelper {
    private BBKSettingsHelper() {}

    public static void validateAncTouchCycleModes(final Preference preference, final DeviceSpecificSettingsHandler handler) {
        if (!(preference instanceof MultiSelectListPreference)) {
            return;
        }
        final MultiSelectListPreference pref = (MultiSelectListPreference) preference;
        final Set<String> selected = pref.getValues();
        if (selected == null || selected.size() < 2) {
            final Context context = preference.getContext();
            final String message = context.getString(R.string.select_at_least_option, 2);
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.warning)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    /**
     * Generic touch filtering: hides ListPreferences not in touchOptions
     *
     * @param handler touchOptions map from coordinator
     * @param touchOptions map of allowed values
     * @param keyFn function to get preference key from side+type (e.g. OppoHeadphonesPreferences::getTouchKey)
     */
    public static <S, T, V extends Enum<V>> void filterTouchPreferences(
            final DeviceSpecificSettingsHandler handler,
            final Map<Pair<S, T>, List<V>> touchOptions,
            final BiFunction<S, T, String> keyFn) {
        for (final Map.Entry<Pair<S, T>, List<V>> e : touchOptions.entrySet()) {
            final S side = e.getKey().first;
            final T type = e.getKey().second;
            final Set<String> possibleValueNames = e.getValue().stream()
                    .map(v -> v.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());

            final String key = keyFn.apply(side, type);
            final ListPreference pref = handler.findPreference(key);
            if (pref == null) {
                continue;
            }

            final CharSequence[] originalEntries = pref.getEntries();
            final CharSequence[] originalValues = pref.getEntryValues();
            if (originalEntries == null || originalValues == null) {
                continue;
            }
            final List<CharSequence> entries = new ArrayList<>();
            final List<CharSequence> values = new ArrayList<>();
            for (int i = 0; i < originalValues.length; i++) {
                final String valStr = originalValues[i].toString().toLowerCase(Locale.ROOT);
                if (possibleValueNames.contains(valStr)) {
                    entries.add(originalEntries[i]);
                    values.add(originalValues[i]);
                }
            }

            pref.setEntries(entries.toArray(new CharSequence[0]));
            pref.setEntryValues(values.toArray(new CharSequence[0]));

            handler.addPreferenceHandlerFor(key);
        }
    }

    /**
     * Hides headers and preferences for touch side/type combinations not present in touchOptions.
     * Extracted from both OnePlus and Oppo customizers to avoid ~20L duplication.
     * If a side has no known types, its header (headerPrefix + side.name().toLowerCase()) is hidden.
     */
    public static <S, T, V> void hideUnsupportedTouchPreferences(
            final DeviceSpecificSettingsHandler handler,
            final Map<Pair<S, T>, List<V>> touchOptions,
            final BiFunction<S, T, String> keyFn,
            final String headerPrefix,
            final S[] allSides,
            final T[] allTypes) {
        final Set<S> knownSides = new java.util.HashSet<>();
        final Set<T> knownTypes = new java.util.HashSet<>();
        for (final Map.Entry<Pair<S, T>, List<V>> e : touchOptions.entrySet()) {
            knownSides.add(e.getKey().first);
            knownTypes.add(e.getKey().second);
        }
        for (final S side : allSides) {
            if (!knownSides.contains(side)) {
                final Preference header = handler.findPreference(headerPrefix + ((Enum<?>) side).name().toLowerCase(Locale.ROOT));
                if (header != null) {
                    header.setVisible(false);
                    continue;
                }
            }
            for (final T type : allTypes) {
                if (!knownTypes.contains(type)) {
                    final String key = keyFn.apply(side, type);
                    final Preference pref = handler.findPreference(key);
                    if (pref != null) {
                        pref.setVisible(false);
                    }
                }
            }
        }
    }
}
