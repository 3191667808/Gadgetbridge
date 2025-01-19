/*  Copyright (C) 2025 LLan

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
package nodomain.freeyourgadget.gadgetbridge.activities.preferences;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.health.connect.client.HealthConnectClient;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Dispatchers;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectUtils;

public class HealthConnectPreferencesActivity extends AbstractSettingsActivityV2 {
    @Override
    protected String fragmentTag() {
        return HealthConnectPreferencesFragment.FRAGMENT_TAG;
    }

    @Override
    protected PreferenceFragmentCompat newFragment() {
        return new HealthConnectPreferencesFragment();
    }

    public static class HealthConnectPreferencesFragment extends AbstractPreferenceFragment {
        protected static final Logger LOG = LoggerFactory.getLogger(HealthConnectPreferencesFragment.class);

        static final String FRAGMENT_TAG = "HEALTH_CONNECT_PREFERENCES_FRAGMENT";

        private final HealthConnectUtils healthConnectUtils = new HealthConnectUtils(this);

        @Override
        public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
            setPreferencesFromResource(R.xml.health_connect_preferences, rootKey);

            final Preference healthConnectEnabledPref = findPreference(GBPrefs.HEALTH_CONNECT_ENABLED);
            if (healthConnectEnabledPref != null) {
                // (I don't know what Kotlin Continuation are used for, but they are needed
                Continuation<Set<String>> continuationString = new Continuation<Set<String>>() {
                    @NotNull
                    @Override
                    public CoroutineContext getContext() {
                        return (CoroutineContext) Dispatchers.getDefault();
                    }

                    public void resumeWith(@NonNull Object e) {

                    }
                };
                HealthConnectClient healthConnectClient = healthConnectUtils.healthConnectInit(getContext());
                Set<String> grantedPermissions = (Set<String>) healthConnectClient.getPermissionController().getGrantedPermissions(continuationString);
                assert grantedPermissions != null;

                healthConnectEnabledPref.setOnPreferenceChangeListener((preference, exportHealthConnectEnabled) -> {
                    if ((boolean) exportHealthConnectEnabled) {
                        if (!grantedPermissions.containsAll(healthConnectUtils.requiredHealthConnectPermissions)) {
                            // If we weren't enabled at some point already, show Permission screen
                            healthConnectUtils.activityResultLauncher.launch(healthConnectUtils.requiredHealthConnectPermissions);
                        }
                    }
                    return true;
                });

                if (grantedPermissions.containsAll(healthConnectUtils.requiredHealthConnectPermissions)) {
                    healthConnectEnabledPref.setEnabled(false);
                } else {
                    healthConnectEnabledPref.setEnabled(true);
                    SharedPreferences.Editor editor = GBApplication.getPrefs().getPreferences().edit();
                    editor.putBoolean(GBPrefs.HEALTH_CONNECT_ENABLED, false);
                    editor.apply();
                    findPreference(GBPrefs.HEALTH_CONNECT_MANUAL_SYNC).setVisible(false);
                    findPreference(GBPrefs.HEALTH_CONNECT_DISABLE_NOTICE).setVisible(false);
                }
            }

            final Preference healthConnectManualSettings = findPreference(GBPrefs.HEALTH_CONNECT_MANUAL_SETTINGS);
            if (healthConnectManualSettings != null) {
                healthConnectManualSettings.setOnPreferenceClickListener(preference -> {
                    Intent healthConnectManageDataIntent = HealthConnectClient.getHealthConnectManageDataIntent(requireContext());
                    startActivity(healthConnectManageDataIntent);
                    return true;
                });
            }

            final Preference healthConnectManualSync = findPreference(GBPrefs.HEALTH_CONNECT_MANUAL_SYNC);
            if (healthConnectManualSync != null) {
                healthConnectManualSync.setOnPreferenceClickListener(preference -> {
                    HealthConnectClient healthConnectClient = healthConnectUtils.healthConnectInit(getContext());
                    if (healthConnectClient == null) {
                        return false;
                    }
                    healthConnectUtils.healthConnectDataSync(getContext(), healthConnectClient);
                    return true;
                });
            }

            final MultiSelectListPreference selectedDevicesPref = findPreference("health_connect_devices_multiselect");
            if (selectedDevicesPref != null) {
                List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices();
                List<String> deviceMACs = new ArrayList<>();
                List<String> deviceNames = new ArrayList<>();
                for (GBDevice dev : devices) {
                    DeviceCoordinator deviceCoordinator = dev.getDeviceCoordinator();
                    if(!deviceCoordinator.supportsActivityTracking()) {
                        continue;
                    }
                    deviceMACs.add(dev.getAddress());
                    deviceNames.add(dev.getAliasOrName());
                }
                selectedDevicesPref.setEntryValues(deviceMACs.toArray(new String[0]));
                selectedDevicesPref.setEntries(deviceNames.toArray(new String[0]));
            }
        }
    }
}
