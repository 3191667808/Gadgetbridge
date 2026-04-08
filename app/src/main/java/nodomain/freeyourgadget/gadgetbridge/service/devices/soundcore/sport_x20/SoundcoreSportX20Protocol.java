package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sport_x20;

import android.content.SharedPreferences;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcorePacket;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.liberty.SoundcoreLibertyProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreSportX20Protocol extends SoundcoreLibertyProtocol {
    private static final short CMD_SET_EQUALIZER = (short) 0x8703;
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

    private static final byte[] EQ_CUSTOM_TEMPLATE = decodeHex(
            "fefe00007878787878787878787878787878787878780002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c787878787878787878007878787878787878780000"
    );

    private static final Map<Integer, byte[]> EQ_PRESET_PAYLOADS = buildPresetPayloads();

    protected SoundcoreSportX20Protocol(final GBDevice device) {
        super(device);
    }

    @Override
    public GBDeviceEvent[] decodeResponse(final byte[] responseData) {
        final ByteBuffer buf = ByteBuffer.wrap(responseData);
        final SoundcorePacket packet = SoundcorePacket.decode(buf);

        if (packet != null && packet.getCommand() == (short) 0x0106) {
            decodeAncAudioMode(packet.getPayload());
            return new GBDeviceEvent[0];
        }

        if (packet != null && packet.getCommand() == CMD_SET_EQUALIZER) {
            decodeEqualizer(packet.getPayload());
            return new GBDeviceEvent[0];
        }

        return super.decodeResponse(responseData);
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
                return encodeAncAudioMode();

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT, "PLAYPAUSE");
                return encodeControlFunctionMessage(TapAction.SINGLE_TAP, false, TapFunction.valueOf(prefString));
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT, "PLAYPAUSE");
                return encodeControlFunctionMessage(TapAction.SINGLE_TAP, true, TapFunction.valueOf(prefString));

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT, "MEDIA_PREV");
                return encodeControlFunctionMessage(TapAction.DOUBLE_TAP, false, TapFunction.valueOf(prefString));
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT, "MEDIA_NEXT");
                return encodeControlFunctionMessage(TapAction.DOUBLE_TAP, true, TapFunction.valueOf(prefString));

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT, "AMBIENT_SOUND_CONTROL");
                return encodeControlFunctionMessage(TapAction.LONG_PRESS, false, TapFunction.valueOf(prefString));
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT:
                prefString = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT, "AMBIENT_SOUND_CONTROL");
                return encodeControlFunctionMessage(TapAction.LONG_PRESS, true, TapFunction.valueOf(prefString));

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF:
                final int duration = Integer.parseInt(prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF, "3"));
                return encodeAutoPowerOff(duration);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE:
                final boolean pressAlert = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE, false);
                return new SoundcorePacket((short) 0x8301, new byte[]{encodeBoolean(pressAlert)}).encode();

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND:
                final boolean surround3d = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND, false);
                return new SoundcorePacket((short) 0x8602, new byte[]{encodeBoolean(surround3d)}).encode();

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

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ENABLE_PAIRING_MODE:
                return new SoundcorePacket((short) 0x850b, new byte[]{0x00, (byte) 0x90}).encode();

            default:
                return super.encodeSendConfiguration(config);
        }
    }

    @Override
    public byte[] encodeFindDevice(final boolean start) {
        final boolean findLeft = start;
        final boolean findRight = start;
        final byte[] payload = new byte[]{encodeBoolean(findLeft), encodeBoolean(findRight), 0x00};
        return new SoundcorePacket((short) 0x8910, payload).encode();
    }

    private byte[] encodeControlFunctionMessage(final TapAction action, final boolean right, final TapFunction function) {
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

        final byte[] payload = new byte[]{encodeBoolean(right), action.getCode(), functionByte};
        return new SoundcorePacket((short) 0x8104, payload).encode();
    }

    /**
     * 0: No Auto Power off
     * 1: Auto Power off 10 min
     * 2: Auto Power off 20 min
     * 3: Auto Power off 30 min
     * 4: Auto Power off 60 min
     */
    private byte[] encodeAutoPowerOff(final int duration) {
        final byte[] payload;

        if (duration > 0) {
            payload = new byte[]{(byte) 0x01, (byte) (duration - 1)};
        } else {
            payload = new byte[]{(byte) 0x00, (byte) 0x03};
        }

        return new SoundcorePacket((short) 0x8601, payload).encode();
    }

    private void decodeAncAudioMode(final byte[] payload) {
        if (payload.length < 5) {
            return;
        }

        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        final SharedPreferences.Editor editor = prefs.edit();

        String ambientSoundMode = "off";
        if (payload[0] == 0x00) {
            ambientSoundMode = "noise_cancelling";
        } else if (payload[0] == 0x01) {
            ambientSoundMode = "ambient_sound";
        } else if (payload[0] == 0x02) {
            ambientSoundMode = "off";
        }

        int ancStrength = ((payload[1] & 0x30) >> 4) - 1;

        final boolean vocalMode = (payload[2] == 0x01);
        final boolean adaptiveAnc = (payload[3] == 0x01);
        final boolean windNoiseReduction = (payload[4] == 0x01);

        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, ambientSoundMode);
        editor.putInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, ancStrength);
        editor.putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TRANSPARENCY_VOCAL_MODE, vocalMode);
        editor.putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING, adaptiveAnc);
        editor.putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, windNoiseReduction);
        editor.apply();
    }

    private byte[] encodeAncAudioMode() {
        final Prefs prefs = getDevicePrefs();

        final String ambientMode = prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, "off");
        final int ancStrengthValue = prefs.getInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, 0);
        final boolean vocalMode = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TRANSPARENCY_VOCAL_MODE, false);
        final boolean adaptive = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING, true);
        final boolean windReduction = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, false);

        if (ancStrengthValue < 0 || ancStrengthValue > 2) {
            return null;
        }
        final byte ancStrengthByte = (byte) ((ancStrengthValue + 1) << 4);

        final byte ambientModeByte;
        switch (ambientMode) {
            case "noise_cancelling":
                ambientModeByte = 0x00;
                break;
            case "ambient_sound":
                ambientModeByte = 0x01;
                break;
            case "off":
                ambientModeByte = 0x02;
                break;
            default:
                return null;
        }

        final byte adaptiveByte = encodeBoolean(adaptive);
        final byte vocalModeByte = encodeBoolean(vocalMode);
        final byte windReductionByte = encodeBoolean(windReduction);

        final byte[] payload = new byte[]{ambientModeByte, ancStrengthByte, vocalModeByte, adaptiveByte, windReductionByte};
        return new SoundcorePacket((short) 0x8106, payload).encode();
    }

    private byte[] encodeEqualizer() {
        final Prefs prefs = getDevicePrefs();
        final int preset = Integer.parseInt(prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_PRESET, "0"));

        if (preset != CUSTOM_PRESET_ID) {
            final byte[] presetPayload = EQ_PRESET_PAYLOADS.get(preset);
            if (presetPayload != null) {
                return new SoundcorePacket(CMD_SET_EQUALIZER, presetPayload).encode();
            }
        }

        final byte[] payload = EQ_CUSTOM_TEMPLATE.clone();

        for (int i = 0; i < EQ_BANDS; i++) {
            final int value = clamp(prefs.getInt(EQUALIZER_PREFS_VALUE[i], 0), -6, 6);

            final int rawBand = 120 + (value * 10);
            final int tailBand = 120 + Math.round((float) (value * EQ_TAIL_SCALE[i]) / EQ_TAIL_DIVISOR);

            payload[4 + i] = (byte) rawBand;
            payload[13 + i] = (byte) rawBand;
            payload[72 + i] = (byte) tailBand;
            payload[82 + i] = (byte) tailBand;
        }

        return new SoundcorePacket(CMD_SET_EQUALIZER, payload).encode();
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

        presetPayloads.put(0x00, decodeHex("0000000078787878787878787800787878787878787878000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c787878787878787878007878787878787878780000"));
        presetPayloads.put(0x01, decodeHex("01000000a0828c8ca0a0a08c7800a0828c8ca0a0a08c78000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7d767b787c7a7c7978007d767b787c7a7c79780000"));
        presetPayloads.put(0x02, decodeHex("02000000a0968278787878787800a09682787878787878000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7b7a78787878787878007b7a787878787878780000"));
        presetPayloads.put(0x03, decodeHex("03000000505a6e78787878787800505a6e787878787878000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c757678787878787878007576787878787878780000"));
        presetPayloads.put(0x04, decodeHex("0400000096966464788c96a0780096966464788c96a078000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7a7c7477787a797d78007a7c7477787a797d780000"));
        presetPayloads.put(0x05, decodeHex("050000005a8ca0a0968c786478005a8ca0a0968c786478000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c747b7a7b797a78757800747b7a7b797a7875780000"));
        presetPayloads.put(0x06, decodeHex("060000008c5a6e828c8c825a78008c5a6e828c8c825a78000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7c7378787a797b7378007c7378787a797b73780000"));
        presetPayloads.put(0x07, decodeHex("070000008c8296968c64504678008c8296968c64504678000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7a777b797b76767378007a777b797b767673780000"));
        presetPayloads.put(0x08, decodeHex("08000000968c648c828c96967800968c648c828c969678000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7a7b737d777a7a7b78007a7b737d777a7a7b780000"));
        presetPayloads.put(0x09, decodeHex("0900000064646e7878786464780064646e787878646478000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c767777787879767678007677777878797676780000"));
        presetPayloads.put(0x0a, decodeHex("0a0000008c966e6e8c6e8c9678008c966e6e8c6e8c9678000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c797c76767d747b7b7800797c76767d747b7b780000"));
        presetPayloads.put(0x0b, decodeHex("0b0000008c8c6464788c96a078008c8c6464788c96a078000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c797b7577787a797d7800797b7577787a797d780000"));
        presetPayloads.put(0x0c, decodeHex("0c00000078786464647896aa780078786464647896aa78000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7879767776787a7e78007879767776787a7e780000"));
        presetPayloads.put(0x0d, decodeHex("0d0000006e8ca09678648c8278006e8ca09678648c8278000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c767a7b7a78747c787800767a7b7a78747c78780000"));
        presetPayloads.put(0x0e, decodeHex("0e0000007896968ca0aa96a078007896968ca0aa96a078000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c777b7a787b7c787d7800777b7a787b7c787d780000"));
        presetPayloads.put(0x0f, decodeHex("0f0000006e829696826e645a78006e829696826e645a78000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c77797a7a79777775780077797a7a79777775780000"));
        presetPayloads.put(0x10, decodeHex("10000000b48c64648c9696a07800b48c64648c9696a078000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7e7976757b7a797d78007e7976757b7a797d780000"));
        presetPayloads.put(0x11, decodeHex("11000000968c6e6e8296a0aa7800968c6e6e8296a0aa78000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7a7a7677797a7a7e78007a7a7677797a7a7e780000"));
        presetPayloads.put(0x12, decodeHex("12000000a0968278645a50507800a0968278645a505078000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c7b7a78797676757478007b7a787976767574780000"));
        presetPayloads.put(0x13, decodeHex("130000005a64828c8c82785a78005a64828c8c82785a78000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c76767a797a787974780076767a797a787974780000"));
        presetPayloads.put(0x14, decodeHex("140000006464646e828c8ca078006464646e828c8ca078000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c76777777797a787d780076777777797a787d780000"));
        presetPayloads.put(0x15, decodeHex("15000000787878645a50503c7800787878645a50503c78000002009fa18a998a8077643c3c9fa18a998a8077643c3c69d64e6a009fa18a998a8077643c3c9fa18a998a8077643c3c787879767775777178007878797677757771780000"));

        return presetPayloads;
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
