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

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;

public class YcbtProtocolTest {
    @Test
    public void framesLogicalHistoryCommands() {
        assertArrayEquals(new byte[]{0x05, 0x06, 0x06, 0x00, (byte) 0x83, 0x20},
                YcbtProtocol.frameLogicalCommand(new byte[]{0x05, 0x06}));
        assertArrayEquals(new byte[]{0x05, (byte) 0x80, 0x07, 0x00, 0x00, (byte) 0xf3, 0x6a},
                YcbtProtocol.frameLogicalCommand(new byte[]{0x05, (byte) 0x80, 0x00}));
        assertArrayEquals(new byte[]{0x05, (byte) 0x80, 0x07, 0x00, 0x04, 0x77, 0x2a},
                YcbtProtocol.frameLogicalCommand(new byte[]{0x05, (byte) 0x80, 0x04}));
    }

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
    public void buildsDocumentedLocalTimeFrame() {
        assertArrayEquals(
                new byte[]{
                        0x01, 0x00, 0x0e, 0x00,
                        (byte) 0xea, 0x07, 0x07, 0x16, 0x0e, 0x05, 0x06, 0x02,
                        (byte) 0xa9, (byte) 0x84
                },
                YcbtProtocol.buildSetTimeRequest(
                        Instant.parse("2026-07-22T14:05:06Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    public void parsesCapturedCapabilityResponse() {
        final YcbtProtocol.Capabilities capabilities = YcbtProtocol.parseCapabilities(
                YcbtFrameCodec.decode(CAPTURED_CAPABILITY_RESPONSE)
        );

        assertTrue(capabilities.hasBloodPressure());
        assertTrue(capabilities.hasSteps());
        assertTrue(capabilities.hasSleep());
        assertTrue(capabilities.hasHeartRate());
        assertTrue(capabilities.hasSpo2());
        assertFalse(capabilities.hasHrv());
        assertTrue(capabilities.hasManualHeartRate());
        assertTrue(capabilities.hasManualBloodPressure());
        assertTrue(capabilities.hasManualSpo2());
        assertFalse(capabilities.hasTemperature());
        assertFalse(capabilities.hasFindDevice());
        assertFalse(capabilities.hasBloodSugar());
    }

    @Test
    public void acceptsVariableLengthCapabilitiesAndRejectsTruncatedCorePayloads() {
        assertNull(YcbtProtocol.parseCapabilities(YcbtFrameCodec.decode(CAPTURED_BATTERY_RESPONSE)));
        assertNull(YcbtProtocol.parseCapabilities(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x01, 0x01, new byte[60])
        )));
        assertNull(YcbtProtocol.parseCapabilities(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x02, 0x01, new byte[13])
        )));

        final byte[] corePayload = new byte[14];
        corePayload[0] = (byte) 0xc9;
        corePayload[1] = 0x0a;
        final YcbtProtocol.Capabilities capabilities = YcbtProtocol.parseCapabilities(
                YcbtFrameCodec.decode(YcbtFrameCodec.encode(0x02, 0x01, corePayload))
        );
        assertTrue(capabilities.hasSteps());
        assertTrue(capabilities.hasSleep());
        assertTrue(capabilities.hasHeartRate());
        assertTrue(capabilities.hasBloodPressure());
        assertTrue(capabilities.hasSpo2());
        assertTrue(capabilities.hasHrv());
        assertFalse(capabilities.hasBloodSugar());
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
    public void selectsCapabilityGatedHistoryTypesWithoutUnsupportedDedicatedSpo2() {
        final byte[] payload = new byte[24];
        payload[0] = (byte) 0xc9;
        payload[1] = 0x0a;
        payload[8] = 0x01;
        payload[17] = 0x08;
        payload[22] = 0x40;
        final YcbtProtocol.Capabilities capabilities = YcbtProtocol.parseCapabilities(
                YcbtFrameCodec.decode(YcbtFrameCodec.encode(0x02, 0x01, payload))
        );

        assertEquals(Arrays.asList(
                        YcbtHistoryTransfer.HistoryType.SPORT,
                        YcbtHistoryTransfer.HistoryType.SLEEP,
                        YcbtHistoryTransfer.HistoryType.HEART_RATE,
                        YcbtHistoryTransfer.HistoryType.BLOOD_PRESSURE,
                        YcbtHistoryTransfer.HistoryType.VITALS,
                        YcbtHistoryTransfer.HistoryType.TEMPERATURE,
                        YcbtHistoryTransfer.HistoryType.COMPREHENSIVE,
                        YcbtHistoryTransfer.HistoryType.BODY_DATA
                ),
                YcbtDeviceSupport.historyTypesFor(RecordedDataTypes.TYPE_SYNC, capabilities));
        assertEquals(Arrays.asList(YcbtHistoryTransfer.HistoryType.SLEEP),
                YcbtDeviceSupport.historyTypesFor(RecordedDataTypes.TYPE_SLEEP, capabilities));
        assertEquals(Arrays.asList(YcbtHistoryTransfer.HistoryType.VITALS),
                YcbtDeviceSupport.historyTypesFor(RecordedDataTypes.TYPE_SPO2, capabilities));
    }

    @Test
    public void buildsDocumentedBloodPressureControlFramesExactly() {
        assertArrayEquals(DOCUMENTED_BLOOD_PRESSURE_START, YcbtProtocol.buildBloodPressureStartRequest());
        assertArrayEquals(DOCUMENTED_BLOOD_PRESSURE_STOP, YcbtProtocol.buildBloodPressureStopRequest());
    }

    @Test
    public void buildsDocumentedHeartRateAndFindDeviceFrames() {
        assertArrayEquals(
                YcbtFrameCodec.encode(0x03, 0x2f, new byte[]{0x01, 0x00}),
                YcbtProtocol.buildHeartRateStartRequest()
        );
        assertArrayEquals(
                YcbtFrameCodec.encode(0x03, 0x2f, new byte[]{0x00, 0x00}),
                YcbtProtocol.buildHeartRateStopRequest()
        );
        assertArrayEquals(
                YcbtFrameCodec.encode(0x03, 0x2f, new byte[]{0x01, 0x02}),
                YcbtProtocol.buildSpo2StartRequest()
        );
        assertArrayEquals(
                YcbtFrameCodec.encode(0x03, 0x2f, new byte[]{0x00, 0x02}),
                YcbtProtocol.buildSpo2StopRequest()
        );
        assertArrayEquals(
                YcbtFrameCodec.encode(0x03, 0x00, new byte[]{0x01, 0x05, 0x02}),
                YcbtProtocol.buildFindDeviceRequest()
        );
    }

    @Test
    public void buildsDocumentedAutomaticMonitoringFrames() {
        assertArrayEquals(
                new byte[]{0x01, 0x0c, 0x08, 0x00, 0x01, 0x1e, (byte) 0x96, (byte) 0x85},
                YcbtProtocol.buildHeartRateMonitoringRequest(true, 30)
        );
        assertArrayEquals(
                new byte[]{0x01, 0x26, 0x08, 0x00, 0x01, 0x3c, (byte) 0xac, (byte) 0xcf},
                YcbtProtocol.buildSpo2MonitoringRequest(true, 60)
        );
    }

    @Test
    public void normalizesAutomaticMonitoringIntervalsToFirmwareLimits() {
        assertEquals(60, YcbtProtocol.normalizeMonitoringInterval(0));
        assertEquals(30, YcbtProtocol.normalizeMonitoringInterval(5));
        assertEquals(255, YcbtProtocol.normalizeMonitoringInterval(360));
        assertArrayEquals(
                new byte[]{0x00, 0x3c},
                YcbtFrameCodec.decode(YcbtProtocol.buildHeartRateMonitoringRequest(false, 0)).getPayload()
        );
        assertArrayEquals(
                new byte[]{0x01, 0x1e},
                YcbtFrameCodec.decode(YcbtProtocol.buildHeartRateMonitoringRequest(true, 5)).getPayload()
        );
        assertArrayEquals(
                new byte[]{0x01, (byte) 0xff},
                YcbtFrameCodec.decode(YcbtProtocol.buildSpo2MonitoringRequest(true, 360)).getPayload()
        );
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
        assertEquals(68, result.getPulse());
    }

    @Test
    public void decodesFirstPartyLiveStreams() {
        final YcbtProtocol.Activity activity = YcbtProtocol.parseLiveActivity(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x00, new byte[]{0x34, 0x12, 0x78, 0x56, (byte) 0xbc, (byte) 0x9a})
        ));
        assertEquals(0x1234, activity.getSteps());
        assertEquals(0x5678, activity.getDistanceMeters());
        assertEquals(0x9abc, activity.getCalories());

        assertEquals(Integer.valueOf(68), YcbtProtocol.parseLiveHeartRate(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x01, new byte[]{68})
        )));
        assertEquals(Integer.valueOf(97), YcbtProtocol.parseLiveSpo2(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x02, new byte[]{97})
        )));
        assertEquals(Integer.valueOf(68), YcbtProtocol.parseLiveBattery(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x15, new byte[]{0x00, 68})
        )));

        final YcbtProtocol.LiveVitals vitals = YcbtProtocol.parseLiveVitals(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x03,
                        new byte[]{111, 74, 68, 42, 98, 36, 5, 0, 0, 0, 0, 0, 0, 0})
        ));
        assertEquals(68, vitals.getHeartRate());
        assertEquals(42, vitals.getHrv());
        assertEquals(98, vitals.getSpo2());
        assertEquals(36.5, vitals.getTemperatureCelsius(), 0.001);
    }

    @Test
    public void rejectsImplausibleOrMalformedLiveStreams() {
        assertNull(YcbtProtocol.parseLiveActivity(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x00, new byte[5])
        )));
        assertNull(YcbtProtocol.parseLiveHeartRate(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x01, new byte[]{29})
        )));
        assertNull(YcbtProtocol.parseLiveSpo2(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x02, new byte[]{69})
        )));
        assertNull(YcbtProtocol.parseLiveBattery(YcbtFrameCodec.decode(
                YcbtFrameCodec.encode(0x06, 0x15, new byte[]{0x00, 101})
        )));
    }

    @Test
    public void buildsFirstPartyLiveActivityRequest() {
        final YcbtFrameCodec.Frame frame = YcbtFrameCodec.decode(YcbtProtocol.buildLiveActivityRequest());

        assertEquals(0x03, frame.getGroup());
        assertEquals(0x09, frame.getCommand());
        assertArrayEquals(new byte[]{0x01, 0x00, 0x02}, frame.getPayload());
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
