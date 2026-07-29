/*  Copyright (C) 2026 Liu Haoxin

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
package nodomain.freeyourgadget.gadgetbridge.mcp;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Owns the MCP preference behavior without adding MCP concerns to the generic settings screen. */
public final class McpSettingsController {
    private final AbstractPreferenceFragment fragment;
    private boolean statusReceiverRegistered;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            refresh();
        }
    };

    public McpSettingsController(@NonNull final AbstractPreferenceFragment fragment) {
        this.fragment = fragment;
    }

    /** Binds controls only when the current nested preference screen contains MCP settings. */
    public void configure() {
        fragment.setNumericInputTypeWithRangeFor(
                McpPreferences.KEY_PORT,
                McpPreferences.MIN_PORT,
                McpPreferences.MAX_PORT,
                false
        );

        bindServerPreference(McpPreferences.KEY_ENABLED);
        bindDevicePreference();
        bindServerPreference(McpPreferences.KEY_ALLOW_LAN);
        bindPortPreference();
        bindCommands();
        refresh();
    }

    public void onStart() {
        if (fragment.findPreference(McpPreferences.KEY_STATUS) == null) {
            return;
        }
        LocalBroadcastManager.getInstance(fragment.requireContext()).registerReceiver(
                statusReceiver,
                new IntentFilter(McpServerManager.ACTION_STATUS_CHANGED)
        );
        statusReceiverRegistered = true;
    }

    public void onResume() {
        refresh();
    }

    public void onStop() {
        if (!statusReceiverRegistered) {
            return;
        }
        LocalBroadcastManager.getInstance(fragment.requireContext())
                .unregisterReceiver(statusReceiver);
        statusReceiverRegistered = false;
    }

    private void bindServerPreference(@NonNull final String key) {
        final Preference preference = fragment.findPreference(key);
        if (preference != null) {
            preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
                applyPreferenceChange();
                return true;
            });
        }
    }

    private void bindDevicePreference() {
        final ListPreference preference = fragment.findPreference(McpPreferences.KEY_DEVICE_ADDRESS);
        if (preference == null) {
            return;
        }

        final List<String> addresses = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        addresses.add("");
        labels.add(fragment.getString(R.string.mcp_device_automatic));
        for (final GBDevice device : GBApplication.app().getDeviceManager().getDevices()) {
            addresses.add(device.getAddress());
            labels.add(device.getAliasOrName());
        }
        preference.setEntryValues(addresses.toArray(new String[0]));
        preference.setEntries(labels.toArray(new String[0]));
        if (preference.getValue() == null) {
            preference.setValue("");
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            applyPreferenceChange();
            return true;
        });
    }

    private void bindPortPreference() {
        final Preference preference = fragment.findPreference(McpPreferences.KEY_PORT);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            try {
                final int port = Integer.parseInt(newValue.toString());
                if (port < McpPreferences.MIN_PORT || port > McpPreferences.MAX_PORT) {
                    throw new NumberFormatException("Port is outside the allowed range");
                }
            } catch (final NumberFormatException exception) {
                GB.toast(
                        fragment.requireContext(),
                        fragment.getString(R.string.mcp_port_invalid),
                        Toast.LENGTH_SHORT,
                        GB.WARN
                );
                return false;
            }
            applyPreferenceChange();
            return true;
        });
    }

    private void bindCommands() {
        final Preference endpointPreference = fragment.findPreference(McpPreferences.KEY_ENDPOINT);
        if (endpointPreference != null) {
            endpointPreference.setOnPreferenceClickListener(preference -> {
                copyToClipboard("MCP endpoint", McpPreferences.read().getEndpoint());
                return true;
            });
        }

        final Preference copyTokenPreference = fragment.findPreference(McpPreferences.KEY_COPY_TOKEN);
        if (copyTokenPreference != null) {
            copyTokenPreference.setOnPreferenceClickListener(preference -> {
                final String token = McpPreferences.ensureAccessToken(
                        fragment.getPreferenceManager().getSharedPreferences()
                );
                copyToClipboard("MCP bearer token", token);
                return true;
            });
        }

        final Preference regeneratePreference =
                fragment.findPreference(McpPreferences.KEY_REGENERATE_TOKEN);
        if (regeneratePreference != null) {
            regeneratePreference.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.mcp_regenerate_token_title)
                        .setMessage(R.string.mcp_regenerate_token_confirmation)
                        .setPositiveButton(R.string.mcp_regenerate_token_action, (dialog, which) -> {
                            McpPreferences.regenerateAccessToken(
                                    fragment.getPreferenceManager().getSharedPreferences()
                            );
                            McpServiceController.ensureServiceRunning(fragment.requireContext());
                            GB.toast(
                                    fragment.requireContext(),
                                    fragment.getString(R.string.mcp_token_regenerated),
                                    Toast.LENGTH_SHORT,
                                    GB.INFO
                            );
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            });
        }
    }

    private void applyPreferenceChange() {
        // Preference listeners run before persistence. Posting to the view queue guarantees that a
        // cold-started service reads the new complete configuration rather than the previous value.
        fragment.getListView().post(() -> {
            McpServiceController.ensureServiceRunning(fragment.requireContext());
            refresh();
        });
    }

    private void refresh() {
        final Preference statusPreference = fragment.findPreference(McpPreferences.KEY_STATUS);
        final Preference endpointPreference = fragment.findPreference(McpPreferences.KEY_ENDPOINT);
        if (statusPreference == null && endpointPreference == null) {
            return;
        }

        final McpPreferences.Config config = McpPreferences.read();
        if (endpointPreference != null) {
            endpointPreference.setSummary(config.getEndpoint());
        }
        if (statusPreference == null) {
            return;
        }
        if (!config.isEnabled()) {
            statusPreference.setSummary(R.string.mcp_status_disabled);
            return;
        }

        final McpServerManager.Status status = McpServerManager.getStatus();
        if (status.isRunning()) {
            statusPreference.setSummary(fragment.getString(
                    R.string.mcp_status_running,
                    status.getEndpoint() == null ? config.getEndpoint() : status.getEndpoint()
            ));
        } else if (status.getError() != null) {
            statusPreference.setSummary(fragment.getString(
                    R.string.mcp_status_error,
                    status.getError()
            ));
        } else {
            statusPreference.setSummary(R.string.mcp_status_starting);
        }
    }

    private void copyToClipboard(@NonNull final String label, @NonNull final String value) {
        final ClipboardManager clipboard = (ClipboardManager) fragment.requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        GB.toast(
                fragment.requireContext(),
                fragment.getString(R.string.copied_to_clipboard),
                Toast.LENGTH_SHORT,
                GB.INFO
        );
    }
}
