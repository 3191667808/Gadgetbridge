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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.ycbt.YcbtConstants;

public class YcbtInboundRouterTest {
    private static final byte[] CAPTURED_FRAME = new byte[]{
            0x02, 0x00, 0x08, 0x00, 0x47, 0x43, 0x6f, (byte) 0xec
    };

    @Test
    public void usesCapturedGattUuidsAndDeterministicIndicationOrder() {
        assertEquals(UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5"), YcbtConstants.SERVICE_UUID);
        assertEquals(
                UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5"),
                YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID
        );
        assertEquals(
                UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5"),
                YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID
        );
        assertEquals(YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID, YcbtConstants.WRITE_CHARACTERISTIC_UUID);
        assertEquals(
                Arrays.asList(
                        YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID,
                        YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID
                ),
                YcbtInboundRouter.getInboundCharacteristicUuids()
        );
    }

    @Test
    public void acceptsOnlyBothVerifiedInboundCharacteristics() {
        final YcbtInboundRouter router = new YcbtInboundRouter();

        assertTrue(router.accepts(YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID));
        assertTrue(router.accepts(YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID));
        assertFalse(router.accepts(YcbtConstants.SERVICE_UUID));
        assertFalse(router.accepts(UUID.fromString("be940002-7333-be46-b7ae-689e71722bd5")));
        assertFalse(router.accepts(null));
    }

    @Test
    public void keepsReassemblyStateSeparateForEachInboundCharacteristic() {
        final YcbtInboundRouter router = new YcbtInboundRouter();

        final YcbtInboundRouter.RouteResult partialCommandReply = router.accept(
                YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID,
                Arrays.copyOfRange(CAPTURED_FRAME, 0, 3)
        );
        final YcbtInboundRouter.RouteResult completeStreamHistory = router.accept(
                YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID,
                CAPTURED_FRAME
        );
        final YcbtInboundRouter.RouteResult completeCommandReply = router.accept(
                YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID,
                Arrays.copyOfRange(CAPTURED_FRAME, 3, CAPTURED_FRAME.length)
        );

        assertTrue(partialCommandReply.isAccepted());
        assertTrue(partialCommandReply.getFrames().isEmpty());
        assertEquals(1, completeStreamHistory.getFrames().size());
        assertEquals(1, completeCommandReply.getFrames().size());
        assertEquals(0x02, completeCommandReply.getFrames().get(0).getGroup());
        assertEquals(0x00, completeCommandReply.getFrames().get(0).getCommand());
        assertArrayEquals(new byte[]{0x47, 0x43}, completeCommandReply.getFrames().get(0).getPayload());
    }

    @Test
    public void rejectsUnknownCharacteristicWithoutChangingReassemblyState() {
        final YcbtInboundRouter router = new YcbtInboundRouter();

        final YcbtInboundRouter.RouteResult rejected = router.accept(
                UUID.fromString("be940002-7333-be46-b7ae-689e71722bd5"),
                CAPTURED_FRAME
        );
        final YcbtInboundRouter.RouteResult accepted = router.accept(
                YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID,
                CAPTURED_FRAME
        );

        assertFalse(rejected.isAccepted());
        assertEquals(Collections.emptyList(), rejected.getFrames());
        assertTrue(accepted.isAccepted());
        assertEquals(1, accepted.getFrames().size());
    }

    @Test
    public void reportsMalformedFrameAndRecoversForNextFrame() {
        final YcbtInboundRouter router = new YcbtInboundRouter();
        final byte[] corrupted = Arrays.copyOf(CAPTURED_FRAME, CAPTURED_FRAME.length);
        corrupted[6] ^= 0x01;

        final YcbtInboundRouter.RouteResult malformed = router.accept(
                YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID,
                corrupted
        );
        final YcbtInboundRouter.RouteResult recovered = router.accept(
                YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID,
                CAPTURED_FRAME
        );

        assertTrue(malformed.isAccepted());
        assertTrue(malformed.getFrames().isEmpty());
        assertNotNull(malformed.getMalformedReason());
        assertTrue(malformed.getMalformedReason().contains("CRC"));
        assertEquals(1, recovered.getFrames().size());
    }

    @Test
    public void reportsNullChunkWithoutThrowing() {
        final YcbtInboundRouter.RouteResult result = new YcbtInboundRouter().accept(
                YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID,
                null
        );

        assertTrue(result.isAccepted());
        assertTrue(result.getFrames().isEmpty());
        assertNotNull(result.getMalformedReason());
    }
}
