/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.miscale;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MiScaleS400DecryptorTest {
    private static final String TEST_MAC = "84:46:93:64:A5:E6";
    private static final String TEST_BIND_KEY = "58305740b64e4b425e518aa1f4e51339";

    @Test
    public void decrypt24BytePayload() {
        final byte[] data = hexToBytes("4859d53b2d3314943c58b133638c7457a4000000c3e670dc");

        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY);

        assertNotNull(measurement);
        assertEquals(74.2f, measurement.getWeightKg(), 0.1f);
    }

    @Test
    public void decrypt26BytePayloadWithHeader() {
        final byte[] data = hexToBytes("95FE4859D53B3BDE6BC8D05B51C0CDFD9021C9000000925C5039");

        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY);

        assertNotNull(measurement);
        assertEquals(73.2f, measurement.getWeightKg(), 0.1f);
    }

    @Test
    public void validateBindKey() {
        assertTrue(MiScaleS400Decryptor.isValidBindKey(TEST_BIND_KEY));
        assertTrue(MiScaleS400Decryptor.isValidBindKey(TEST_BIND_KEY.toUpperCase()));
        assertFalse(MiScaleS400Decryptor.isValidBindKey(null));
        assertFalse(MiScaleS400Decryptor.isValidBindKey("invalid"));
        assertFalse(MiScaleS400Decryptor.isValidBindKey("58305740b64e4b425e518aa1f4e5133z"));
    }

    @Test
    public void rejectInvalidBindKey() {
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes("4859d53b2d3314943c58b133638c7457a4000000c3e670dc"), TEST_MAC, "invalid"));
    }

    @Test
    public void rejectInvalidPayload() {
        assertNull(MiScaleS400Decryptor.decrypt(null, TEST_MAC, TEST_BIND_KEY));
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes("4859d53b"), TEST_MAC, TEST_BIND_KEY));
    }

    @Test
    public void rejectInvalidMac() {
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes("4859d53b2d3314943c58b133638c7457a4000000c3e670dc"), "invalid", TEST_BIND_KEY));
    }

    private static byte[] hexToBytes(final String hex) {
        final String cleanHex = hex.replace(" ", "");
        final byte[] bytes = new byte[cleanHex.length() / 2];
        for (int i = 0; i < cleanHex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(cleanHex.substring(i, i + 2), 16);
        }
        return bytes;
    }
}
