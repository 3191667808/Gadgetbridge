package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sport_x20;

import android.content.SharedPreferences;

import java.nio.ByteBuffer;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcorePacket;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.liberty.SoundcoreLibertyProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreSportX20Protocol extends SoundcoreLibertyProtocol {
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
}
