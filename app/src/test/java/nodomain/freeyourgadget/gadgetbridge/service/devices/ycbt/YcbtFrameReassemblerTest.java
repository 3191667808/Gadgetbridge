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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class YcbtFrameReassemblerTest {
    private static final byte[] CAPTURED_FRAME = new byte[]{
            0x02, 0x00, 0x08, 0x00, 0x47, 0x43, 0x6f, (byte) 0xec
    };

    @Test
    public void reassemblesEveryFragmentationPoint() {
        for (int split = 1; split < CAPTURED_FRAME.length; split++) {
            final YcbtFrameReassembler reassembler = new YcbtFrameReassembler();

            assertTrue(reassembler.accept(Arrays.copyOfRange(CAPTURED_FRAME, 0, split)).isEmpty());
            assertCapturedFrame(reassembler.accept(Arrays.copyOfRange(CAPTURED_FRAME, split, CAPTURED_FRAME.length)));
        }
    }

    @Test
    public void reassemblesFragmentedHeaderAndBody() {
        final YcbtFrameReassembler reassembler = new YcbtFrameReassembler();

        for (int i = 0; i < CAPTURED_FRAME.length - 1; i++) {
            assertTrue(reassembler.accept(new byte[]{CAPTURED_FRAME[i]}).isEmpty());
        }
        assertCapturedFrame(reassembler.accept(new byte[]{CAPTURED_FRAME[CAPTURED_FRAME.length - 1]}));
    }

    @Test
    public void emitsConsecutiveFrames() {
        final byte[] secondFrame = YcbtFrameCodec.encode(0x03, 0x04, new byte[]{0x05});
        final byte[] consecutive = new byte[CAPTURED_FRAME.length + secondFrame.length];
        System.arraycopy(CAPTURED_FRAME, 0, consecutive, 0, CAPTURED_FRAME.length);
        System.arraycopy(secondFrame, 0, consecutive, CAPTURED_FRAME.length, secondFrame.length);

        final List<YcbtFrameCodec.Frame> frames = new YcbtFrameReassembler().accept(consecutive);

        assertEquals(2, frames.size());
        assertArrayEquals(new byte[]{0x47, 0x43}, frames.get(0).getPayload());
        assertEquals(0x03, frames.get(1).getGroup());
        assertEquals(0x04, frames.get(1).getCommand());
        assertArrayEquals(new byte[]{0x05}, frames.get(1).getPayload());
    }

    @Test
    public void doesNotEmitPartialInput() {
        final YcbtFrameReassembler reassembler = new YcbtFrameReassembler();

        assertTrue(reassembler.accept(Arrays.copyOf(CAPTURED_FRAME, CAPTURED_FRAME.length - 1)).isEmpty());
    }

    @Test
    public void clearsMalformedStateWithoutScanningForAnotherStart() {
        final YcbtFrameReassembler reassembler = new YcbtFrameReassembler();
        final byte[] corrupted = Arrays.copyOf(CAPTURED_FRAME, CAPTURED_FRAME.length);
        corrupted[6] ^= 0x01;

        assertTrue(reassembler.accept(corrupted).isEmpty());
        assertCapturedFrame(reassembler.accept(CAPTURED_FRAME));
    }

    private static void assertCapturedFrame(final List<YcbtFrameCodec.Frame> frames) {
        assertEquals(1, frames.size());
        assertEquals(0x02, frames.get(0).getGroup());
        assertEquals(0x00, frames.get(0).getCommand());
        assertArrayEquals(new byte[]{0x47, 0x43}, frames.get(0).getPayload());
    }
}
