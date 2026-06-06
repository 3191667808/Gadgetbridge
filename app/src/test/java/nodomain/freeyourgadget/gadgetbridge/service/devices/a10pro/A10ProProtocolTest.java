/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;

public class A10ProProtocolTest {
    private static final A10ProProtocol P = new A10ProProtocol(null);

    @Test public void getFunction_isTwoBytes() { assertArrayEquals(new byte[]{0x03, 0x00}, P.encodeGetFunction()); }
    @Test public void getFirmwareVersion_isSingleByte() { assertArrayEquals(new byte[]{0x1F}, P.encodeGetFirmwareVersion()); }
    @Test public void classicBatteryQuery_isDc4() { assertArrayEquals(new byte[]{0x14}, P.encodeClassicBatteryQuery()); }
    @Test public void reset_hasGuardBytes() { assertArrayEquals(new byte[]{0x71, 0x01, 0x02, 0x03}, P.encodeReset()); }
    @Test public void turnOff_emitsAdPlusModel() { assertArrayEquals(new byte[]{(byte) 0xAD, 0x02}, P.encodeTurnOff(2)); }
    @Test public void findBand_usesD1() { assertArrayEquals(new byte[]{(byte) 0xD1, 0x01}, P.encodeFindBand(true)); }
    @Test public void antiLost_uses70() { assertArrayEquals(new byte[]{0x70, 0x01}, P.encodeAntiLost(true)); }
    @Test public void camera_uses52() { assertArrayEquals(new byte[]{0x52, 0x00}, P.encodeCameraControl(false)); }
    @Test public void unit_metricCelsius() { assertArrayEquals(new byte[]{0x11, 0, 0, 0, 0}, P.encodeUnit(true, true)); }

    @Test public void queryBattery_isFbPrefixedReadWithChecksum() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x03, 0x00, 0x06, 0x04, 0x01}, P.encodeQueryBattery()); }
    @Test public void queryEq_isFbPrefixed() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x01, 0x00, 0x06, 0x02, 0x01}, P.encodeQueryEq()); }
    @Test public void queryKeyCode_isFbPrefixed() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x02, 0x00, 0x06, 0x03, 0x01}, P.encodeQueryKeyCode()); }
    @Test public void queryBlueName_isFbPrefixed() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x04, 0x00, 0x06, 0x05, 0x01}, P.encodeQueryBlueName()); }
    @Test public void queryAnc_isFbPrefixed() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x05, 0x00, 0x06, 0x06, 0x01}, P.encodeQueryAnc()); }
    @Test public void queryAudio_isChecksummed() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x07, 0x00, 0x06, 0x08, 0x01}, P.encodeQueryAudio()); }

    @Test public void setAnc_appendsIndexAndChecksum() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x05, 0x01, 0x07, 0x02, 0x0A, 0x01}, P.encodeSetAnc(2)); }
    @Test public void setAudioModel_movie() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x07, 0x01, 0x07, 0x01, 0x0B, 0x01}, P.encodeSetAudioModel(1)); }
    @Test public void findHeadphones_both() { assertArrayEquals(new byte[]{(byte) 0xFB, 0x06, 0x01, 0x07, 0x03, 0x0C, 0x01}, P.encodeFindHeadphones(3)); }

    @Test public void setEq_packs10BandsCorrectly() { byte[] frame = P.encodeSetEq(0, new byte[]{1,2,3,4,5,6,7,8,9,10}); assertEquals(17, frame.length); assertEquals(0x11, frame[3]); assertEquals(10, frame[14]); }
    @Test(expected = IllegalArgumentException.class) public void setEq_rejectsWrongBandCount() { P.encodeSetEq(0, new byte[]{1, 2, 3}); }
    @Test public void setKeyCode_packs9Slots() { byte[] frame = P.encodeSetKeyCode(new byte[]{1,2,3,4,5,6,7,8,9}); assertEquals(15, frame.length); assertEquals(0x0F, frame[3]); assertEquals(9, frame[12]); }
    @Test(expected = IllegalArgumentException.class) public void setKeyCode_rejectsWrongSlotCount() { P.encodeSetKeyCode(new byte[]{1}); }
    @Test public void setBlueName_truncatesWhenOverflow() { assertNull(P.encodeSetBlueName("WAY_TOO_LONG_NAME", 4)); }
    @Test public void setBlueName_packsAscii() { byte[] frame = P.encodeSetBlueName("Buds", 16); assertEquals(0x04, frame[1]); assertEquals(0x0A, frame[3]); assertEquals('B', frame[4]); assertEquals('s', frame[7]); }

    @Test public void syncTime_isElevenBytes_with24HourFlag() { byte[] frame = P.encodeSyncTime(true, 0); assertEquals(11, frame.length); assertEquals(0x01, frame[0]); assertEquals(0x01, frame[9]); assertEquals(0x00, frame[10]); }
    @Test public void syncTime_12HourEncodedAs2() { assertEquals(0x02, P.encodeSyncTime(false, 0)[9]); }

    @Test public void weatherFir_isFourBytes() { assertArrayEquals(new byte[]{0x05, 0x0B, 18, 22}, P.encodeWeatherFir(11, 18, 22)); }
    @Test public void weatherJl_isTwentyBytes() { byte[] frame = P.encodeWeatherJl(3, 18, 24, 16, 55, 3, 1); assertEquals(20, frame.length); assertEquals(0x10, frame[0]); assertEquals(3, frame[2]); assertEquals(55, frame[6]); }
    @Test public void weatherZk_padsCityWithFf() { byte[] frame = P.encodeWeatherZk(25, 30, 0, "Tel Aviv"); assertEquals(20, frame.length); assertEquals(0x25, frame[0]); assertEquals('T', frame[4]); assertEquals((byte) 0xFF, frame[19]); }
    @Test public void unknownFamilyWeather_emitsAllThreeVariants() { List<byte[]> frames = P.encodeWeatherForFamily(A10ProProtocol.DeviceFamily.UNKNOWN, 0, 22, 28, 16, 50, 3, 1, "TA"); assertEquals(3, frames.size()); assertEquals(0x05, frames.get(0)[0]); assertEquals(0x10, frames.get(1)[0]); assertEquals(0x25, frames.get(2)[0]); }

    @Test public void gpsAddress_usesUtf16LeBomHeader() { List<byte[]> packets = P.encodeGpsAddress("AB"); assertEquals(1, packets.size()); assertArrayEquals(new byte[]{0x07, 0x00, (byte) 0xFF, (byte) 0xFE, 0x41, 0x00, 0x42, 0x00}, java.util.Arrays.copyOf(packets.get(0), 8)); }
    @Test public void gpsAddress_longStringMultipackets() { List<byte[]> packets = P.encodeGpsAddress("12345678901234567890"); assertEquals(3, packets.size()); assertEquals(0x07, packets.get(1)[0]); assertEquals(0x01, packets.get(1)[1]); }

    @Test public void notificationFir_uses73Chunks() { List<byte[]> frames = P.encodeNotification(2, "hello", A10ProProtocol.DeviceFamily.FIR); assertEquals(1, frames.size()); assertEquals(0x73, frames.get(0)[0]); assertEquals(0, frames.get(0)[1]); assertEquals(2, frames.get(0)[2]); }
    @Test public void notificationZk_uses23AndEndMarker() { List<byte[]> frames = P.encodeNotification(4, "hello", A10ProProtocol.DeviceFamily.ZK); assertEquals(1, frames.size()); assertEquals(0x23, frames.get(0)[0]); assertEquals((byte) 0xFF, frames.get(0)[frames.get(0).length - 1]); }
    @Test public void notificationLongTextChunksAt17Bytes() { List<byte[]> frames = P.encodeNotification(1, "abcdefghijklmnopqr", A10ProProtocol.DeviceFamily.JL); assertEquals(2, frames.size()); assertEquals(0, frames.get(0)[1]); assertEquals(1, frames.get(1)[1]); }

    @Test public void decodeBattery_extractsLevelsAndChargingFlags() { byte[] data = {(byte) 0xFB, 0x03, 0x03, 0x08, (byte) 0x95, 0x32, 0x00, 0x00}; GBDeviceEvent[] events = P.decodeBattery(data); assertEquals(2, events.length); GBDeviceEventBatteryInfo left = (GBDeviceEventBatteryInfo) events[0]; GBDeviceEventBatteryInfo right = (GBDeviceEventBatteryInfo) events[1]; assertEquals(0, left.batteryIndex); assertEquals(21, left.level); assertEquals(BatteryState.BATTERY_CHARGING, left.state); assertEquals(1, right.batteryIndex); assertEquals(50, right.level); assertEquals(BatteryState.BATTERY_NORMAL, right.state); }
    @Test public void decodeBattery_threePayloadBytesAddsCase() { byte[] data = {(byte) 0xFB, 0x03, 0x03, 0x09, 10, 20, 30, 0, 0}; assertEquals(3, P.decodeBattery(data).length); }
    @Test public void decodeClassicBattery_extractsTwoBuds() { GBDeviceEvent[] events = P.decodeClassicBattery(new byte[]{(byte) 0x94, 11, (byte) 0x8C}); assertEquals(2, events.length); assertEquals(BatteryState.BATTERY_CHARGING, ((GBDeviceEventBatteryInfo) events[1]).state); }
    @Test public void decodeWeatherAckDetectsZk() { P.setFamily(A10ProProtocol.DeviceFamily.UNKNOWN); P.decodeResponse(new byte[]{(byte) 0xA5, 0}); assertEquals(A10ProProtocol.DeviceFamily.ZK, P.getFamily()); }
    @Test public void decodeWeatherAckDetectsJl() { P.setFamily(A10ProProtocol.DeviceFamily.UNKNOWN); P.decodeResponse(new byte[]{(byte) 0x90, 0}); assertEquals(A10ProProtocol.DeviceFamily.JL, P.getFamily()); }
    @Test public void decodeWeatherAckDetectsFir() { P.setFamily(A10ProProtocol.DeviceFamily.UNKNOWN); P.decodeResponse(new byte[]{(byte) 0x85, 0}); assertEquals(A10ProProtocol.DeviceFamily.FIR, P.getFamily()); }
    @Test public void decodeUnknownOpcode_returnsEmpty() { assertEquals(0, P.decodeResponse(new byte[]{0x42, 0x00}).length); }
    @Test public void checksum_helperMatchesByHand() { byte[] out = A10ProProtocol.withChecksum(new byte[]{(byte) 0xFB, 0x05, 0x01, 0x07, 0x02}); assertEquals(7, out.length); assertEquals(0x0A, out[5] & 0xFF); assertEquals(0x01, out[6] & 0xFF); }
}
