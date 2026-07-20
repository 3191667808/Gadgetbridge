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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class YcbtPairingTraceTest {
    @Test
    public void formatsAndAccumulatesEvents() {
        final YcbtPairingTrace trace = new YcbtPairingTrace();

        trace.beginAttempt();
        trace.append("initialize entered", "12:34:56.789");
        trace.append("command/reply found", "12:34:57.001");

        assertEquals(
                "YCBT pairing diagnostics\n\nAttempt 1\n01. 12:34:56.789 initialize entered\n02. 12:34:57.001 command/reply found",
                trace.format()
        );
    }

    @Test
    public void keepsAttemptBoundariesAndRestartsEventNumbers() {
        final YcbtPairingTrace trace = new YcbtPairingTrace();

        trace.beginAttempt();
        trace.append("disconnected status=133", "12:35:00.000");
        trace.beginAttempt();
        trace.append("connect requested", "12:36:00.000");

        assertEquals(
                "YCBT pairing diagnostics\n\nAttempt 1\n01. 12:35:00.000 disconnected status=133\n\nAttempt 2\n01. 12:36:00.000 connect requested",
                trace.format()
        );
        assertEquals(2, trace.getAttemptNumber());
        assertEquals(1, trace.getEventNumber());
    }

    @Test
    public void restoredTraceContinuesCurrentAttempt() {
        final YcbtPairingTrace original = new YcbtPairingTrace();
        original.beginAttempt();
        original.append("stream/history properties=0x00000020", "12:37:00.000");

        final YcbtPairingTrace restored = new YcbtPairingTrace(
                original.getAttemptNumber(),
                original.getEventNumber(),
                original.getLines()
        );
        restored.append("stream/history CCCD present", "12:37:00.100");

        assertEquals(
                "YCBT pairing diagnostics\n\nAttempt 1\n01. 12:37:00.000 stream/history properties=0x00000020\n02. 12:37:00.100 stream/history CCCD present",
                restored.format()
        );
    }
}
