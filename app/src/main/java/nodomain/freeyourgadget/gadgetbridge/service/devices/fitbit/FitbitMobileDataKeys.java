/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import android.content.SharedPreferences;

import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.devices.fitbit.FitbitConstants;

final class FitbitMobileDataKeys {
    private static final String MOBILE_DATA_PSK_IDENTITY_PREFIX = "MD-";
    private static final int MOBILE_DATA_PSK_IDENTITY_LENGTH = 20;
    private static final int MOBILE_DATA_PSK_LENGTH = 16;

    private FitbitMobileDataKeys() {
    }

    static byte[] loadPsk(final SharedPreferences preferences, final String identity) {
        final String normalizedIdentity = normalizeMobileDataPskIdentity(identity);
        if (normalizedIdentity == null) {
            return null;
        }

        final String requestedKeyId = mobileDataPskIdentityKeyId(normalizedIdentity);
        final String storedKeys = preferences.getString(FitbitConstants.PREF_MOBILE_DATA_KEYS, "");
        for (final String line : storedKeys.split("\\R")) {
            final String normalizedEntry = normalizeMobileDataKeyEntry(line);
            if (normalizedEntry == null) {
                continue;
            }

            if (requestedKeyId.equals(mobileDataKeyEntryKeyId(normalizedEntry))) {
                return parseMobileDataKey(normalizedEntry);
            }
        }

        return null;
    }

    static boolean isMobileDataPskIdentity(final String identity) {
        return identity != null
                && identity.length() == MOBILE_DATA_PSK_IDENTITY_LENGTH
                && identity.startsWith(MOBILE_DATA_PSK_IDENTITY_PREFIX)
                && identity.charAt(11) == '-';
    }

    private static String normalizeMobileDataKeyEntry(final String rawEntry) {
        if (rawEntry == null) {
            return null;
        }

        String entry = rawEntry.trim();
        if (entry.isEmpty() || entry.startsWith("#")) {
            return null;
        }

        final int delimiterIndex = findMobileDataKeyDelimiter(entry);
        if (delimiterIndex <= 0 || delimiterIndex >= entry.length() - 1) {
            return null;
        }

        final String identity = normalizeMobileDataKeyReference(entry.substring(0, delimiterIndex).trim());
        if (identity == null) {
            return null;
        }

        final String keyHex = cleanHexString(entry.substring(delimiterIndex + 1));
        if (keyHex.length() != MOBILE_DATA_PSK_LENGTH * 2 || !isHexString(keyHex)) {
            return null;
        }

        return identity + ":" + keyHex.toLowerCase(Locale.ROOT);
    }

    private static int findMobileDataKeyDelimiter(final String entry) {
        final int colonIndex = entry.indexOf(':');
        final int equalsIndex = entry.indexOf('=');
        final int spaceIndex = firstWhitespaceIndex(entry);

        int delimiterIndex = -1;
        if (colonIndex >= 0) {
            delimiterIndex = colonIndex;
        }
        if (equalsIndex >= 0 && (delimiterIndex < 0 || equalsIndex < delimiterIndex)) {
            delimiterIndex = equalsIndex;
        }
        if (spaceIndex >= 0 && (delimiterIndex < 0 || spaceIndex < delimiterIndex)) {
            delimiterIndex = spaceIndex;
        }
        return delimiterIndex;
    }

    private static int firstWhitespaceIndex(final String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeMobileDataPskIdentity(final String identity) {
        if (identity == null) {
            return null;
        }

        final String normalized = identity.trim().toUpperCase(Locale.ROOT);
        if (!isMobileDataPskIdentity(normalized)) {
            return null;
        }

        final String keyId = normalized.substring(3, 11);
        final String expiration = normalized.substring(12, 20);
        if (!isHexString(keyId) || !isHexString(expiration)) {
            return null;
        }

        return normalized;
    }

    private static String normalizeMobileDataKeyReference(final String reference) {
        if (reference == null) {
            return null;
        }

        final String normalizedIdentity = normalizeMobileDataPskIdentity(reference);
        if (normalizedIdentity != null) {
            return normalizedIdentity;
        }

        final String keyId = cleanHexString(reference).toUpperCase(Locale.ROOT);
        if (keyId.length() == 8 && isHexString(keyId)) {
            return MOBILE_DATA_PSK_IDENTITY_PREFIX + keyId + "-00000000";
        }

        return null;
    }

    private static String mobileDataKeyEntryIdentity(final String normalizedEntry) {
        return normalizedEntry.substring(0, normalizedEntry.indexOf(':'));
    }

    private static String mobileDataKeyEntryKeyId(final String normalizedEntry) {
        return mobileDataPskIdentityKeyId(mobileDataKeyEntryIdentity(normalizedEntry));
    }

    private static String mobileDataPskIdentityKeyId(final String normalizedIdentity) {
        return normalizedIdentity.substring(3, 11);
    }

    private static byte[] parseMobileDataKey(final String normalizedEntry) {
        final String keyHex = normalizedEntry.substring(normalizedEntry.indexOf(':') + 1);
        final byte[] key = new byte[keyHex.length() / 2];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) Integer.parseInt(keyHex.substring(i * 2, i * 2 + 2), 16);
        }
        return key;
    }

    private static String cleanHexString(final String value) {
        return value
                .replace("0x", "")
                .replace("0X", "")
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .replace("\t", "")
                .trim();
    }

    private static boolean isHexString(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!((c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return !value.isEmpty();
    }
}
