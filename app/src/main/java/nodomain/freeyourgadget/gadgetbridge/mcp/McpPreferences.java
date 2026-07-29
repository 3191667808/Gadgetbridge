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

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.SecureRandom;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/** Central definition and validation of every MCP preference. */
public final class McpPreferences {
    public static final String SCREEN_KEY = "pref_screen_mcp";
    public static final String KEY_ENABLED = "mcp_enabled";
    public static final String KEY_DEVICE_ADDRESS = "mcp_device_address";
    public static final String KEY_ALLOW_LAN = "mcp_allow_lan";
    public static final String KEY_PORT = "mcp_port";
    public static final String KEY_ACCESS_TOKEN = "mcp_access_token";
    public static final String KEY_ENDPOINT = "mcp_endpoint";
    public static final String KEY_COPY_TOKEN = "mcp_copy_token";
    public static final String KEY_REGENERATE_TOKEN = "mcp_regenerate_token";
    public static final String KEY_STATUS = "mcp_status";

    public static final int DEFAULT_PORT = 8765;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static final Set<String> SERVER_KEYS = Set.of(
            KEY_ENABLED,
            KEY_DEVICE_ADDRESS,
            KEY_ALLOW_LAN,
            KEY_PORT,
            KEY_ACCESS_TOKEN
    );
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private McpPreferences() {
    }

    public static boolean isEnabled() {
        return GBApplication.getPrefs().getBoolean(KEY_ENABLED, false);
    }

    public static boolean isServerPreference(@Nullable final String key) {
        return key != null && SERVER_KEYS.contains(key);
    }

    /**
     * Creates the LAN bearer token before the service registers its manager. This ordering avoids
     * recursively reconfiguring the server from the SharedPreferences callback on first startup.
     */
    @NonNull
    public static String ensureAccessToken(@NonNull final SharedPreferences preferences) {
        final String current = preferences.getString(KEY_ACCESS_TOKEN, null);
        if (current != null && !current.isBlank()) {
            return current;
        }
        return regenerateAccessToken(preferences);
    }

    @NonNull
    public static String regenerateAccessToken(@NonNull final SharedPreferences preferences) {
        final byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        final StringBuilder token = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            final int unsigned = value & 0xff;
            token.append(HEX[unsigned >>> 4]);
            token.append(HEX[unsigned & 0x0f]);
        }
        final String generated = token.toString();
        preferences.edit().putString(KEY_ACCESS_TOKEN, generated).apply();
        return generated;
    }

    @NonNull
    public static Config read() {
        final Prefs prefs = GBApplication.getPrefs();
        final int requestedPort = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        final int port = Math.max(MIN_PORT, Math.min(MAX_PORT, requestedPort));
        return new Config(
                prefs.getBoolean(KEY_ENABLED, false),
                prefs.getString(KEY_DEVICE_ADDRESS, ""),
                prefs.getBoolean(KEY_ALLOW_LAN, false),
                port,
                prefs.getString(KEY_ACCESS_TOKEN, "")
        );
    }

    public static final class Config {
        private final boolean enabled;
        private final String deviceAddress;
        private final boolean allowLan;
        private final int port;
        private final String accessToken;

        private Config(
                final boolean enabled,
                @NonNull final String deviceAddress,
                final boolean allowLan,
                final int port,
                @NonNull final String accessToken
        ) {
            this.enabled = enabled;
            this.deviceAddress = deviceAddress;
            this.allowLan = allowLan;
            this.port = port;
            this.accessToken = accessToken;
        }

        public boolean isEnabled() {
            return enabled;
        }

        @NonNull
        public String getDeviceAddress() {
            return deviceAddress;
        }

        public boolean isAllowLan() {
            return allowLan;
        }

        public int getPort() {
            return port;
        }

        @NonNull
        public String getAccessToken() {
            return accessToken;
        }

        @NonNull
        public String getBindAddress() {
            return allowLan ? "0.0.0.0" : "127.0.0.1";
        }

        @NonNull
        public String getEndpoint() {
            final String host = allowLan
                    ? McpNetworkAddressResolver.findBestIpv4Address()
                    : "127.0.0.1";
            return "http://" + host + ":" + port + "/mcp";
        }
    }
}
