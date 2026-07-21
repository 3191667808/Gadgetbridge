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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YcbtBloodPressureOperationTest {
    private final YcbtBloodPressureOperation operation = new YcbtBloodPressureOperation();

    @Test
    public void completesOneAcceptedMeasurementAndStopReply() {
        assertTrue(operation.requestStart());
        assertEquals(YcbtBloodPressureOperation.State.START_QUEUED, operation.getState());
        assertEquals(YcbtBloodPressureOperation.Reply.IGNORED, operation.handleReply(0));
        assertTrue(operation.markStartRequested());
        assertEquals(YcbtBloodPressureOperation.State.WAITING_START_REPLY, operation.getState());
        assertEquals(YcbtBloodPressureOperation.Reply.START_ACCEPTED, operation.handleReply(0));
        assertEquals(YcbtBloodPressureOperation.State.MEASURING, operation.getState());

        assertTrue(operation.handleResult());
        assertEquals(YcbtBloodPressureOperation.State.STOP_QUEUED, operation.getState());
        assertEquals(YcbtBloodPressureOperation.Reply.IGNORED, operation.handleReply(0));
        assertTrue(operation.markStopRequested());
        assertEquals(YcbtBloodPressureOperation.State.WAITING_STOP_REPLY, operation.getState());
        assertEquals(YcbtBloodPressureOperation.Reply.STOP_REPLY, operation.handleReply(0));
        assertEquals(YcbtBloodPressureOperation.State.IDLE, operation.getState());
    }

    @Test
    public void rejectsARefusedStartWithoutSendingStop() {
        assertTrue(operation.requestStart());
        assertTrue(operation.markStartRequested());
        assertEquals(YcbtBloodPressureOperation.Reply.START_REJECTED, operation.handleReply(3));
        assertEquals(YcbtBloodPressureOperation.State.IDLE, operation.getState());
        assertFalse(operation.handleResult());
    }

    @Test
    public void ignoresDuplicateAndOutOfOrderEvents() {
        assertEquals(YcbtBloodPressureOperation.Reply.IGNORED, operation.handleReply(0));
        assertFalse(operation.handleResult());

        assertTrue(operation.requestStart());
        assertFalse(operation.requestStart());
        assertFalse(operation.handleResult());
        assertTrue(operation.markStartRequested());
        assertFalse(operation.markStartRequested());
        assertEquals(YcbtBloodPressureOperation.Reply.START_ACCEPTED, operation.handleReply(0));
        assertEquals(YcbtBloodPressureOperation.Reply.IGNORED, operation.handleReply(0));

        assertTrue(operation.handleResult());
        assertFalse(operation.handleResult());
        assertTrue(operation.markStopRequested());
        assertFalse(operation.markStopRequested());
        assertEquals(YcbtBloodPressureOperation.Reply.STOP_REPLY, operation.handleReply(5));
        assertEquals(YcbtBloodPressureOperation.Reply.IGNORED, operation.handleReply(0));
    }

    @Test
    public void cancellationMakesLateEventsInert() {
        assertTrue(operation.requestStart());
        assertTrue(operation.cancel());
        assertEquals(YcbtBloodPressureOperation.State.IDLE, operation.getState());
        assertEquals(YcbtBloodPressureOperation.Reply.IGNORED, operation.handleReply(0));
        assertFalse(operation.handleResult());
        assertFalse(operation.cancel());
    }

    @Test
    public void cleanupStopSupersedesAQueuedNormalStop() {
        assertTrue(operation.requestStart());
        assertTrue(operation.markStartRequested());
        assertEquals(YcbtBloodPressureOperation.Reply.START_ACCEPTED, operation.handleReply(0));
        assertTrue(operation.handleResult());

        assertTrue(operation.requestCleanupStop());
        assertEquals(YcbtBloodPressureOperation.State.CLEANUP_STOP_QUEUED, operation.getState());
        assertFalse(operation.markStopRequested());
        assertTrue(operation.markCleanupStopRequested());
        assertEquals(YcbtBloodPressureOperation.State.CLEANUP_STOP_SENT, operation.getState());
        assertEquals(YcbtBloodPressureOperation.Reply.IGNORED, operation.handleReply(0));
        assertTrue(operation.finishCleanup());
        assertEquals(YcbtBloodPressureOperation.State.IDLE, operation.getState());
    }
}
