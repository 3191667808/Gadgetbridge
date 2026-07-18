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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

public class YcbtFrameCodecTest {
    private static final byte[] CAPTURED_FRAME = new byte[]{
            0x02, 0x00, 0x08, 0x00, 0x47, 0x43, 0x6f, (byte) 0xec
    };

    @Test
    public void encodesCapturedFrameExactly() {
        assertArrayEquals(
                CAPTURED_FRAME,
                YcbtFrameCodec.encode(0x02, 0x00, new byte[]{0x47, 0x43})
        );
    }

    @Test
    public void decodesCapturedFrameExactly() {
        final YcbtFrameCodec.Frame frame = YcbtFrameCodec.decode(CAPTURED_FRAME);

        assertEquals(0x02, frame.getGroup());
        assertEquals(0x00, frame.getCommand());
        assertArrayEquals(new byte[]{0x47, 0x43}, frame.getPayload());
    }

    @Test
    public void rejectsCorruptedCrc() {
        final byte[] corrupted = Arrays.copyOf(CAPTURED_FRAME, CAPTURED_FRAME.length);
        corrupted[7] ^= 0x01;

        assertInvalid(corrupted);
    }

    @Test
    public void rejectsFramesShorterThanMinimumLength() {
        for (int length = 0; length < 6; length++) {
            assertInvalid(Arrays.copyOf(CAPTURED_FRAME, length));
        }
    }

    @Test
    public void rejectsDeclaredLengthMismatch() {
        final byte[] declaredShorter = Arrays.copyOf(CAPTURED_FRAME, CAPTURED_FRAME.length);
        declaredShorter[2] = 0x07;
        assertInvalid(declaredShorter);

        final byte[] declaredLonger = Arrays.copyOf(CAPTURED_FRAME, CAPTURED_FRAME.length);
        declaredLonger[2] = 0x09;
        assertInvalid(declaredLonger);

        final byte[] trailingByte = Arrays.copyOf(CAPTURED_FRAME, CAPTURED_FRAME.length + 1);
        assertInvalid(trailingByte);
    }

    private static void assertInvalid(final byte[] frame) {
        try {
            YcbtFrameCodec.decode(frame);
            fail("Expected invalid frame");
        } catch (final IllegalArgumentException expected) {
            // Expected.
        }
    }
}
