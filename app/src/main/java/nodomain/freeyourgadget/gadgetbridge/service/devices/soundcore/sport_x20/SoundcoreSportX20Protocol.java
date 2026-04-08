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

        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING:
            case DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL:
                return encodeAncAudioMode();

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT:
                return encodeControlFunctionMessage(
                    TapAction.SINGLE_TAP,
                    false,
                    TapFunction.fromPreferenceValue(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT, "PLAYPAUSE")
                    )
                );
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT:
                return encodeControlFunctionMessage(
                    TapAction.SINGLE_TAP,
                    true,
                    TapFunction.fromPreferenceValue(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT, "PLAYPAUSE")
                    )
                );

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT:
                return encodeControlFunctionMessage(
                    TapAction.DOUBLE_TAP,
                    false,
                    TapFunction.fromPreferenceValue(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT, "MEDIA_PREV")
                    )
                );
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT:
                return encodeControlFunctionMessage(
                    TapAction.DOUBLE_TAP,
                    true,
                    TapFunction.fromPreferenceValue(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT, "MEDIA_NEXT")
                    )
                );

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT:
                return encodeControlFunctionMessage(
                    TapAction.LONG_PRESS,
                    false,
                    TapFunction.fromPreferenceValue(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT, "AMBIENT_SOUND_CONTROL")
                    )
                );
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT:
                return encodeControlFunctionMessage(
                    TapAction.LONG_PRESS,
                    true,
                    TapFunction.fromPreferenceValue(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT, "AMBIENT_SOUND_CONTROL")
                    )
                );

            default:
                return super.encodeSendConfiguration(config);
        }
    }

    private byte[] encodeControlFunctionMessage(final TapAction action, final boolean right, final TapFunction function) {
        if (function == null) {
            return null;
        }

        final byte encodedFunction = (byte) (action.getFunctionPrefix() + function.getCode());
        final byte[] payload = new byte[]{encodeBoolean(right), action.getCode(), encodedFunction};
        return new SoundcorePacket((short) 0x810d, payload).encode();
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
        }

        int ancStrength = 0;
        final byte strength = (byte) (payload[1] & 0x30);
        if (strength == 0x20) {
            ancStrength = 1;
        } else if (strength == 0x30) {
            ancStrength = 2;
        }

        final boolean adaptiveAnc = (payload[3] == 0x01);
        final boolean windNoiseReduction = (payload[4] == 0x01);

        editor.putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, ambientSoundMode);
        editor.putInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, ancStrength);
        editor.putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING, adaptiveAnc);
        editor.putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, windNoiseReduction);
        editor.apply();
    }

    private byte[] encodeAncAudioMode() {
        final Prefs prefs = getDevicePrefs();

        final byte ambientSoundMode;
        switch (prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, "off")) {
            case "noise_cancelling":
                ambientSoundMode = 0x00;
                break;
            case "ambient_sound":
                ambientSoundMode = 0x01;
                break;
            case "off":
                ambientSoundMode = 0x02;
                break;
            default:
                return null;
        }

        byte ancStrength = 0x10;
        switch (prefs.getInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, 0)) {
            case 0:
                ancStrength = 0x10;
                break;
            case 1:
                ancStrength = 0x20;
                break;
            case 2:
                ancStrength = 0x30;
                break;
        }

        final boolean adaptive = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING, true);
        final boolean windReduction = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, false);
        final byte adaptiveAnc = encodeBoolean(adaptive);
        final byte windNoiseReduction = encodeBoolean(windReduction);

        if (adaptive) {
            ancStrength = 0x30;
            if (windReduction) {
                // RFCOMM pattern on Sport X20: adaptive+wind toggles 0x30 -> 0x32.
                ancStrength = 0x32;
            }
        }

        final byte[] payload = new byte[]{ambientSoundMode, ancStrength, 0x00, adaptiveAnc, windNoiseReduction};
        return new SoundcorePacket((short) 0x8106, payload).encode();
    }
}
