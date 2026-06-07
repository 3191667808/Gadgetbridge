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
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCameraRemote;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
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
    @Test public void weatherJl_isTwentyBytes() { byte[] frame = P.encodeWeatherJl(3, 18, 24, 16, 55, 3, 1); assertEquals(20, frame.length); assertEquals(0x10, frame[0]); assertEquals(3, frame[2]); assertEquals(3, frame[6]); assertEquals(55, frame[17]); }
    @Test public void weatherZk_padsCityWithFf() { byte[] frame = P.encodeWeatherZk(25, 30, 0, "Tel Aviv"); assertEquals(20, frame.length); assertEquals(0x25, frame[0]); assertEquals('T', frame[4]); assertEquals((byte) 0xFF, frame[19]); }
    @Test public void unknownFamilyWeather_emitsAllThreeVariants() { List<byte[]> frames = P.encodeWeatherForFamily(A10ProProtocol.DeviceFamily.UNKNOWN, 0, 22, 28, 16, 50, 3, 1, "TA"); assertEquals(3, frames.size()); assertEquals(0x05, frames.get(0)[0]); assertEquals(0x10, frames.get(1)[0]); assertEquals(0x25, frames.get(2)[0]); }

    @Test public void gpsAddress_usesUtf16LeBomHeader() { List<byte[]> packets = P.encodeGpsAddress("AB"); assertEquals(1, packets.size()); assertArrayEquals(new byte[]{0x07, 0x00, (byte) 0xFF, (byte) 0xFE, 0x41, 0x00, 0x42, 0x00}, java.util.Arrays.copyOf(packets.get(0), 8)); }
    @Test public void gpsAddress_longStringMultipackets() { List<byte[]> packets = P.encodeGpsAddress("12345678901234567890"); assertEquals(3, packets.size()); assertEquals(0x07, packets.get(1)[0]); assertEquals(0x01, packets.get(1)[1]); }

    @Test public void notificationFir_uses73Chunks() { List<byte[]> frames = P.encodeNotification(2, "hello", A10ProProtocol.DeviceFamily.FIR); assertEquals(1, frames.size()); assertEquals(0x73, frames.get(0)[0]); assertEquals(0, frames.get(0)[1]); assertEquals(2, frames.get(0)[2]); }
    @Test public void notificationZk_uses23AndEndMarker() { List<byte[]> frames = P.encodeNotification(4, "hello", A10ProProtocol.DeviceFamily.ZK); assertEquals(1, frames.size()); assertEquals(0x23, frames.get(0)[0]); assertEquals((byte) 0xFF, frames.get(0)[frames.get(0).length - 1]); }
    @Test public void notificationLongTextChunksAt17Bytes() { List<byte[]> frames = P.encodeNotification(1, "abcdefghijklmnopqr", A10ProProtocol.DeviceFamily.JL); assertEquals(2, frames.size()); assertEquals(0, frames.get(0)[1]); assertEquals(1, frames.get(1)[1]); }

    @Test public void jlWeather_liveLayoutToday() { byte[] f = P.encodeWeatherJl(new int[]{1, 40, 39, 38, 0}, new int[]{30, 31, 32, 33, 34}, new int[]{20, 21, 22, 23, 24}, 27, 5, 6, 72); assertArrayEquals(new byte[]{0x10,0,1,27,30,20,40,31,21,39,32,22,38,33,23,5,6,72,0,0}, f); }
    @Test public void jlWeather_requiresFourDays() { try { P.encodeWeatherJl(new int[]{1}, new int[]{2}, new int[]{3}, 4, 5, 6, 7); } catch (IllegalArgumentException e) { return; } throw new AssertionError("expected exception"); }
    @Test public void weatherForFamilyJl_onlyJlFrame() { List<byte[]> frames = P.encodeWeatherForFamily(A10ProProtocol.DeviceFamily.JL, new int[]{1,2,3,4,5}, new int[]{10,11,12,13,14}, new int[]{0,1,2,3,4}, 9, 8, 7, 6, "TA"); assertEquals(1, frames.size()); assertEquals(0x10, frames.get(0)[0]); }
    @Test public void weatherForFamilyUnknown_sendsThreeFrames() { List<byte[]> frames = P.encodeWeatherForFamily(A10ProProtocol.DeviceFamily.UNKNOWN, new int[]{1,2,3,4,5}, new int[]{10,11,12,13,14}, new int[]{0,1,2,3,4}, 9, 8, 7, 6, "TA"); assertEquals(3, frames.size()); assertEquals(0x05, frames.get(0)[0]); assertEquals(0x10, frames.get(1)[0]); assertEquals(0x25, frames.get(2)[0]); }
    @Test public void weatherIcon_sun() { assertEquals(1, A10ProProtocol.mapOpenWeatherToJlIcon(800)); }
    @Test public void weatherIcon_cloud() { assertEquals(40, A10ProProtocol.mapOpenWeatherToJlIcon(804)); }
    @Test public void weatherIcon_thunderstorm() { assertEquals(33, A10ProProtocol.mapOpenWeatherToJlIcon(202)); }
    @Test public void weatherIcon_thunderstormWithSun() { assertEquals(4, A10ProProtocol.mapOpenWeatherToJlIcon(211)); }
    @Test public void weatherIcon_heavyRain() { assertEquals(7, A10ProProtocol.mapOpenWeatherToJlIcon(502)); }
    @Test public void weatherIcon_drizzle() { assertEquals(11, A10ProProtocol.mapOpenWeatherToJlIcon(301)); }
    @Test public void weatherIcon_snow() { assertEquals(38, A10ProProtocol.mapOpenWeatherToJlIcon(600)); }
    @Test public void weatherIcon_rain() { assertEquals(39, A10ProProtocol.mapOpenWeatherToJlIcon(500)); }

    @Test public void musicVolume_hasVolumeSubcommand() { assertArrayEquals(new byte[]{0x41, 0x04, 55}, P.encodeMusicVolume(55)); }
    @Test public void musicVolume_clampsHigh() { assertEquals(100, P.encodeMusicVolume(150)[2]); }
    @Test public void musicVolume_clampsLow() { assertEquals(0, P.encodeMusicVolume(-1)[2]); }
    @Test public void musicControl_play() { assertArrayEquals(new byte[]{(byte) 0x99, 0}, P.encodeMusicControl(0)); }
    @Test public void musicControl_pause() { assertArrayEquals(new byte[]{(byte) 0x99, 1}, P.encodeMusicControl(1)); }
    @Test public void musicControl_next() { assertArrayEquals(new byte[]{(byte) 0x99, 2}, P.encodeMusicControl(2)); }
    @Test public void musicControl_prev() { assertArrayEquals(new byte[]{(byte) 0x99, 3}, P.encodeMusicControl(3)); }
    @Test public void decodeMusicControlPause() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{(byte) 0x99, 0}); assertEquals(GBDeviceEventMusicControl.Event.PAUSE, ((GBDeviceEventMusicControl) events[0]).event); }
    @Test public void decodeMusicControlPlay() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{(byte) 0x99, 2}); assertEquals(GBDeviceEventMusicControl.Event.PLAY, ((GBDeviceEventMusicControl) events[0]).event); }
    @Test public void decodeMusicControlNext() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{(byte) 0x99, 4}); assertEquals(GBDeviceEventMusicControl.Event.NEXT, ((GBDeviceEventMusicControl) events[0]).event); }
    @Test public void decodeMusicControlPrev() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{(byte) 0x99, 3}); assertEquals(GBDeviceEventMusicControl.Event.PREVIOUS, ((GBDeviceEventMusicControl) events[0]).event); }
    @Test public void decodeMusicControlVolumeUp() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{(byte) 0x99, 5}); assertEquals(GBDeviceEventMusicControl.Event.VOLUMEUP, ((GBDeviceEventMusicControl) events[0]).event); }
    @Test public void decodeMusicControlVolumeDown() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{(byte) 0x99, 6}); assertEquals(GBDeviceEventMusicControl.Event.VOLUMEDOWN, ((GBDeviceEventMusicControl) events[0]).event); }
    @Test public void musicText_artistUtf16AndEnd() { List<byte[]> frames = P.encodeMusicText("AB", 1); assertArrayEquals(new byte[]{(byte) 0x99,2,1,1,0,'A',0,'B'}, frames.get(0)); assertArrayEquals(new byte[]{(byte) 0x99,2,1,(byte) 0xFF}, frames.get(1)); }
    @Test public void musicText_chunksAt16Bytes() { List<byte[]> frames = P.encodeMusicText("123456789", 2); assertEquals(3, frames.size()); assertEquals(16, frames.get(0).length - 4); }

    @Test public void incomingCall_packsNameAndNumberLengths() { assertArrayEquals(new byte[]{0x55,1,3,'B','o','b',3,'1','2','3'}, P.encodeIncomingCall("Bob", "123")); }
    @Test public void incomingCall_nullsBecomeEmpty() { assertArrayEquals(new byte[]{0x55,1,0,0}, P.encodeIncomingCall(null, null)); }
    @Test public void callEnd_isState2() { assertArrayEquals(new byte[]{0x55,2,0,0}, P.encodeCallEnd()); }
    @Test public void callAnswer_isState3() { assertArrayEquals(new byte[]{0x55,3,0,0}, P.encodeCallAnswer()); }
    @Test public void callDecline_isState4() { assertArrayEquals(new byte[]{0x55,4,0,0}, P.encodeCallDecline()); }
    @Test public void decodeFindPhoneStart() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{0x53, 1}); assertEquals(1, events.length); assertEquals(GBDeviceEventFindPhone.Event.START, ((GBDeviceEventFindPhone) events[0]).event); }
    @Test public void decodeFindPhoneStop() { GBDeviceEvent[] events = P.decodeResponse(new byte[]{0x53, 0}); assertEquals(GBDeviceEventFindPhone.Event.STOP, ((GBDeviceEventFindPhone) events[0]).event); }

    @Test public void decodeCameraShutter_liveCapturedFrame() {
        // Live frame from new_snoop.log (G2-ADV pressing case shutter button)
        byte[] wire = new byte[]{
                (byte) 0xa2, (byte) 0x09, 0x00, 0x02, 0x0a, 0x01, 0x04,
                (byte) 0x84, (byte) 0xa2, 0x13, 0x04, 0x01, 0x63, (byte) 0xdc, 0x00
        };
        GBDeviceEvent[] events = P.decodeResponse(wire);
        assertEquals(1, events.length);
        assertEquals(GBDeviceEventCameraRemote.Event.TAKE_PICTURE, ((GBDeviceEventCameraRemote) events[0]).event);
    }

    @Test public void decodeCameraShutter_ignoresNonShutterA2() {
        // Frame ending in 01 03 91 — UI button-list response, not a shutter press
        byte[] wire = new byte[]{
                (byte) 0xa2, (byte) 0x8a, 0x00, 0x02, 0x0a, 0x01, 0x04,
                (byte) 0x85, (byte) 0x93, 0x13, 0x01, 0x03, (byte) 0x91, 0x20, 0x00
        };
        GBDeviceEvent[] events = P.decodeResponse(wire);
        assertEquals(0, events.length);
    }

    @Test public void decodeCameraShutter_rejectsShortFrame() {
        GBDeviceEvent[] events = P.decodeResponse(new byte[]{(byte) 0xa2, 0x00});
        assertEquals(0, events.length);
    }

    @Test public void notificationSupport_defaultsTrue() { A10ProProtocol p = new A10ProProtocol(null); assertEquals(true, p.supportsUploadMessage()); }
    @Test public void functionInfoBit18_enablesNotifications() { A10ProProtocol p = new A10ProProtocol(null); byte[] data = new byte[19]; data[0] = (byte) 0x83; data[18] = 1; p.decodeResponse(data); assertEquals(true, p.supportsUploadMessage()); }
    @Test public void functionInfoBit18_unsetStillAllowsPush() { A10ProProtocol p = new A10ProProtocol(null); byte[] data = new byte[19]; data[0] = (byte) 0x83; p.decodeResponse(data); assertEquals(true, p.supportsUploadMessage()); }

    @Test public void alarmClock_oneAlarmLayout() { byte[] frame = P.encodeAlarmClock(new Alarm[]{alarm(true, 6, 30, Alarm.ALARM_DAILY)}); assertEquals(19, frame.length); assertEquals(2, frame[0]); assertEquals(3, frame[1]); assertEquals(1, frame[2]); assertEquals(6, frame[3]); assertEquals(30, frame[4]); assertEquals(1, frame[14]); }
    @Test public void alarmClock_fiveAlarmsLayout() { byte[] frame = P.encodeAlarmClock(new Alarm[]{alarm(true,1,2,3), alarm(false,4,5,6), alarm(true,7,8,9), alarm(false,10,11,12), alarm(true,13,14,15)}); assertEquals(23, frame.length); assertEquals(5, frame[14]); assertEquals(0, frame[15]); assertEquals(10, frame[16]); assertEquals(1, frame[19]); assertEquals(13, frame[20]); }
    @Test public void userInfo_packsWeightAgeHeightStrideGenderGoal() { assertArrayEquals(new byte[]{2,1,0,70,26,(byte) 180,75,1,0,0,31,64}, P.encodeUserInfo(70, 26, 180, 75, 1, 8000)); }

    @Test public void callerStateNotification_matchesLiveHebrewCapture() {
        // Live-captured zwsvibe → G2-ADV: '23 00 13 "Lea " + "מעצבת" + ":In"'
        // Hebrew bytes from snoop: d7 9e d7 a2 d7 a6 d7 91 d7 aa = U+05E9..U+05EA "מעצבת"
        byte[] expected = new byte[] {
                0x23, 0x00, 0x13,
                'L', 'e', 'a', ' ',
                (byte) 0xd7, (byte) 0x9e, (byte) 0xd7, (byte) 0xa2,
                (byte) 0xd7, (byte) 0xa6, (byte) 0xd7, (byte) 0x91, (byte) 0xd7, (byte) 0xaa,
                ':', 'I', 'n'
        };
        assertArrayEquals(expected, P.encodeCallerStateNotification("Lea \u05DE\u05E2\u05E6\u05D1\u05EA", "In"));
    }

    @Test public void callerStateNotification_ongoingStateTag() {
        byte[] f = P.encodeCallerStateNotification("Alice", "On");
        assertArrayEquals(new byte[] {0x23, 0x00, 0x0A, 'A','l','i','c','e',':','O','n'}, f);
    }
    @Test public void callerStateNotification_truncatesAtUtf8Boundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("\u05DE");
        byte[] f = P.encodeCallerStateNotification(sb.toString(), "In");
        int declared = f[2] & 0xff;
        assertEquals(declared, f.length - 1);
        byte[] tail = new byte[3];
        System.arraycopy(f, f.length - 3, tail, 0, 3);
        assertArrayEquals(new byte[]{':','I','n'}, tail);
        for (int i = 3; i < f.length - 3; i += 2) {
            assertEquals((byte) 0xd7, f[i]);
            assertEquals((byte) 0x9e, f[i + 1]);
        }
    }
    @Test public void findBandSwitch_uses51() { assertArrayEquals(new byte[]{0x51, 0x01}, P.encodeFindBandSwitch(true)); }
    @Test public void volumeCap_usesFb08Checksum() { assertArrayEquals(new byte[]{(byte) 0xFB,0x08,0x01,0x07,80,0x5B,0x01}, P.encodeVolumeMaxValue(80)); }
    @Test public void barrageEmpty_clearsSlot() { List<byte[]> frames = P.encodeBarrage("", 2, 20); assertArrayEquals(new byte[]{(byte) 0xBB,1,2,0,0,0,0}, frames.get(0)); }
    @Test public void barrageShort_packsHeader() { List<byte[]> frames = P.encodeBarrage("hello", 1, 20); assertEquals(1, frames.size()); assertEquals((byte) 0xBB, frames.get(0)[0]); assertEquals(1, frames.get(0)[4]); assertEquals(5, frames.get(0)[5]); assertEquals('h', frames.get(0)[7]); }
    @Test public void barrageLong_chunksByMtuMinusHeader() { List<byte[]> frames = P.encodeBarrage("abcdefghijklmn", 1, 20); assertEquals(2, frames.size()); assertEquals(2, frames.get(0)[3]); assertEquals(2, frames.get(1)[4]); }

    @Test public void decodeBattery_extractsLevelsAndChargingFlags() { byte[] data = {(byte) 0xFB, 0x03, 0x03, 0x08, (byte) 0x95, 0x32, 0x00, 0x00}; GBDeviceEvent[] events = P.decodeBattery(data); assertEquals(2, events.length); GBDeviceEventBatteryInfo left = (GBDeviceEventBatteryInfo) events[0]; GBDeviceEventBatteryInfo right = (GBDeviceEventBatteryInfo) events[1]; assertEquals(0, left.batteryIndex); assertEquals(21, left.level); assertEquals(BatteryState.BATTERY_CHARGING, left.state); assertEquals(1, right.batteryIndex); assertEquals(50, right.level); assertEquals(BatteryState.BATTERY_NORMAL, right.state); }
    @Test public void decodeBattery_threePayloadBytesAddsCase() { byte[] data = {(byte) 0xFB, 0x03, 0x03, 0x09, 10, 20, 30, 0, 0}; assertEquals(3, P.decodeBattery(data).length); }
    @Test public void decodeClassicBattery_extractsTwoBuds() { GBDeviceEvent[] events = P.decodeClassicBattery(new byte[]{(byte) 0x94, 11, (byte) 0x8C}); assertEquals(2, events.length); assertEquals(BatteryState.BATTERY_CHARGING, ((GBDeviceEventBatteryInfo) events[1]).state); }
    @Test public void decodeWeatherAckDetectsZk() { P.setFamily(A10ProProtocol.DeviceFamily.UNKNOWN); P.decodeResponse(new byte[]{(byte) 0xA5, 0}); assertEquals(A10ProProtocol.DeviceFamily.ZK, P.getFamily()); }
    @Test public void decodeWeatherAckDetectsJl() { P.setFamily(A10ProProtocol.DeviceFamily.UNKNOWN); P.decodeResponse(new byte[]{(byte) 0x90, 0}); assertEquals(A10ProProtocol.DeviceFamily.JL, P.getFamily()); }
    @Test public void decodeWeatherAckDetectsFir() { P.setFamily(A10ProProtocol.DeviceFamily.UNKNOWN); P.decodeResponse(new byte[]{(byte) 0x85, 0}); assertEquals(A10ProProtocol.DeviceFamily.FIR, P.getFamily()); }
    @Test public void decodeUnknownOpcode_returnsEmpty() { assertEquals(0, P.decodeResponse(new byte[]{0x42, 0x00}).length); }
    @Test public void checksum_helperMatchesByHand() { byte[] out = A10ProProtocol.withChecksum(new byte[]{(byte) 0xFB, 0x05, 0x01, 0x07, 0x02}); assertEquals(7, out.length); assertEquals(0x0A, out[5] & 0xFF); assertEquals(0x01, out[6] & 0xFF); }

    private static Alarm alarm(final boolean enabled, final int hour, final int minute, final int repetition) {
        return new Alarm() {
            public int getPosition() { return 0; }
            public boolean getEnabled() { return enabled; }
            public boolean getUnused() { return false; }
            public boolean getSmartWakeup() { return false; }
            public Integer getSmartWakeupInterval() { return 0; }
            public boolean getSnooze() { return false; }
            public int getRepetition() { return repetition; }
            public boolean isRepetitive() { return repetition != 0; }
            public boolean getRepetition(final int dow) { return (repetition & dow) != 0; }
            public int getHour() { return hour; }
            public int getMinute() { return minute; }
            public String getTitle() { return ""; }
            public String getDescription() { return ""; }
            public int getSoundCode() { return 0; }
            public boolean getBacklight() { return false; }
        };
    }
}
