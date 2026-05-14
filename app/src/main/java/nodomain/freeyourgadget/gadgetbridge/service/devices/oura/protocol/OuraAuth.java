/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class OuraAuth {
    public static final int KEY_LEN = 16;
    public static final int NONCE_LEN = 15;
    public static final int CIPHERTEXT_LEN = 16;

    public static byte[] computeAuthResponse(final byte[] nonce15, final byte[] key16) throws Exception {
        if (nonce15 == null || nonce15.length != NONCE_LEN) {
            throw new IllegalArgumentException("nonce must be " + NONCE_LEN + " bytes");
        }
        if (key16 == null || key16.length != KEY_LEN) {
            throw new IllegalArgumentException("key must be " + KEY_LEN + " bytes");
        }

        final byte[] padded = new byte[CIPHERTEXT_LEN];
        System.arraycopy(nonce15, 0, padded, 0, NONCE_LEN);
        padded[NONCE_LEN] = 0x01;

        final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));
        return cipher.doFinal(padded);
    }

    public static byte[] parseHexKey(final String hex) {
        if (hex == null) {
            return null;
        }
        final String clean = hex.trim().replace(" ", "").replace(":", "");
        if (clean.length() != KEY_LEN * 2) {
            return null;
        }
        final byte[] out = new byte[KEY_LEN];
        for (int i = 0; i < KEY_LEN; i++) {
            final int hi = Character.digit(clean.charAt(i * 2), 16);
            final int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private OuraAuth() {
    }
}
