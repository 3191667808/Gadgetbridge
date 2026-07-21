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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YcbtProtocolTest {
    private static final byte[] CAPTURED_MODEL_REQUEST = new byte[]{
            0x02, 0x03, 0x08, 0x00, 0x47, 0x50, (byte) 0xef, 0x20
    };
    private static final byte[] CAPTURED_MODEL_RESPONSE = new byte[]{
            0x02, 0x03, 0x0b, 0x00, 0x52, 0x31, 0x31, 0x4d, 0x00, (byte) 0xa6, (byte) 0xdf
    };
    private static final byte[] CAPTURED_BATTERY_REQUEST = new byte[]{
            0x02, 0x00, 0x08, 0x00, 0x47, 0x43, 0x6f, (byte) 0xec
    };
    private static final byte[] CAPTURED_BATTERY_RESPONSE = new byte[]{
            0x02, 0x00, 0x1e, 0x00, (byte) 0xa3, 0x00, 0x20, 0x02,
            0x00, 0x34, 0x00, 0x01, 0x00, 0x03, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, (byte) 0xe3, (byte) 0xec
    };
    private static final byte[] CAPTURED_CAPABILITY_REQUEST = new byte[]{
            0x02, 0x01, 0x08, 0x00, 0x47, 0x46, (byte) 0x9b, 0x16
    };
    private static final byte[] CAPTURED_CAPABILITY_RESPONSE = new byte[]{
            0x02, 0x01, 0x42, 0x00,
            (byte) 0xf9, 0x09, 0x00, 0x00, 0x00, 0x00, 0x0c, (byte) 0xd8,
            0x10, 0x04, 0x01, (byte) 0xb2, (byte) 0xb6, 0x00, 0x40, 0x0f,
            0x00, 0x14, 0x50, 0x00, 0x00, 0x00, 0x20, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x2e, 0x69
    };
    private static final byte[] DOCUMENTED_BLOOD_PRESSURE_START = new byte[]{
            0x03, 0x2f, 0x08, 0x00, 0x01, 0x01, 0x6e, 0x0b
    };
    private static final byte[] DOCUMENTED_BLOOD_PRESSURE_STOP = new byte[]{
            0x03, 0x2f, 0x08, 0x00, 0x00, 0x01, 0x5f, 0x38
    };
    private static final byte[] FIRST_PARTY_BLOOD_PRESSURE_RESULT = new byte[]{
            0x06, 0x03, 0x14, 0x00, 0x6f, 0x4a, 0x44, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x74, (byte) 0xf1
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
    public void buildsCapturedBatteryRequestExactly() {
        assertArrayEquals(CAPTURED_BATTERY_REQUEST, YcbtProtocol.buildBatteryRequest());
    }

    @Test
    public void parsesCapturedBatteryResponse() {
        assertEquals(Integer.valueOf(52),
                YcbtProtocol.parseBatteryLevel(YcbtFrameCodec.decode(CAPTURED_BATTERY_RESPONSE)));
    }

    @Test
    public void rejectsShortOrOutOfRangeBatteryResponses() {
        assertNull(YcbtProtocol.parseBatteryLevel(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x02, 0x00, new byte[]{0x00, 0x00, 0x00, 0x00, 0x00})
        )));
        assertNull(YcbtProtocol.parseBatteryLevel(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x02, 0x00, new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 101})
        )));
        assertNull(YcbtProtocol.parseBatteryLevel(YcbtFrameCodec.decode(CAPTURED_MODEL_RESPONSE)));
    }

    @Test
    public void buildsCapturedCapabilityRequestExactly() {
        assertArrayEquals(CAPTURED_CAPABILITY_REQUEST, YcbtProtocol.buildCapabilityRequest());
    }

    @Test
    public void parsesCapturedCapabilityResponse() {
        final YcbtProtocol.Capabilities capabilities = YcbtProtocol.parseCapabilities(
                YcbtFrameCodec.decode(CAPTURED_CAPABILITY_RESPONSE)
        );

        assertTrue(capabilities.hasBloodPressure());
        assertFalse(capabilities.hasTemperature());
        assertFalse(capabilities.hasFindDevice());
        assertFalse(capabilities.hasBloodSugar());
    }

    @Test
    public void rejectsCapabilityResponsesWithUnexpectedCommandOrPayloadLength() {
        assertNull(YcbtProtocol.parseCapabilities(YcbtFrameCodec.decode(CAPTURED_BATTERY_RESPONSE)));
        assertNull(YcbtProtocol.parseCapabilities(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x01, 0x01, new byte[60])
        )));
        assertNull(YcbtProtocol.parseCapabilities(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x02, 0x01, new byte[59])
        )));
        assertNull(YcbtProtocol.parseCapabilities(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x02, 0x01, new byte[61])
        )));
    }

    @Test
    public void parsesOnlyDocumentedCapabilityBitPositions() {
        final byte[] payload = new byte[60];
        payload[0] = 1 << 0;
        payload[6] = 1 << 4;
        payload[8] = 1 << 0;
        payload[17] = 1 << 3;

        final YcbtProtocol.Capabilities capabilities = YcbtProtocol.parseCapabilities(
                YcbtFrameCodec.decode(YcbtFrameCodec.encode(0x02, 0x01, payload))
        );

        assertTrue(capabilities.hasBloodPressure());
        assertTrue(capabilities.hasTemperature());
        assertTrue(capabilities.hasFindDevice());
        assertTrue(capabilities.hasBloodSugar());
    }

    @Test
    public void buildsDocumentedBloodPressureControlFramesExactly() {
        assertArrayEquals(DOCUMENTED_BLOOD_PRESSURE_START, YcbtProtocol.buildBloodPressureStartRequest());
        assertArrayEquals(DOCUMENTED_BLOOD_PRESSURE_STOP, YcbtProtocol.buildBloodPressureStopRequest());
    }

    @Test
    public void parsesExactBloodPressureControlReply() {
        assertEquals(Integer.valueOf(0), YcbtProtocol.parseBloodPressureControlReply(
                YcbtFrameCodec.decode(new byte[]{0x03, 0x2f, 0x07, 0x00, 0x00, (byte) 0xee, (byte) 0x99})
        ));
        assertEquals(Integer.valueOf(2), YcbtProtocol.parseBloodPressureControlReply(
                YcbtFrameCodec.decode(YcbtFrameCodec.encode(0x03, 0x2f, new byte[]{0x02}))
        ));
        assertNull(YcbtProtocol.parseBloodPressureControlReply(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x03, 0x2f, new byte[]{0x00, 0x01})
        )));
        assertNull(YcbtProtocol.parseBloodPressureControlReply(YcbtFrameCodec.decode(CAPTURED_MODEL_RESPONSE)));
    }

    @Test
    public void parsesFirstPartyBloodPressureResult() {
        final YcbtProtocol.BloodPressure result = YcbtProtocol.parseBloodPressureResult(
                YcbtFrameCodec.decode(FIRST_PARTY_BLOOD_PRESSURE_RESULT)
        );

        assertEquals(111, result.getSystolic());
        assertEquals(74, result.getDiastolic());
    }

    @Test
    public void rejectsBloodPressureResultsWithWrongShapeOrImplausibleValues() {
        assertNull(YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, new byte[13])
        )));
        assertNull(YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, new byte[15])
        )));

        final byte[] lowSystolic = new byte[14];
        lowSystolic[0] = 59;
        lowSystolic[1] = 74;
        assertNull(YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, lowSystolic)
        )));

        final byte[] highDiastolic = new byte[14];
        highDiastolic[0] = 111;
        highDiastolic[1] = (byte) 151;
        assertNull(YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, highDiastolic)
        )));

        final byte[] highSystolic = new byte[14];
        highSystolic[0] = (byte) 251;
        highSystolic[1] = 74;
        assertNull(YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, highSystolic)
        )));

        final byte[] lowDiastolic = new byte[14];
        lowDiastolic[0] = 111;
        lowDiastolic[1] = 29;
        assertNull(YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, lowDiastolic)
        )));
        assertNull(YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x04, 0x03, new byte[14])
        )));
    }

    @Test
    public void acceptsBloodPressurePlausibilityBoundaries() {
        final byte[] minimum = new byte[14];
        minimum[0] = 60;
        minimum[1] = 30;
        assertEquals(60, YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, minimum)
        )).getSystolic());

        final byte[] maximum = new byte[14];
        maximum[0] = (byte) 250;
        maximum[1] = (byte) 150;
        assertEquals(150, YcbtProtocol.parseBloodPressureResult(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03, maximum)
        )).getDiastolic());
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
