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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

/**
 * FreeFit iEnjoy V2 protocol codec, reverse engineered from zwsvibe
 * (com.czw.freefit.ienjoy) and live-tested against a G2-ADV LCD case.
 *
 * BLE transport:
 * service 6E40FC00-B5A3-F393-E0A9-E50E24DCCA9E,
 * write 6E40FC20-..., notify 6E40FC21-...
 */
public class A10ProProtocol extends GBDeviceProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(A10ProProtocol.class);

    public enum DeviceFamily { UNKNOWN, FIR, JL, ZK }

    static final byte CMD_SYNC_TIME = 0x01;
    static final byte CMD_DEVICE_RAISE = 0x02;
    static final byte CMD_DEVICE_ALARM = 0x03;
    static final byte CMD_DEVICE_LOSE = 0x04;
    static final byte CMD_DEVICE_NAME = 0x05;
    static final byte CMD_GPS_INFO = 0x06;
    static final byte CMD_GPS_ADDRESS = 0x07;
    static final byte CMD_DEVICE_REQUEST_SYNC_TIME = 0x0D;
    static final byte CMD_WEATHER_FIR = 0x05;
    static final byte CMD_WEATHER_JL = 0x10;
    static final byte CMD_WEATHER_ZK = 0x25;
    static final byte CMD_GET_FUNCTION = 0x03;
    static final byte CMD_GET_FW_VERSION = 0x1F;
    static final byte CMD_FIND_BAND = (byte) 0xD1;
    static final byte CMD_CALL_PHONE = 0x55;
    static final byte CMD_FIND_PHONE = 0x53;
    static final byte CMD_MSG_PUSH = 0x73;
    static final byte CMD_LOW_BATTERY = 0x72;
    static final byte CMD_DIAL_SET = (byte) 0x98;
    static final byte CMD_MUSIC_CONTROL = (byte) 0x99;
    static final byte CMD_SYNC_CONTACT = (byte) 0x9A;
    static final byte CMD_WK_STRING = (byte) 0xB9;
    static final byte CMD_BARRAGE = (byte) 0xBB;
    static final byte CMD_STICKER = (byte) 0xBC;
    static final byte CMD_GPS = (byte) 0xDD;
    static final byte CMD_RESET = 0x71;
    static final byte CMD_TURN_OFF = (byte) 0xAD;

    static final byte RESP_SYNC_TIME_ANSWER = (byte) 0x81;
    static final byte RESP_FUNCTION_LIST = (byte) 0x83;
    static final byte RESP_VERSION = (byte) 0x9F;
    static final byte RESP_WEATHER_FIR = (byte) 0x85;
    static final byte RESP_WEATHER_JL = (byte) 0x90;
    static final byte RESP_WEATHER_ZK = (byte) 0xA5;
    static final byte RESP_BATTERY_CLASSIC = (byte) 0x94;

    static final byte FB_PREFIX = (byte) 0xFB;
    static final byte SUB_EQ = 0x01;
    static final byte SUB_KEY_CODE = 0x02;
    static final byte SUB_BATTERY = 0x03;
    static final byte SUB_BLUE_NAME = 0x04;
    static final byte SUB_ANC = 0x05;
    static final byte SUB_FIND = 0x06;
    static final byte SUB_AUDIO_MODEL = 0x07;
    static final byte READ = 0x00;
    static final byte WRITE = 0x01;

    private DeviceFamily family = DeviceFamily.UNKNOWN;
    private boolean supportsUploadMessage;

    public A10ProProtocol(final GBDevice device) {
        super(device);
    }

    public DeviceFamily getFamily() {
        return family;
    }

    public void setFamily(final DeviceFamily family) {
        this.family = family == null ? DeviceFamily.UNKNOWN : family;
    }

    public boolean supportsUploadMessage() {
        return supportsUploadMessage;
    }

    public byte[] encodeSyncTime(final boolean is24Hour, final int languageCode) {
        final Date date = new Date();
        final int secsSinceEpoch = (int) (date.getTime() / 1000L);
        final int tzOffsetSecs = Calendar.getInstance().getTimeZone().getOffset(date.getTime()) / 1000;
        return new byte[]{
                CMD_SYNC_TIME,
                (byte) (secsSinceEpoch >>> 24), (byte) (secsSinceEpoch >>> 16),
                (byte) (secsSinceEpoch >>> 8), (byte) secsSinceEpoch,
                (byte) (tzOffsetSecs >>> 24), (byte) (tzOffsetSecs >>> 16),
                (byte) (tzOffsetSecs >>> 8), (byte) tzOffsetSecs,
                (byte) (is24Hour ? 1 : 2),
                (byte) languageCode
        };
    }

    public byte[] encodeGetFunction() { return new byte[]{CMD_GET_FUNCTION, 0x00}; }
    public byte[] encodeGetFirmwareVersion() { return new byte[]{CMD_GET_FW_VERSION}; }
    public byte[] encodeClassicBatteryQuery() { return new byte[]{0x14}; }
    public byte[] encodeReset() { return new byte[]{CMD_RESET, 0x01, 0x02, 0x03}; }
    public byte[] encodeTurnOff(final int model) { return new byte[]{CMD_TURN_OFF, (byte) model}; }
    public byte[] encodeFindBand(final boolean start) { return new byte[]{CMD_FIND_BAND, (byte) (start ? 1 : 0)}; }
    public byte[] encodeFindBandSwitch(final boolean start) { return new byte[]{0x51, (byte) (start ? 1 : 0)}; }
    public byte[] encodeAntiLost(final boolean enabled) { return new byte[]{0x70, (byte) (enabled ? 1 : 0)}; }
    public byte[] encodeCameraControl(final boolean enabled) { return new byte[]{0x52, (byte) (enabled ? 1 : 0)}; }
    public byte[] encodeUnit(final boolean metric, final boolean celsius) { return new byte[]{0x11, (byte) (metric ? 0 : 1), (byte) (celsius ? 0 : 1), 0, 0}; }
    public byte[] encodeMusicVolume(final int volume) { return new byte[]{0x41, 0x04, (byte) clamp(volume, 0, 100)}; }
    public byte[] encodeMusicControl(final int action) { return new byte[]{CMD_MUSIC_CONTROL, (byte) clamp(action, 0, 3)}; }
    public byte[] encodeIncomingCall(final String name, final String number) { return encodeCallState(1, name, number); }
    public byte[] encodeCallEnd() { return new byte[]{CMD_CALL_PHONE, 2, 0, 0}; }
    public byte[] encodeCallAnswer() { return new byte[]{CMD_CALL_PHONE, 3, 0, 0}; }
    public byte[] encodeCallDecline() { return new byte[]{CMD_CALL_PHONE, 4, 0, 0}; }
    public byte[] encodeCall(final boolean alert, final String number, final String name) { return alert ? encodeIncomingCall(name, number) : encodeCallEnd(); }
    public byte[] encodeDialSet(final int index) { return new byte[]{CMD_DIAL_SET, (byte) index}; }
    public List<byte[]> encodeBarrage(final String text) { return encodeBarrage(text, 1, 20); }
    public byte[] encodeSticker(final int index) { return new byte[]{CMD_STICKER, (byte) index}; }
    public byte[] encodeWkString(final String text) { return prefixedText(CMD_WK_STRING, 0, text, StandardCharsets.UTF_8, 18); }
    public byte[] encodeVolumeMaxValue(final int value) { return withChecksum(new byte[]{FB_PREFIX, 0x08, WRITE, 7, (byte) clamp(value, 0, 100)}); }

    public byte[] encodeQueryBattery() { return withChecksum(new byte[]{FB_PREFIX, SUB_BATTERY, READ, 6}); }
    public byte[] encodeQueryEq() { return withChecksum(new byte[]{FB_PREFIX, SUB_EQ, READ, 6}); }
    public byte[] encodeQueryKeyCode() { return withChecksum(new byte[]{FB_PREFIX, SUB_KEY_CODE, READ, 6}); }
    public byte[] encodeQueryBlueName() { return withChecksum(new byte[]{FB_PREFIX, SUB_BLUE_NAME, READ, 6}); }
    public byte[] encodeQueryAnc() { return withChecksum(new byte[]{FB_PREFIX, SUB_ANC, READ, 6}); }
    public byte[] encodeQueryAudio() { return withChecksum(new byte[]{FB_PREFIX, SUB_AUDIO_MODEL, READ, 6}); }

    public byte[] encodeFindHeadphones(final int state) { return withChecksum(new byte[]{FB_PREFIX, SUB_FIND, WRITE, 7, (byte) state}); }
    public byte[] encodeSetAnc(final int index) { return withChecksum(new byte[]{FB_PREFIX, SUB_ANC, WRITE, 7, (byte) index}); }
    public byte[] encodeSetAudioModel(final int model) { return withChecksum(new byte[]{FB_PREFIX, SUB_AUDIO_MODEL, WRITE, 7, (byte) model}); }

    public byte[] encodeSetEq(final int preset, @NonNull final byte[] bands10) {
        if (bands10.length != 10) throw new IllegalArgumentException("EQ needs 10 bands");
        final byte[] frame = new byte[15];
        frame[0] = FB_PREFIX;
        frame[1] = SUB_EQ;
        frame[2] = WRITE;
        frame[3] = 17;
        frame[4] = (byte) preset;
        System.arraycopy(bands10, 0, frame, 5, 10);
        return withChecksum(frame);
    }

    public byte[] encodeSetKeyCode(@NonNull final byte[] map9) {
        if (map9.length != 9) throw new IllegalArgumentException("KeyCode needs 9 slots");
        final byte[] frame = new byte[13];
        frame[0] = FB_PREFIX;
        frame[1] = SUB_KEY_CODE;
        frame[2] = WRITE;
        frame[3] = 15;
        System.arraycopy(map9, 0, frame, 4, 9);
        return withChecksum(frame);
    }

    @Nullable
    public byte[] encodeSetBlueName(@NonNull final String name, final int maxLen) {
        final byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > maxLen) return null;
        final byte[] frame = new byte[bytes.length + 4];
        frame[0] = FB_PREFIX;
        frame[1] = SUB_BLUE_NAME;
        frame[2] = WRITE;
        frame[3] = (byte) (bytes.length + 6);
        System.arraycopy(bytes, 0, frame, 4, bytes.length);
        return withChecksum(frame);
    }

    public byte[] encodeWeatherFir(final int code, final int tempNowC, final int tempMaxC) {
        return new byte[]{CMD_WEATHER_FIR, (byte) code, (byte) tempNowC, (byte) tempMaxC};
    }

    public byte[] encodeWeatherJl(final int code, final int tempNowC, final int tempMaxC,
                                  final int tempNightC, final int humidity, final int windLevel,
                                  final int windDirection) {
        return encodeWeatherJl(new int[]{code, code, code, code, code},
                new int[]{tempMaxC, tempMaxC, tempMaxC, tempMaxC, tempMaxC},
                new int[]{tempNightC, tempNightC, tempNightC, tempNightC, tempNightC},
                tempNowC, 0, windDirection, humidity);
    }

    public byte[] encodeWeatherJl(@NonNull final int[] icons5, @NonNull final int[] hi5,
                                  @NonNull final int[] lo5, final int currentTemp,
                                  final int uv, final int windDir, final int humidity) {
        if (icons5.length < 4 || hi5.length < 4 || lo5.length < 4) {
            throw new IllegalArgumentException("JL weather needs today plus three forecast days");
        }
        final byte[] frame = new byte[20];
        frame[0] = CMD_WEATHER_JL;
        frame[1] = 0x00;
        frame[2] = (byte) icons5[0];
        frame[3] = (byte) currentTemp;
        frame[4] = (byte) hi5[0];
        frame[5] = (byte) lo5[0];
        for (int day = 1; day <= 3; day++) {
            final int offset = 3 + day * 3;
            frame[offset] = (byte) icons5[day];
            frame[offset + 1] = (byte) hi5[day];
            frame[offset + 2] = (byte) lo5[day];
        }
        frame[15] = (byte) clamp(uv, 0, 15);
        frame[16] = (byte) clamp(windDir, 0, 9);
        frame[17] = (byte) clamp(humidity, 0, 100);
        return frame;
    }

    public byte[] encodeWeatherZk(final int tempNowC, final int tempMaxC, final int code, @NonNull final String city) {
        final byte[] frame = new byte[20];
        frame[0] = CMD_WEATHER_ZK;
        frame[1] = (byte) tempNowC;
        frame[2] = (byte) tempMaxC;
        frame[3] = (byte) code;
        final byte[] cityBytes = city.getBytes(StandardCharsets.UTF_8);
        final int len = Math.min(cityBytes.length, 16);
        System.arraycopy(cityBytes, 0, frame, 4, len);
        for (int i = 4 + len; i < frame.length; i++) {
            frame[i] = (byte) 0xFF;
        }
        return frame;
    }

    public List<byte[]> encodeWeatherForFamily(final DeviceFamily deviceFamily, final int code, final int tempNowC,
                                               final int tempMaxC, final int tempMinC, final int humidity,
                                               final int windLevel, final int windDirection,
                                               @NonNull final String city) {
        final List<byte[]> frames = new ArrayList<>();
        final DeviceFamily f = deviceFamily == null ? DeviceFamily.UNKNOWN : deviceFamily;
        if (f == DeviceFamily.UNKNOWN || f == DeviceFamily.FIR) frames.add(encodeWeatherFir(code, tempNowC, tempMaxC));
        if (f == DeviceFamily.UNKNOWN || f == DeviceFamily.JL) frames.add(encodeWeatherJl(code, tempNowC, tempMaxC, tempMinC, humidity, windLevel, windDirection));
        if (f == DeviceFamily.UNKNOWN || f == DeviceFamily.ZK) frames.add(encodeWeatherZk(tempNowC, tempMaxC, code, city));
        return frames;
    }

    public List<byte[]> encodeWeatherForFamily(final DeviceFamily deviceFamily, @NonNull final int[] icons5,
                                               @NonNull final int[] hi5, @NonNull final int[] lo5,
                                               final int currentTemp, final int uv, final int windDir,
                                               final int humidity, @NonNull final String city) {
        final List<byte[]> frames = new ArrayList<>();
        final DeviceFamily f = deviceFamily == null ? DeviceFamily.UNKNOWN : deviceFamily;
        if (f == DeviceFamily.UNKNOWN || f == DeviceFamily.FIR) frames.add(encodeWeatherFir(icons5[0], currentTemp, hi5[0]));
        if (f == DeviceFamily.UNKNOWN || f == DeviceFamily.JL) frames.add(encodeWeatherJl(icons5, hi5, lo5, currentTemp, uv, windDir, humidity));
        if (f == DeviceFamily.UNKNOWN || f == DeviceFamily.ZK) frames.add(encodeWeatherZk(currentTemp, hi5[0], icons5[0], city));
        return frames;
    }

    public static int mapOpenWeatherToJlIcon(final int openWeatherCode) {
        if (openWeatherCode >= 200 && openWeatherCode < 300) return openWeatherCode == 210 || openWeatherCode == 211 ? 4 : 33;
        if (openWeatherCode >= 300 && openWeatherCode < 400) return 11;
        if (openWeatherCode >= 500 && openWeatherCode < 600) return openWeatherCode >= 502 ? 7 : 39;
        if (openWeatherCode >= 600 && openWeatherCode < 700) return 38;
        if (openWeatherCode >= 700 && openWeatherCode < 800) return 40;
        if (openWeatherCode == 800) return 1;
        if (openWeatherCode >= 801 && openWeatherCode <= 804) return openWeatherCode == 801 ? 0 : 40;
        return 0;
    }

    public List<byte[]> encodeGpsAddress(@NonNull String address) {
        if (address.length() > 64) address = address.substring(0, 61) + "...";
        final byte[] utf16le = address.getBytes(Charset.forName("UTF-16LE"));
        final List<byte[]> packets = new ArrayList<>();
        int offset = 0;
        byte[] first = new byte[20];
        first[0] = CMD_GPS_ADDRESS;
        first[1] = 0x00;
        first[2] = (byte) 0xFF;
        first[3] = (byte) 0xFE;
        final int firstLen = Math.min(16, utf16le.length);
        System.arraycopy(utf16le, 0, first, 4, firstLen);
        packets.add(first);
        offset += firstLen;
        int packetIndex = 1;
        while (offset < utf16le.length) {
            final byte[] packet = new byte[20];
            packet[0] = CMD_GPS_ADDRESS;
            packet[1] = (byte) packetIndex++;
            final int len = Math.min(18, utf16le.length - offset);
            System.arraycopy(utf16le, offset, packet, 2, len);
            packets.add(packet);
            offset += len;
        }
        return packets;
    }

    public List<byte[]> encodeNotification(final int appId, @NonNull final String text, final DeviceFamily deviceFamily) {
        final DeviceFamily f = deviceFamily == null ? DeviceFamily.UNKNOWN : deviceFamily;
        if (f == DeviceFamily.JL || f == DeviceFamily.ZK) {
            return chunkUtf8(f == DeviceFamily.ZK ? (byte) 0x23 : CMD_MSG_PUSH, appId, text, true);
        }
        return chunkUtf8(CMD_MSG_PUSH, appId, text, false);
    }

    public byte[] encodeAlarmClock(@NonNull final Alarm[] alarms) {
        final int count = Math.min(5, alarms.length);
        final byte[] frame = new byte[count >= 5 ? 23 : 19];
        frame[0] = 0x02;
        frame[1] = CMD_DEVICE_ALARM;
        for (int i = 0; i < count; i++) {
            final int offset = i < 3 ? 2 + i * 4 : 15 + (i - 3) * 4;
            final Alarm alarm = alarms[i];
            frame[offset] = (byte) (alarm.getEnabled() ? 1 : 0);
            frame[offset + 1] = (byte) alarm.getHour();
            frame[offset + 2] = (byte) alarm.getMinute();
            frame[offset + 3] = (byte) alarm.getRepetition();
        }
        frame[14] = (byte) count;
        return frame;
    }

    public byte[] encodeUserInfo(final int weightKg, final int age, final int heightCm,
                                 final int stepLengthCm, final int gender, final int stepGoal) {
        return new byte[]{
                0x02, 0x01,
                (byte) ((weightKg >> 8) & 0xFF), (byte) (weightKg & 0xFF),
                (byte) clamp(age, 0, 120), (byte) clamp(heightCm, 0, 255),
                (byte) clamp(stepLengthCm, 0, 255), (byte) clamp(gender, 0, 2),
                (byte) ((stepGoal >> 24) & 0xFF), (byte) ((stepGoal >> 16) & 0xFF),
                (byte) ((stepGoal >> 8) & 0xFF), (byte) (stepGoal & 0xFF)
        };
    }

    public List<byte[]> encodeMusicText(@Nullable final String text, final int type) {
        final byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_16BE);
        final List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        int packet = 1;
        while (offset < body.length || frames.isEmpty()) {
            final int len = Math.min(16, body.length - offset);
            final byte[] frame = new byte[4 + Math.max(0, len)];
            frame[0] = CMD_MUSIC_CONTROL;
            frame[1] = 0x02;
            frame[2] = (byte) type;
            frame[3] = (byte) packet++;
            if (len > 0) System.arraycopy(body, offset, frame, 4, len);
            frames.add(frame);
            offset += Math.max(0, len);
            if (offset >= body.length) break;
        }
        frames.add(new byte[]{CMD_MUSIC_CONTROL, 0x02, (byte) type, (byte) 0xFF});
        return frames;
    }

    public List<byte[]> encodeBarrage(@Nullable final String text, final int index, final int mtu) {
        final byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            final List<byte[]> clear = new ArrayList<>();
            clear.add(new byte[]{CMD_BARRAGE, 0x01, (byte) index, 0, 0, 0, 0});
            return clear;
        }
        final int packetSize = Math.max(20, mtu);
        final int payloadSize = packetSize - 7;
        final int packets = (bytes.length + payloadSize - 1) / payloadSize;
        final List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < packets; i++) {
            final int len = Math.min(payloadSize, bytes.length - i * payloadSize);
            final byte[] frame = new byte[7 + len];
            frame[0] = CMD_BARRAGE;
            frame[1] = 0x01;
            frame[2] = (byte) index;
            frame[3] = (byte) packets;
            frame[4] = (byte) (i + 1);
            frame[5] = (byte) (bytes.length & 0xFF);
            frame[6] = (byte) ((bytes.length >> 8) & 0xFF);
            System.arraycopy(bytes, i * payloadSize, frame, 7, len);
            frames.add(frame);
        }
        return frames;
    }

    public List<byte[]> encodeContacts(@NonNull final List<? extends nodomain.freeyourgadget.gadgetbridge.model.Contact> contacts) {
        final List<byte[]> frames = new ArrayList<>();
        int index = 1;
        for (final nodomain.freeyourgadget.gadgetbridge.model.Contact contact : contacts) {
            frames.add(encodeContactName(index, contact.getName()));
            frames.add(encodeContactNumber(index, contact.getNumber()));
            index++;
        }
        frames.add(contactEnd((byte) 0x01));
        frames.add(contactEnd((byte) 0x02));
        return frames;
    }

    private byte[] encodeContactName(final int index, @Nullable String name) {
        if (name == null) name = "";
        if (name.length() > 6) name = name.substring(0, 6);
        final byte[] frame = new byte[20];
        frame[0] = CMD_SYNC_CONTACT;
        frame[1] = 0x01;
        frame[2] = (byte) (index >> 8);
        frame[3] = (byte) index;
        frame[4] = (byte) name.length();
        final byte[] be = name.getBytes(StandardCharsets.UTF_16BE);
        System.arraycopy(be, 0, frame, 5, Math.min(be.length, 12));
        for (int i = 5 + Math.min(be.length, 12); i < 17; i++) frame[i] = (byte) 0xFF;
        frame[19] = checksum8(frame, 19);
        return frame;
    }

    private byte[] encodeContactNumber(final int index, @Nullable final String rawNumber) {
        final String number = rawNumber == null ? "" : rawNumber.replaceAll("[^0-9+]", "");
        final byte[] frame = new byte[20];
        frame[0] = CMD_SYNC_CONTACT;
        frame[1] = 0x02;
        frame[2] = (byte) (index >> 8);
        frame[3] = (byte) index;
        frame[4] = (byte) Math.min(number.length(), 14);
        for (int i = 0; i < number.length() && i < 14; i++) {
            final char c = number.charAt(i);
            frame[5 + i] = c == '+' ? (byte) '+' : (byte) Character.digit(c, 10);
        }
        frame[19] = checksum8(frame, 19);
        return frame;
    }

    private byte[] contactEnd(final byte type) {
        final byte[] frame = new byte[20];
        frame[0] = CMD_SYNC_CONTACT;
        frame[1] = type;
        frame[2] = (byte) 0xFF;
        frame[3] = (byte) 0xFF;
        frame[19] = checksum8(frame, 19);
        return frame;
    }

    static byte[] withChecksum(final byte[] data) {
        final byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        int sum = 0;
        for (final byte b : data) sum += b & 0xFF;
        out[data.length] = (byte) (sum & 0xFF);
        out[data.length + 1] = (byte) ((sum >>> 8) & 0xFF);
        return out;
    }

    @Override
    public GBDeviceEvent[] decodeResponse(final byte[] data) {
        if (data == null || data.length == 0) return new GBDeviceEvent[0];
        switch (data[0] & 0xFF) {
            case 0x81: return ackFamily("SyncTime", data, null);
            case 0x83: return decodeFunctionInfo(data);
            case 0x85: return ackFamily("Weather FIR", data, DeviceFamily.FIR);
            case 0x90: return ackFamily("Weather JL", data, DeviceFamily.JL);
            case 0x94: return decodeClassicBattery(data);
            case 0x99: return decodeMusicControl(data);
            case 0x9F: return decodeFirmwareVersion(data);
            case 0xA5: return ackFamily("Weather ZK", data, DeviceFamily.ZK);
            case 0x0D: return ackFamily("Device requested time", data, null);
            case 0x53: return decodeFindPhone(data);
            case 0xFB: return decodeFbFrame(data);
            default:
                LOG.debug("FreeFit V2 unhandled frame opcode 0x{}", Integer.toHexString(data[0] & 0xFF));
                return new GBDeviceEvent[0];
        }
    }

    private GBDeviceEvent[] decodeFbFrame(final byte[] data) {
        if (data.length < 3) return new GBDeviceEvent[0];
        final int rw = data[2] & 0xFF;
        if (rw != 2 && rw != 3) return new GBDeviceEvent[0];
        if ((data[1] & 0xFF) == SUB_BATTERY) return decodeBattery(data);
        LOG.debug("FreeFit V2 FB subcmd 0x{} ack/push", Integer.toHexString(data[1] & 0xFF));
        return new GBDeviceEvent[0];
    }

    GBDeviceEvent[] decodeBattery(final byte[] data) {
        if (data.length < 6) return new GBDeviceEvent[0];
        final int declaredLen = data.length >= 4 ? data[3] & 0xFF : data.length;
        final int payloadLen = Math.max(0, Math.min(declaredLen, data.length) - 6);
        final int count = Math.min(3, Math.max(2, payloadLen));
        final GBDeviceEvent[] events = new GBDeviceEvent[count];
        for (int i = 0; i < count; i++) {
            events[i] = batteryEvent(i, data[4 + i]);
        }
        return events;
    }

    GBDeviceEvent[] decodeClassicBattery(final byte[] data) {
        if (data.length < 3) return new GBDeviceEvent[0];
        return new GBDeviceEvent[]{batteryEvent(0, data[1]), batteryEvent(1, data[2])};
    }

    @Nullable
    GBDeviceEvent[] decodeFirmwareVersion(final byte[] data) {
        if (data.length < 2) return new GBDeviceEvent[0];
        final String full = new String(data, 1, data.length - 1, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
        final GBDeviceEventVersionInfo evt = new GBDeviceEventVersionInfo();
        if (full.length() > 10) {
            evt.hwVersion = full.substring(0, 10);
            evt.fwVersion = full.substring(10);
        } else {
            evt.fwVersion = full;
        }
        return new GBDeviceEvent[]{evt};
    }

    GBDeviceEvent[] decodeFunctionInfo(final byte[] data) {
        if (data.length >= 10) {
            final boolean supportsAnc = (data[5] & 0xFF) == 1;
            final boolean supportsCustomEq = (data[6] & 0xFF) == 1;
            final int aiMode = (data[7] & 0xFF) + 1;
            final int fwRev = (data[8] & 0xFF) | ((data[9] & 0xFF) << 8);
            LOG.info("FreeFit V2 function info: anc={} customEq={} aiMode={} fwRev={} maxName={}",
                    supportsAnc, supportsCustomEq, aiMode, fwRev, data[4] & 0xFF);
        }
        supportsUploadMessage = data.length > 18 && (data[18] & 0x01) != 0;
        return new GBDeviceEvent[0];
    }

    private GBDeviceEvent[] decodeFindPhone(final byte[] data) {
        final GBDeviceEventFindPhone evt = new GBDeviceEventFindPhone();
        evt.event = data.length > 1 && data[1] == 0 ? GBDeviceEventFindPhone.Event.STOP : GBDeviceEventFindPhone.Event.START;
        return new GBDeviceEvent[]{evt};
    }

    private GBDeviceEvent[] decodeMusicControl(final byte[] data) {
        if (data.length < 2) return new GBDeviceEvent[0];
        final GBDeviceEventMusicControl.Event event;
        switch (data[1] & 0xFF) {
            case 0: event = GBDeviceEventMusicControl.Event.PAUSE; break;
            case 1: event = GBDeviceEventMusicControl.Event.PLAYPAUSE; break;
            case 2: event = GBDeviceEventMusicControl.Event.PLAY; break;
            case 3: event = GBDeviceEventMusicControl.Event.PREVIOUS; break;
            case 4: event = GBDeviceEventMusicControl.Event.NEXT; break;
            case 5: event = GBDeviceEventMusicControl.Event.VOLUMEUP; break;
            case 6: event = GBDeviceEventMusicControl.Event.VOLUMEDOWN; break;
            default: return new GBDeviceEvent[0];
        }
        return new GBDeviceEvent[]{new GBDeviceEventMusicControl(event)};
    }

    private GBDeviceEventBatteryInfo batteryEvent(final int index, final byte raw) {
        final GBDeviceEventBatteryInfo evt = new GBDeviceEventBatteryInfo();
        evt.batteryIndex = index;
        evt.level = raw & 0x7F;
        evt.state = (raw & 0x80) != 0 ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;
        return evt;
    }

    private GBDeviceEvent[] ackFamily(final String label, final byte[] data, @Nullable final DeviceFamily detectedFamily) {
        if (detectedFamily != null) family = detectedFamily;
        LOG.debug("FreeFit V2 ack {} ({} bytes)", label, data.length);
        return new GBDeviceEvent[0];
    }

    private List<byte[]> chunkUtf8(final byte opcode, final int appId, @NonNull final String text, final boolean addEndMarker) {
        final byte[] body = text.getBytes(StandardCharsets.UTF_8);
        final List<byte[]> frames = new ArrayList<>();
        final int chunks = Math.max(1, (body.length + 16) / 17);
        for (int i = 0; i < chunks; i++) {
            final int offset = i * 17;
            final int len = Math.min(17, Math.max(0, body.length - offset));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(opcode);
            out.write(i & 0xFF);
            out.write(appId & 0xFF);
            out.write(body, offset, len);
            if (addEndMarker && i == chunks - 1 && len < 17) out.write(0xFF);
            frames.add(out.toByteArray());
        }
        return frames;
    }

    private byte[] prefixedText(final byte opcode, final int arg, @Nullable final String text, final Charset charset, final int maxBytes) {
        final byte[] src = (text == null ? "" : text).getBytes(charset);
        final int len = Math.min(src.length, maxBytes);
        final byte[] out = new byte[len + 2];
        out[0] = opcode;
        out[1] = (byte) arg;
        System.arraycopy(src, 0, out, 2, len);
        return out;
    }

    private String safeJoin(@Nullable final String a, @Nullable final String b) {
        final String aa = a == null ? "" : a;
        final String bb = b == null ? "" : b;
        return aa.isEmpty() ? bb : (bb.isEmpty() ? aa : aa + " " + bb);
    }

    private byte[] encodeCallState(final int state, @Nullable final String name, @Nullable final String number) {
        final byte[] nameBytes = (name == null ? "" : name).getBytes(StandardCharsets.UTF_8);
        final byte[] numberBytes = (number == null ? "" : number).getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[nameBytes.length + numberBytes.length + 4];
        out[0] = CMD_CALL_PHONE;
        out[1] = (byte) state;
        out[2] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, out, 3, nameBytes.length);
        out[3 + nameBytes.length] = (byte) numberBytes.length;
        System.arraycopy(numberBytes, 0, out, 4 + nameBytes.length, numberBytes.length);
        return out;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static byte checksum8(final byte[] data, final int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        return (byte) sum;
    }
}
