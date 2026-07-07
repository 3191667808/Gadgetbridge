package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sport_x20;

import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcorePacket;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.liberty.SoundcoreLibertyProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class SoundcoreSportX20Protocol extends SoundcoreLibertyProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreSportX20Protocol.class);

    private static final short CMD_SET_EQUALIZER = (short) 0x8703;
    private static final short CMD_SET_3D_SURROUND = (short) 0x8602;
    private static final short CMD_SET_DUAL_CONNECTION = (short) 0x840b;
    private static final short CMD_SET_FIT_TEST = (short) 0x0109;
    // Session handshake sent after CMD_GET_DEVICE_INFO; device ACKs with empty payload
    private static final short CMD_SESSION_INIT = (short) 0x8105;
    // Unsolicited notifications from the device on connection
    private static final short CMD_NOTIFY_PAIRED_DEVICES = (short) 0x010b;
    private static final short CMD_NOTIFY_CONNECTION_STATUS = (short) 0x020b;
    private static final short CMD_NOTIFY_DEVICE_STATE = (short) 0x0910;

    // Payload offset (within CMD_GET_DEVICE_INFO response) where the 6 control-function
    // bytes start: L_single, R_single, L_double, R_double, L_long, R_long
    private static final int DEVICE_INFO_CONTROL_OFFSET = 110;
    private static final int DEVICE_INFO_CONTROL_LENGTH = 6;

    // Offset within CMD_GET_DEVICE_INFO payload where the 6-byte audio-mode state mirror starts.
    // Bytes [AUDIO_OFFSET .. AUDIO_OFFSET+5] have the same layout as CMD_NOTIFY_AUDIO_MODE payload:
    //   [0]=transparency_active  [1]=mode_byte  [2]=voice_mode  [3]=adaptive_nc  [4]=wind  [5]=0xff
    private static final int DEVICE_INFO_AUDIO_OFFSET = 117;
    private static final int DEVICE_INFO_AUDIO_LENGTH = 6;

    // Offset within CMD_GET_DEVICE_INFO payload for the touch tone boolean (0x01=on, 0x00=off).
    private static final int DEVICE_INFO_TOUCH_TONE_OFFSET = 127;

    // Offsets within CMD_GET_DEVICE_INFO payload for auto power off.
    // [128] = enabled flag (0x01=on, 0x00=never/disabled)
    // [129] = duration raw value when enabled: 0x00=10min, 0x01=20min, 0x02=30min, 0x03=60min
    //         maps to preference integer: duration = raw + 1  (1=10min, 2=20min, 3=30min, 4=60min)
    //         preference 0 = never (disabled)
    private static final int DEVICE_INFO_AUTO_POWER_OFF_ENABLED_OFFSET = 128;
    private static final int DEVICE_INFO_AUTO_POWER_OFF_OFFSET = 129;

    private static final int CUSTOM_PRESET_ID = 0xfe;
    private static final int EQ_BANDS = 8;

    public static final String[] EQUALIZER_PREFS_VALUE = new String[]{
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND1_VALUE,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND2_VALUE,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND3_VALUE,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND4_VALUE,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND5_VALUE,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND6_VALUE,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND7_VALUE,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND8_VALUE,
    };

    private static final int[] EQ_TAIL_SCALE = new int[]{5, 3, 4, 4, 4, 4, 4, 6};
    private static final int EQ_TAIL_DIVISOR = 6;

    private static final String PRESET_CHANNEL_SEPARATOR = "00";
    private static final String PRESET_SUFFIX = "0000";
    private static final String PRESET_COMMON_REPEATED_BLOCK = "9fa18a998a8077643c3c";
    private static final String PRESET_COMMON_PREFIX = "00000200";
    private static final String PRESET_COMMON_MIDDLE = "69d64e6a00";
    private static final String PRESET_COMMON_SECTION =
        PRESET_COMMON_PREFIX
            + PRESET_COMMON_REPEATED_BLOCK
            + PRESET_COMMON_REPEATED_BLOCK
            + PRESET_COMMON_MIDDLE
            + PRESET_COMMON_REPEATED_BLOCK
            + PRESET_COMMON_REPEATED_BLOCK;

    private static final Map<Integer, byte[]> EQ_PRESET_PAYLOADS = buildPresetPayloads();

    protected SoundcoreSportX20Protocol(final GBDevice device) {
        super(device);
    }

    @Override
    public GBDeviceEvent[] decodeResponse(final byte[] responseData) {
        final SoundcorePacket packet = decodePacket(responseData);

        if (packet != null && packet.getCommand() == CMD_NOTIFY_AUDIO_MODE) {
            decodeAdvancedAudioMode(packet.getPayload());
            return new GBDeviceEvent[0];
        }

        if (packet != null && packet.getCommand() == CMD_SET_EQUALIZER) {
            decodeEqualizer(packet.getPayload());
            return new GBDeviceEvent[0];
        }

        if (packet != null && packet.getCommand() == CMD_GET_DEVICE_INFO) {
            // Decode button-control functions embedded in the device-info response,
            // then fall through so the super class handles battery / firmware / serial.
            decodeControlFunctionsFromDeviceInfo(packet.getPayload());
        }

        if (packet != null && packet.getCommand() == CMD_SESSION_INIT) {
            // Empty ACK from the device to our session-init request – nothing to do.
            return new GBDeviceEvent[0];
        }

        if (packet != null && packet.getCommand() == CMD_NOTIFY_PAIRED_DEVICES) {
            decodePairedDevices(packet.getPayload());
            return new GBDeviceEvent[0];
        }

        if (packet != null && packet.getCommand() == CMD_NOTIFY_CONNECTION_STATUS) {
            LOG.debug("Connection status notification, {} bytes", packet.getPayload().length);
            return new GBDeviceEvent[0];
        }

        if (packet != null && packet.getCommand() == CMD_NOTIFY_DEVICE_STATE) {
            LOG.debug("Device state notification, {} bytes", packet.getPayload().length);
            return new GBDeviceEvent[0];
        }

        return super.decodeResponse(responseData);
    }

    /** Requests extended device configuration (firmware details, serial, settings). */
    byte[] encodeExtendedInfoRequest() {
        return encodeRequest(CMD_GET_UNKNOWN_DATA_0105);
    }

    /** Sent after CMD_GET_DEVICE_INFO to finalise the session with the device. */
    byte[] encodeSessionInitRequest() {
        return encodeRequest(CMD_SESSION_INIT);
    }

    /**
     * Reads the six button-control function bytes from the CMD_GET_DEVICE_INFO response
     * (payload offsets 110–115) and the six audio-mode state bytes (offsets 117–122),
     * then persists them all as preferences.
     *
     * Control layout: L_single | R_single | L_double | R_double | L_long | R_long
     * Each byte: high-nibble = action prefix (unused here), low-nibble = TapFunction code.
     *
     * Audio layout at offset 117 mirrors CMD_NOTIFY_AUDIO_MODE payload exactly.
     */
    private void decodeControlFunctionsFromDeviceInfo(final byte[] payload) {
        if (payload.length < DEVICE_INFO_CONTROL_OFFSET + DEVICE_INFO_CONTROL_LENGTH) {
            LOG.warn("CMD_GET_DEVICE_INFO payload too short to decode controls: {} bytes", payload.length);
            return;
        }

        final TapFunction lSingle = functionFromCode(payload[DEVICE_INFO_CONTROL_OFFSET]     & 0x0f);
        final TapFunction rSingle = functionFromCode(payload[DEVICE_INFO_CONTROL_OFFSET + 1] & 0x0f);
        final TapFunction lDouble = functionFromCode(payload[DEVICE_INFO_CONTROL_OFFSET + 2] & 0x0f);
        final TapFunction rDouble = functionFromCode(payload[DEVICE_INFO_CONTROL_OFFSET + 3] & 0x0f);
        final TapFunction lLong   = functionFromCode(payload[DEVICE_INFO_CONTROL_OFFSET + 4] & 0x0f);
        final TapFunction rLong   = functionFromCode(payload[DEVICE_INFO_CONTROL_OFFSET + 5] & 0x0f);

        LOG.debug("Control functions from device info: L_single={} R_single={} L_double={} R_double={} L_long={} R_long={}",
                lSingle, rSingle, lDouble, rDouble, lLong, rLong);

        final SharedPreferences.Editor editor = getDevicePrefs().getPreferences().edit();
        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT,  lSingle.name());
        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT, rSingle.name());
        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT,  lDouble.name());
        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT, rDouble.name());
        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT,  lLong.name());
        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT, rLong.name());
        editor.apply();

        // Audio state is mirrored at offsets 117-122 using the same format as CMD_NOTIFY_AUDIO_MODE.
        // The device does not always send a separate CMD_NOTIFY_AUDIO_MODE on connection, so this
        // is the primary source of the current audio mode on connect.
        if (payload.length >= DEVICE_INFO_AUDIO_OFFSET + DEVICE_INFO_AUDIO_LENGTH) {
            final byte[] audioPayload = java.util.Arrays.copyOfRange(
                    payload, DEVICE_INFO_AUDIO_OFFSET, DEVICE_INFO_AUDIO_OFFSET + DEVICE_INFO_AUDIO_LENGTH);
            LOG.debug("Decoding audio state from CMD_GET_DEVICE_INFO mirror: {}",
                    StringUtils.bytesToHex(audioPayload));
            decodeAdvancedAudioMode(audioPayload);
        }

        // Touch tone boolean at offset 127 (0x01=on, 0x00=off).
        if (payload.length > DEVICE_INFO_TOUCH_TONE_OFFSET) {
            final boolean touchTone = payload[DEVICE_INFO_TOUCH_TONE_OFFSET] == 0x01;
            LOG.debug("Touch tone from device info: {}", touchTone);
            getDevicePrefs().getPreferences().edit()
                    .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE, touchTone)
                    .apply();
        }

        // Auto power off: enabled flag at offset 128, duration at offset 129.
        // duration=0 means never (disabled); 1=10min, 2=20min, 3=30min, 4=60min.
        if (payload.length > DEVICE_INFO_AUTO_POWER_OFF_OFFSET) {
            final boolean autoPowerEnabled = payload[DEVICE_INFO_AUTO_POWER_OFF_ENABLED_OFFSET] == 0x01;
            final int autoPowerDuration = autoPowerEnabled ? (payload[DEVICE_INFO_AUTO_POWER_OFF_OFFSET] & 0xFF) + 1 : 0;
            LOG.debug("Auto power off from device info: enabled={} duration={}", autoPowerEnabled, autoPowerDuration);
            getDevicePrefs().getPreferences().edit()
                    .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF, String.valueOf(autoPowerDuration))
                    .apply();
        }
    }

    /**
     * Logs the unsolicited paired-device list sent by the device on connection.
     * Payload layout: 4-byte header, then per device: 6-byte BT address + 40-byte name (UTF-8, zero-padded).
     */
    private void decodePairedDevices(final byte[] payload) {
        // Header: [0]=connected_count [1]=?? [2]=name_field_len(40) [3]=??
        if (payload.length < 4) {
            return;
        }
        final int nameLen = Byte.toUnsignedInt(payload[2]);  // 0x28 = 40
        final int entrySize = 6 + nameLen;
        int offset = 4;
        int index = 0;
        while (offset + entrySize <= payload.length) {
            final byte[] nameBytes = new byte[nameLen];
            System.arraycopy(payload, offset + 6, nameBytes, 0, nameLen);
            // Trim null-padding
            int nameEnd = 0;
            while (nameEnd < nameLen && nameBytes[nameEnd] != 0) nameEnd++;
            final String name = new String(nameBytes, 0, nameEnd, java.nio.charset.StandardCharsets.UTF_8);
            LOG.debug("Paired device {}: addr={} name='{}'",
                    index,
                    String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                            payload[offset+5], payload[offset+4], payload[offset+3],
                            payload[offset+2], payload[offset+1], payload[offset]),
                    name);
            offset += entrySize;
            index++;
        }
    }

    /** Maps a low-nibble TapFunction code (0–15) back to the TapFunction enum. */
    private static TapFunction functionFromCode(final int code) {
        for (final TapFunction f : TapFunction.values()) {
            if (f.getCode() == code) {
                return f;
            }
        }
        return TapFunction.NONE;
    }

    @Override
    public byte[] encodeSendConfiguration(final String config) {
        final Prefs prefs = getDevicePrefs();
        String prefString;

        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TRANSPARENCY_VOCAL_MODE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING:
            case DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL:
                return encodeAdvancedAudioMode(false);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT, "PLAYPAUSE");
                return encodeControlFunction(TapAction.SINGLE_TAP, false, TapFunction.valueOf(prefString));
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT, "PLAYPAUSE");
                return encodeControlFunction(TapAction.SINGLE_TAP, true, TapFunction.valueOf(prefString));

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT, "MEDIA_PREV");
                return encodeControlFunction(TapAction.DOUBLE_TAP, false, TapFunction.valueOf(prefString));
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT, "MEDIA_NEXT");
                return encodeControlFunction(TapAction.DOUBLE_TAP, true, TapFunction.valueOf(prefString));

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT, "AMBIENT_SOUND_CONTROL");
                return encodeControlFunction(TapAction.LONG_PRESS, false, TapFunction.valueOf(prefString));
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT, "AMBIENT_SOUND_CONTROL");
                return encodeControlFunction(TapAction.LONG_PRESS, true, TapFunction.valueOf(prefString));

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF:
                final int duration = Integer.parseInt(prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF, "3"));
                return encodeAutoPowerOff(CMD_SET_AUTO_POWER_OFF, duration, (byte) 0x03);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE:
                final boolean pressAlert = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE, false);
                return encodeBooleanCommand(CMD_SET_TOUCH_TONE, pressAlert);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND:
                final boolean surround3d = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND, false);
                return encodeBooleanCommand(CMD_SET_3D_SURROUND, surround3d);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_DUAL_CONNECTION:
                final boolean dualConnection = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_DUAL_CONNECTION, false);
                return encodeBooleanCommand(CMD_SET_DUAL_CONNECTION, dualConnection);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_PRESET:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND1_VALUE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND2_VALUE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND3_VALUE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND4_VALUE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND5_VALUE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND6_VALUE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND7_VALUE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND8_VALUE:
                return encodeEqualizer();

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_FIT_TEST:
                return encodeCommand(CMD_SET_FIT_TEST, new byte[]{0x0a});

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ENABLE_PAIRING_MODE:
                return encodePairingMode();

            default:
                return super.encodeSendConfiguration(config);
        }
    }

    private byte[] encodeControlFunction(final TapAction action, final boolean right, final TapFunction function) {
        final byte functionByte;
        switch (action) {
            case SINGLE_TAP:
                functionByte = (byte) (16 * 6 + function.getCode());
                break;
            case DOUBLE_TAP:
                functionByte = (byte) (16 * 3 + function.getCode());
                break;
            case LONG_PRESS:
                functionByte = (byte) (16 * 4 + function.getCode());
                break;
            default:
                return null;
        }

        return encodeControlFunction(right, action.getCode(), functionByte);
    }

    private byte[] encodeEqualizer() {
        final Prefs prefs = getDevicePrefs();
        final int preset = Integer.parseInt(prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_PRESET, "0"));

        if (preset != CUSTOM_PRESET_ID) {
            final byte[] presetPayload = EQ_PRESET_PAYLOADS.get(preset);
            if (presetPayload != null) {
                return encodeCommand(CMD_SET_EQUALIZER, presetPayload);
            }
        }

        final int[] rawBands = new int[EQ_BANDS];
        final int[] tailBands = new int[EQ_BANDS];

        for (int i = 0; i < EQ_BANDS; i++) {
            final int value = clamp(prefs.getInt(EQUALIZER_PREFS_VALUE[i], 0), -6, 6);

            rawBands[i] = 120 + (value * 10);
            tailBands[i] = 120 + Math.round((float) (value * EQ_TAIL_SCALE[i]) / EQ_TAIL_DIVISOR);
        }

        final byte[] payload = buildPresetPayload(
            CUSTOM_PRESET_ID,
            bandsToHex(rawBands),
            bandsToHex(tailBands)
        );

        return encodeCommand(CMD_SET_EQUALIZER, payload);
    }

    private void decodeEqualizer(final byte[] payload) {
        if (payload.length < 90) {
            return;
        }

        final int preset = Byte.toUnsignedInt(payload[0]);
        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        final SharedPreferences.Editor editor = prefs.edit();

        editor.putString(
                DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_PRESET,
                String.valueOf(preset)
        );

        for (int i = 0; i < EQ_BANDS; i++) {
            final int rawBand = Byte.toUnsignedInt(payload[4 + i]);
            final int value = clamp(Math.round((rawBand - 120) / 10f), -6, 6);
            editor.putInt(EQUALIZER_PREFS_VALUE[i], value);
        }

        editor.apply();
    }

    private static Map<Integer, byte[]> buildPresetPayloads() {
        final Map<Integer, byte[]> presetPayloads = new HashMap<>();

        presetPayloads.put(0x00, buildPresetPayload(0x00, "787878787878787878", "787878787878787878"));
        presetPayloads.put(0x01, buildPresetPayload(0x01, "a0828c8ca0a0a08c78", "7d767b787c7a7c7978"));
        presetPayloads.put(0x02, buildPresetPayload(0x02, "a09682787878787878", "7b7a78787878787878"));
        presetPayloads.put(0x03, buildPresetPayload(0x03, "505a6e787878787878", "757678787878787878"));
        presetPayloads.put(0x04, buildPresetPayload(0x04, "96966464788c96a078", "7a7c7477787a797d78"));
        presetPayloads.put(0x05, buildPresetPayload(0x05, "5a8ca0a0968c786478", "747b7a7b797a787578"));
        presetPayloads.put(0x06, buildPresetPayload(0x06, "8c5a6e828c8c825a78", "7c7378787a797b7378"));
        presetPayloads.put(0x07, buildPresetPayload(0x07, "8c8296968c64504678", "7a777b797b76767378"));
        presetPayloads.put(0x08, buildPresetPayload(0x08, "968c648c828c969678", "7a7b737d777a7a7b78"));
        presetPayloads.put(0x09, buildPresetPayload(0x09, "64646e787878646478", "767777787879767678"));
        presetPayloads.put(0x0a, buildPresetPayload(0x0a, "8c966e6e8c6e8c9678", "797c76767d747b7b78"));
        presetPayloads.put(0x0b, buildPresetPayload(0x0b, "8c8c6464788c96a078", "797b7577787a797d78"));
        presetPayloads.put(0x0c, buildPresetPayload(0x0c, "78786464647896aa78", "7879767776787a7e78"));
        presetPayloads.put(0x0d, buildPresetPayload(0x0d, "6e8ca09678648c8278", "767a7b7a78747c7878"));
        presetPayloads.put(0x0e, buildPresetPayload(0x0e, "7896968ca0aa96a078", "777b7a787b7c787d78"));
        presetPayloads.put(0x0f, buildPresetPayload(0x0f, "6e829696826e645a78", "77797a7a7977777578"));
        presetPayloads.put(0x10, buildPresetPayload(0x10, "b48c64648c9696a078", "7e7976757b7a797d78"));
        presetPayloads.put(0x11, buildPresetPayload(0x11, "968c6e6e8296a0aa78", "7a7a7677797a7a7e78"));
        presetPayloads.put(0x12, buildPresetPayload(0x12, "a0968278645a505078", "7b7a78797676757478"));
        presetPayloads.put(0x13, buildPresetPayload(0x13, "5a64828c8c82785a78", "76767a797a78797478"));
        presetPayloads.put(0x14, buildPresetPayload(0x14, "6464646e828c8ca078", "76777777797a787d78"));
        presetPayloads.put(0x15, buildPresetPayload(0x15, "787878645a50503c78", "787879767775777178"));

        return presetPayloads;
    }

    private static byte[] buildPresetPayload(final int presetId, final String bands, final String tails) {
        return decodeHex(
                toHexByte(presetId)
                        + "000000"
                        + bands
                        + PRESET_CHANNEL_SEPARATOR
                        + bands
                        + PRESET_COMMON_SECTION
                        + tails
                        + PRESET_CHANNEL_SEPARATOR
                        + tails
                        + PRESET_SUFFIX
        );
    }

    private static String bandsToHex(final int[] bands) {
        final StringBuilder sb = new StringBuilder(bands.length * 2);

        for (final int band : bands) {
            sb.append(toHexByte(band));
        }

        return sb.toString();
    }

    private static String toHexByte(final int value) {
        final String hex = Integer.toHexString(value & 0xff);
        return hex.length() < 2 ? "0" + hex : hex;
    }

    private static byte[] decodeHex(final String hex) {
        final int len = hex.length();
        final byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }

        return data;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
