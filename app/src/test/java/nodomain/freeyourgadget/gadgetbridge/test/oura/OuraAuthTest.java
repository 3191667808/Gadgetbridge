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
package nodomain.freeyourgadget.gadgetbridge.test.oura;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraAuth;

public class OuraAuthTest {

    @Test
    public void parseHexKey_validKey() {
        final byte[] key = OuraAuth.parseHexKey("000102030405060708090A0B0C0D0E0F");
        assertNotNull(key);
        assertEquals(16, key.length);
        for (int i = 0; i < 16; i++) {
            assertEquals(i, key[i] & 0xff);
        }
    }

    @Test
    public void parseHexKey_lowercase() {
        assertNotNull(OuraAuth.parseHexKey("aabbccddeeff00112233445566778899"));
    }

    @Test
    public void parseHexKey_withSpaces() {
        assertNotNull(OuraAuth.parseHexKey("00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF"));
    }

    @Test
    public void parseHexKey_wrongLength() {
        assertNull(OuraAuth.parseHexKey("00112233"));
        assertNull(OuraAuth.parseHexKey("000102030405060708090A0B0C0D0E0F00"));
    }

    @Test
    public void parseHexKey_invalidChars() {
        assertNull(OuraAuth.parseHexKey("000102030405060708090A0B0C0D0E0G"));
    }

    @Test
    public void computeAuthResponse_paddingMatchesAesEcb() throws Exception {
        // Synthetic fixture: throwaway key + nonce + expected ciphertext computed independently.
        final byte[] key = new byte[]{
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F};
        final byte[] nonce = new byte[]{
                0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
                0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E};

        final byte[] padded = new byte[16];
        System.arraycopy(nonce, 0, padded, 0, 15);
        padded[15] = 0x01;
        final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        final byte[] expected = cipher.doFinal(padded);

        final byte[] actual = OuraAuth.computeAuthResponse(nonce, key);
        assertArrayEquals(expected, actual);
        assertEquals(16, actual.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void computeAuthResponse_rejectsShortNonce() throws Exception {
        OuraAuth.computeAuthResponse(new byte[14], new byte[16]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void computeAuthResponse_rejectsShortKey() throws Exception {
        OuraAuth.computeAuthResponse(new byte[15], new byte[15]);
    }
}
