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
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YcbtProtocolTest {
    private static final byte[] CAPTURED_MODEL_REQUEST = new byte[]{
            0x02, 0x03, 0x08, 0x00, 0x47, 0x50, (byte) 0xef, 0x20
    };
    private static final byte[] CAPTURED_MODEL_RESPONSE = new byte[]{
            0x02, 0x03, 0x0b, 0x00, 0x52, 0x31, 0x31, 0x4d, 0x00, (byte) 0xa6, (byte) 0xdf
    };

    @Test
    public void buildsCapturedModelRequestExactly() {
        assertArrayEquals(CAPTURED_MODEL_REQUEST, YcbtProtocol.buildModelRequest());
    }

    @Test
    public void parsesCapturedModelResponse() {
        assertEquals("R11M", YcbtProtocol.parseModelResponse(YcbtFrameCodec.decode(CAPTURED_MODEL_RESPONSE)));
    }

    @Test
    public void ignoresOtherResponses() {
        assertNull(YcbtProtocol.parseModelResponse(YcbtFrameCodec.decode(new byte[]{
                0x02, 0x00, 0x08, 0x00, 0x47, 0x43, 0x6f, (byte) 0xec
        })));
    }

    @Test
    public void rejectsUnterminatedOrNonAsciiModelPayloads() {
        assertNull(YcbtProtocol.parseModelResponse(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x02, 0x03, new byte[]{0x52, 0x31, 0x31, 0x4d})
        )));
        assertNull(YcbtProtocol.parseModelResponse(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x02, 0x03, new byte[]{0x52, 0x00, 0x31})
        )));
    }
}
