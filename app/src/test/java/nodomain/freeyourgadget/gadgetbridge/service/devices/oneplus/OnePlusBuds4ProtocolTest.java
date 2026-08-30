package nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.devices.oneplus.OnePlusBuds4Preferences;
import nodomain.freeyourgadget.gadgetbridge.devices.oneplus.OnePlusEqPreset;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4AncConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4AncMode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4AncTouchCycleMode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4Feature;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4SubscriptionType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigValue;

public class OnePlusBuds4ProtocolTest {
    private OnePlusBuds4Protocol createProtocol() {
        return new OnePlusBuds4Protocol(null);
    }

    private static byte[] hex(String hex) {
        final int len = hex.length();
        final byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static void assertFrameEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            Assert.fail("Expected: " + bytesToHex(expected) + " Actual: " + bytesToHex(actual)
                    + " (expected.length=" + expected.length + " actual.length=" + actual.length + ")");
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    @Test
    public void testBatteryReq() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeBatteryReq();
        assertFrameEquals(hex("AA0700000001000000"), frame);
    }

    @Test
    public void testSubscriptionQuery() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeSubscriptionQuery();
        assertFrameEquals(hex("AA0700000002000000"), frame);
    }

    @Test
    public void testSubscriptionSet() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final EnumSet<OnePlusBuds4SubscriptionType> subs = EnumSet.of(
                OnePlusBuds4SubscriptionType.ONEPLUS_SETTINGS_1,
                OnePlusBuds4SubscriptionType.ONEPLUS_SETTINGS_2,
                OnePlusBuds4SubscriptionType.ONEPLUS_SETTINGS_3,
                OnePlusBuds4SubscriptionType.BATTERY,
                OnePlusBuds4SubscriptionType.STATUS,
                OnePlusBuds4SubscriptionType.ANC_SELECTOR,
                OnePlusBuds4SubscriptionType.UNKNOWN_04,
                OnePlusBuds4SubscriptionType.WEAR_DETECTION,
                OnePlusBuds4SubscriptionType.UNKNOWN_08,
                OnePlusBuds4SubscriptionType.EQUALIZER
        );
        final byte[] frame = protocol.encodeSubscriptionSet(subs);
        assertFrameEquals(hex("AA1200000502000B00090102030405080BF1F2F3"), frame);
    }

    @Test
    public void testSettingsQuery() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeSettingsQuery();
        assertFrameEquals(hex("AA0700002F01000000"), frame);
    }

    @Test
    public void testFindDeviceStart() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFindDevice(true);
        assertFrameEquals(hex("AA080000000400010001"), frame);
    }

    @Test
    public void testFindDeviceStop() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFindDevice(false);
        assertFrameEquals(hex("AA080000000400010000"), frame);
    }

    @Test
    public void testTouchConfigReq() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigReq();
        assertFrameEquals(hex("AA0A00000801000300020301"), frame);
    }

    @Test
    public void testTouchConfigSetLeftDoubleTapPlayPause() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.PLAY_PAUSE);
        assertFrameEquals(hex("AA0C000001040005000101010201"), frame);
    }

    @Test
    public void testTouchConfigSetLeftSingleTapOff() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.SINGLE_TAP,
                OnePlusBuds4TouchConfigValue.OFF);
        assertFrameEquals(hex("AA0C000001040005000101010100"), frame);
    }

    @Test
    public void testTouchConfigSetLeftSlideVolumeControl() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.SLIDE,
                OnePlusBuds4TouchConfigValue.VOLUME_CONTROL);
        assertFrameEquals(hex("AA0C000001040005000101010507"), frame);
    }

    @Test
    public void testTouchConfigSetLeftSlideSwitchTrack() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.SLIDE,
                OnePlusBuds4TouchConfigValue.SWITCH_TRACK);
        assertFrameEquals(hex("AA0C00000104000500010101050A"), frame);
    }

    @Test
    public void testTouchConfigSetRightSingleTapPrevious() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.RIGHT,
                OnePlusBuds4TouchConfigType.SINGLE_TAP,
                OnePlusBuds4TouchConfigValue.PREVIOUS);
        assertFrameEquals(hex("AA0C000001040005000102010105"), frame);
    }

    @Test
    public void testTouchConfigSetRightDoubleTapNext() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.RIGHT,
                OnePlusBuds4TouchConfigType.DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.NEXT);
        assertFrameEquals(hex("AA0C000001040005000102010206"), frame);
    }

    @Test
    public void testTouchConfigSetBothOnCallDoubleTapAnswerEndCall() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.BOTH,
                OnePlusBuds4TouchConfigType.ON_CALL_DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.ANSWER_END_CALL);
        assertFrameEquals(hex("AA0C00000104000500010406021D"), frame);
    }

    @Test
    public void testTouchConfigSetBothOnCallHoldDeclineCall() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.BOTH,
                OnePlusBuds4TouchConfigType.ON_CALL_HOLD,
                OnePlusBuds4TouchConfigValue.DECLINE_CALL);
        assertFrameEquals(hex("AA0C00000104000500010406061C"), frame);
    }

    @Test
    public void testTouchConfigSetLeftTripleTapVoiceAssistant() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.TRIPLE_TAP,
                OnePlusBuds4TouchConfigValue.VOICE_ASSISTANT);
        assertFrameEquals(hex("AA0C000001040005000101010303"), frame);
    }

    @Test
    public void testTouchConfigSetLeftTouchHoldGameMode() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.TOUCH_HOLD,
                OnePlusBuds4TouchConfigValue.GAME_MODE);
        assertFrameEquals(hex("AA0C000001040005000101010411"), frame);
    }

    @Test
    public void testFeatureSetSpatialAudioEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.SPATIAL_AUDIO, true);
        assertFrameEquals(hex("AA09000003040002001B01"), frame);
    }

    @Test
    public void testFeatureSetSpatialAudioDisable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.SPATIAL_AUDIO, false);
        assertFrameEquals(hex("AA09000003040002001B00"), frame);
    }

    @Test
    public void testFeatureSetHiResAudioEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.HI_RES_AUDIO, true);
        assertFrameEquals(hex("AA09000003040002001801"), frame);
    }

    @Test
    public void testFeatureSetHiResAudioDisable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.HI_RES_AUDIO, false);
        assertFrameEquals(hex("AA09000003040002001800"), frame);
    }

    @Test
    public void testFeatureSetGameModeEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.GAME_MODE, true);
        assertFrameEquals(hex("AA09000003040002000601"), frame);
    }

    @Test
    public void testFeatureSetGameModeDisable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.GAME_MODE, false);
        assertFrameEquals(hex("AA09000003040002000600"), frame);
    }

    @Test
    public void testFeatureSetDualConnectionDisable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.DUAL_CONNECTION, false);
        assertFrameEquals(hex("AA09000003040002001100"), frame);
    }

    @Test
    public void testFeatureSetDualConnectionEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.DUAL_CONNECTION, true);
        assertFrameEquals(hex("AA09000003040002001101"), frame);
    }

    @Test
    public void testFeatureSetAutoPlayPauseEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.AUTO_PLAY_PAUSE, true);
        assertFrameEquals(hex("AA09000003040002000401"), frame);
    }

    @Test
    public void testFeatureSetThreeDAudioDisable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.THREE_D_AUDIO, false);
        assertFrameEquals(hex("AA09000003040002000B00"), frame);
    }

    @Test
    public void testMiscConfigReqAllFeatures() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeMiscConfigReq(Arrays.asList(
                OnePlusBuds4Feature.WEAR_DETECTION,
                OnePlusBuds4Feature.AUTO_PLAY_PAUSE,
                OnePlusBuds4Feature.THREE_D_AUDIO,
                OnePlusBuds4Feature.DUAL_CONNECTION,
                OnePlusBuds4Feature.HI_RES_AUDIO,
                OnePlusBuds4Feature.GAME_MODE,
                OnePlusBuds4Feature.SPATIAL_AUDIO,
                OnePlusBuds4Feature.UNKNOWN_1D,
                OnePlusBuds4Feature.UNKNOWN_1C
        ));
        assertFrameEquals(hex("AA1100000D01000A000905040B1118061B1D1C"), frame);
    }

    @Test
    public void testMiscConfigReqEmpty() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeMiscConfigReq(null);
        assertFrameEquals(hex("AA0800000D0100010000"), frame);
    }

    @Test
    public void testAncModeSetTransparency() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.TRANSPARENCY);
        assertFrameEquals(hex("AA0A00000404000300010104"), frame);
    }

    @Test
    public void testAncModeSetOff() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.OFF);
        assertFrameEquals(hex("AA0A00000404000300010101"), frame);
    }

    @Test
    public void testAncModeSetModerate() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.MODERATE);
        assertFrameEquals(hex("AA0A00000404000300010120"), frame);
    }

    @Test
    public void testAncModeSetLow() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.LOW);
        assertFrameEquals(hex("AA0A00000404000300010140"), frame);
    }

    @Test
    public void testAncModeSetAuto() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.AUTO);
        assertFrameEquals(hex("AA0A00000404000300010180"), frame);
    }

    @Test
    public void testAncModeSetNcHigh() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.NC_HIGH);
        assertFrameEquals(hex("AA0A00000404000300010110"), frame);
    }

    @Test
    public void testAncModeSetAdaptive() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.ADAPTIVE);
        // Adaptive is value 0x00 with a trailing 0x08 flag byte
        assertFrameEquals(hex("AA0B0000040400040001010008"), frame);
    }

    @Test
    public void testAncTouchCycleModesHighTransparencyAdaptive() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final EnumSet<OnePlusBuds4AncTouchCycleMode> modes = EnumSet.of(
                OnePlusBuds4AncTouchCycleMode.NC,
                OnePlusBuds4AncTouchCycleMode.TRANSPARENCY,
                OnePlusBuds4AncTouchCycleMode.ADAPTIVE
        );
        final byte[] frame = protocol.encodeAncTouchCycleModesSet(modes);
        assertFrameEquals(hex("AA0B0000040400040002010608"), frame);
    }

    @Test
    public void testAncTouchCycleModesHighTransparency() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final EnumSet<OnePlusBuds4AncTouchCycleMode> modes = EnumSet.of(
                OnePlusBuds4AncTouchCycleMode.NC,
                OnePlusBuds4AncTouchCycleMode.TRANSPARENCY
        );
        final byte[] frame = protocol.encodeAncTouchCycleModesSet(modes);
        assertFrameEquals(hex("AA0A00000404000300020106"), frame);
    }

    @Test
    public void testAncConfigReqMode() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncConfigReq(OnePlusBuds4AncConfigType.MODE);
        assertFrameEquals(hex("AA0900000C010002000101"), frame);
    }

    @Test
    public void testAncConfigReqTouchCycleModes() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncConfigReq(OnePlusBuds4AncConfigType.TOUCH_CYCLE_MODES);
        assertFrameEquals(hex("AA0900000C010002000201"), frame);
    }

    // --- Cross-validation tests: verify payload bytes match btsnoop-captured device frames ---

    @Test
    public void testPayloadMatchesBtsnoopLeftDoubleTapPlayPause() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.PLAY_PAUSE);
        assertPayloadEquals(hex("0101010201"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopLeftSlideVolumeControl() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.SLIDE,
                OnePlusBuds4TouchConfigValue.VOLUME_CONTROL);
        assertPayloadEquals(hex("0101010507"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopLeftSlideSwitchTrack() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.LEFT,
                OnePlusBuds4TouchConfigType.SLIDE,
                OnePlusBuds4TouchConfigValue.SWITCH_TRACK);
        assertPayloadEquals(hex("010101050A"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopBothOnCallDoubleTapAnswer() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.BOTH,
                OnePlusBuds4TouchConfigType.ON_CALL_DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.ANSWER_END_CALL);
        assertPayloadEquals(hex("010406021D"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopBothOnCallHoldDecline() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.BOTH,
                OnePlusBuds4TouchConfigType.ON_CALL_HOLD,
                OnePlusBuds4TouchConfigValue.DECLINE_CALL);
        assertPayloadEquals(hex("010406061C"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopRightDoubleTapPlayPause() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.RIGHT,
                OnePlusBuds4TouchConfigType.DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.PLAY_PAUSE);
        assertPayloadEquals(hex("0102010201"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopRightDoubleTapVoiceAssistant() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.RIGHT,
                OnePlusBuds4TouchConfigType.DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.VOICE_ASSISTANT);
        assertPayloadEquals(hex("0102010203"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopRightDoubleTapGameMode() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigSet(
                OnePlusBuds4TouchConfigSide.RIGHT,
                OnePlusBuds4TouchConfigType.DOUBLE_TAP,
                OnePlusBuds4TouchConfigValue.GAME_MODE);
        assertPayloadEquals(hex("0102010211"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopFeatureSetSpatialAudioDisable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.SPATIAL_AUDIO, false);
        assertPayloadEquals(hex("1B00"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopFeatureSetHiResAudioEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.HI_RES_AUDIO, true);
        assertPayloadEquals(hex("1801"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopFeatureSetGameModeEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.GAME_MODE, true);
        assertPayloadEquals(hex("0601"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopFeatureSetDualConnectionDisable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.DUAL_CONNECTION, false);
        assertPayloadEquals(hex("1100"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopFeatureSetDualConnectionEnable() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFeatureSet(OnePlusBuds4Feature.DUAL_CONNECTION, true);
        assertPayloadEquals(hex("1101"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopTouchConfigReq() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeTouchConfigReq();
        assertPayloadEquals(hex("020301"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopMiscConfigReqAllFeatures() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeMiscConfigReq(Arrays.asList(
                OnePlusBuds4Feature.WEAR_DETECTION,
                OnePlusBuds4Feature.AUTO_PLAY_PAUSE,
                OnePlusBuds4Feature.THREE_D_AUDIO,
                OnePlusBuds4Feature.DUAL_CONNECTION,
                OnePlusBuds4Feature.HI_RES_AUDIO,
                OnePlusBuds4Feature.GAME_MODE,
                OnePlusBuds4Feature.SPATIAL_AUDIO,
                OnePlusBuds4Feature.UNKNOWN_1D,
                OnePlusBuds4Feature.UNKNOWN_1C
        ));
        assertPayloadEquals(hex("0905040B1118061B1D1C"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopAncModeSetTransparency() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.TRANSPARENCY);
        assertPayloadEquals(hex("010104"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopAncModeSetOff() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncModeSet(OnePlusBuds4AncMode.OFF);
        assertPayloadEquals(hex("010101"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopAncConfigReq() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncConfigReq(OnePlusBuds4AncConfigType.MODE);
        assertPayloadEquals(hex("0101"), frame);
    }

    @Test
    public void testAncTouchCycleModesSingleModeIs3Bytes() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncTouchCycleModesSet(EnumSet.of(OnePlusBuds4AncTouchCycleMode.NC));
        assertFrameEquals(hex("AA0A00000404000300020102"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopAncTouchCycleSingleMode() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncTouchCycleModesSet(EnumSet.of(OnePlusBuds4AncTouchCycleMode.NC));
        assertPayloadEquals(hex("020102"), frame);
    }

    @Test
    public void testPayloadMatchesBtsnoopAncTouchCycleAdaptive() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAncTouchCycleModesSet(EnumSet.of(
                OnePlusBuds4AncTouchCycleMode.NC,
                OnePlusBuds4AncTouchCycleMode.TRANSPARENCY,
                OnePlusBuds4AncTouchCycleMode.ADAPTIVE
        ));
        assertPayloadEquals(hex("02010608"), frame);
    }

    @Test
    public void testDecodeAncTouchCycleRetWithAdaptive() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C00000C810005000002010608"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        final Object value = event.preferences.get(OnePlusBuds4Preferences.ANC_TOUCH_CYCLE_MODES);
        Assert.assertNotNull(value);
        @SuppressWarnings("unchecked")
        final Set<String> modes = (Set<String>) value;
        Assert.assertTrue(modes.contains("nc"));
        Assert.assertTrue(modes.contains("transparency"));
        Assert.assertTrue(modes.contains("adaptive"));
    }

     @Test
     public void testDecodeAncSelectorPushOff() {
         final OnePlusBuds4Protocol protocol = createProtocol();
         final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C000004020005000301010800"));
         Assert.assertEquals(1, events.length);
         final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
         Assert.assertEquals("off", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
     }

     @Test
     public void testDecodeAncSelectorPushTransparency() {
         final OnePlusBuds4Protocol protocol = createProtocol();
         final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C000004020005000301010001"));
         Assert.assertEquals(1, events.length);
         final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
         Assert.assertEquals("transparency", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
     }

     @Test
     public void testDecodeAncSelectorPushAdaptive() {
         final OnePlusBuds4Protocol protocol = createProtocol();
         final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C000004020005000301010008"));
         Assert.assertEquals(1, events.length);
         final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
         Assert.assertEquals("adaptive", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
     }

     @Test
     public void testDecodeAncSelectorPushAuto() {
         final OnePlusBuds4Protocol protocol = createProtocol();
         final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C000004020005000301018000"));
         Assert.assertEquals(1, events.length);
         final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
         Assert.assertEquals("nc_auto", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
     }

    @Test
    public void testDecodeAncSelectorTouchCycleMaskIgnored() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C000004020005000302010408"));
        Assert.assertEquals(0, events.length);
    }

    @Test
    public void testDecodeAncSelectorLastLevelIgnored() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C000004020005000304014000"));
        Assert.assertEquals(0, events.length);
    }

    @Test
    public void testDecodeAncConfigRetAdaptive() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C00000C810005000001010008"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertEquals("adaptive", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
    }

    @Test
    public void testDecodeAncConfigRetOff() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C00000C810005000001010100"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertEquals("off", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
    }

    @Test
    public void testDecodeAncConfigRetOffSwapped() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C00000C810005000001010800"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        // With our translation 08 00 -> 01
        Assert.assertEquals("off", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
    }

    @Test
    public void testDecodeAncConfigRetTransparency() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C00000C810005000001010400"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertEquals("transparency", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
    }

    @Test
    public void testDecodeAncConfigRetTransparencySwapped() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0C00000C810005000001010001"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertEquals("transparency", event.preferences.get(OnePlusBuds4Preferences.ANC_SELECTOR));
    }

    @Test
    public void testEncodeAlarmVolumeSet() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAlarmVolumeSet(9);
        assertFrameEquals(hex("AA080000270400010009"), frame);
    }

    @Test
    public void testEncodeAlarmVolumeSetMin() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeAlarmVolumeSet(4);
        assertFrameEquals(hex("AA080000270400010004"), frame);
    }

    @Test
    public void testDecodeAlarmVolumeRet() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA09000027840002000009"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertEquals(9, event.preferences.get(OnePlusBuds4Preferences.ALARM_VOLUME));
    }

    @Test
    public void testDecodeAutoPlayPauseRet() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0B00000D8100040000010401"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertEquals(Boolean.TRUE, event.preferences.get(OnePlusBuds4Preferences.AUTO_PLAY_PAUSE));
    }

    @Test
    public void testEncodeFindDeviceStart() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFindDevice(true);
        assertFrameEquals(hex("AA080000000400010001"), frame);
    }

    @Test
    public void testEncodeFindDeviceStop() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeFindDevice(false);
        assertFrameEquals(hex("AA080000000400010000"), frame);
    }

    @Test
    public void testEncodeEqPresetSetBalanced() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeEqPresetSet(0);
        assertFrameEquals(hex("AA080000060400010000"), frame);
    }

    @Test
    public void testEncodeEqCustomSetRoundTrip() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final int[] gains = {-6, -6, -6, -6, -6, -6};
        final byte[] frame = protocol.encodeEqCustomSet(4, "-6 everywhere", gains);
        assertFrameEquals(hex("AA2C0000180400250002FA06040D2D362065766572797768657265063E00FAFA00FAE803FAA00FFA401FFA803EFA"), frame);
    }

    @Test
    public void testDecodeEqQueryRetCustom() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA2E00002281002700000101FA06040D2D362065766572797768657265063E00FAFA00FAE803FAA00FFA401FFA803EFA"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertEquals("custom:4", event.preferences.get(OnePlusBuds4Preferences.EQ_PRESET));
        final List<OnePlusEqPreset> presets = OnePlusEqPreset.parse(
                (String) event.preferences.get(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS));
        Assert.assertEquals(1, presets.size());
        final OnePlusEqPreset preset = presets.get(0);
        Assert.assertEquals("-6 everywhere", preset.name);
        Assert.assertEquals(4, preset.subtype);
        for (int i = 0; i < 6; i++) {
            Assert.assertEquals(-6, preset.gains[i]);
        }
    }

    @Test
    public void testDecodeEqQueryRetCustomNotSelected() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA2E00002281002700000100FA06040D2D362065766572797768657265063E00FAFA00FAE803FAA00FFA401FFA803EFA"));
        Assert.assertEquals(1, events.length);
        final GBDeviceEventUpdatePreferences event = (GBDeviceEventUpdatePreferences) events[0];
        Assert.assertNull(event.preferences.get(OnePlusBuds4Preferences.EQ_PRESET));
        final List<OnePlusEqPreset> presets = OnePlusEqPreset.parse(
                (String) event.preferences.get(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS));
        Assert.assertEquals(1, presets.size());
        Assert.assertEquals(4, presets.get(0).subtype);
    }

    @Test
    public void testFindBySubtype() {
        final List<OnePlusEqPreset> presets = OnePlusEqPreset.parse(
                "[{\"slot\":0,\"subtype\":4,\"name\":\"-6 everywhere\",\"gains\":[-6,-6,-6,-6,-6,-6]}," +
                "{\"slot\":1,\"subtype\":5,\"name\":\"Custom1\",\"gains\":[0,0,0,0,0,0]}," +
                "{\"slot\":2,\"subtype\":6,\"name\":\"Custom2\",\"gains\":[0,0,0,0,0,0]}]");
        Assert.assertNotNull(OnePlusEqPreset.findBySubtype(presets, 4));
        Assert.assertEquals("-6 everywhere", OnePlusEqPreset.findBySubtype(presets, 4).name);
        Assert.assertEquals("Custom2", OnePlusEqPreset.findBySubtype(presets, 6).name);
        Assert.assertNull(OnePlusEqPreset.findBySubtype(presets, 7));
    }

    @Test
    public void testDecodeEqCustomRetIgnoredWhenListUnknown() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA09000018840B02000004"));
        Assert.assertEquals(0, events.length);
    }

    @Test
    public void testDecodeEqSettingsRetIgnored() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA4D00002F81004600000200FA06040D2D362065766572797768657265063E00FAFA00FAE803FAA00FFA401FFA803EFA01FA060507437573746F6D31063E0000FA0000E80300A00F00401F00803E00"));
        Assert.assertEquals(0, events.length);
    }

    @Test
    public void testDecodeEqActiveStateRet() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0900000F810002000000"));
        Assert.assertEquals(1, events.length);
        Assert.assertEquals("builtin:0", ((GBDeviceEventUpdatePreferences) events[0]).preferences.get(OnePlusBuds4Preferences.EQ_PRESET));

        events = protocol.decodeResponse(hex("AA0900000F810002000001"));
        Assert.assertEquals("builtin:1", ((GBDeviceEventUpdatePreferences) events[0]).preferences.get(OnePlusBuds4Preferences.EQ_PRESET));

        events = protocol.decodeResponse(hex("AA0900000F810002000002"));
        Assert.assertEquals("builtin:2", ((GBDeviceEventUpdatePreferences) events[0]).preferences.get(OnePlusBuds4Preferences.EQ_PRESET));

        events = protocol.decodeResponse(hex("AA0900000F810002000004"));
        Assert.assertEquals(0, events.length);
    }

    @Test
    public void testDecodeEqQueryRetNoMarkerResetsToEmpty() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA08000022810001000000"));
        Assert.assertEquals(0, events.length);
    }

    @Test
    public void testDecodeEqSettingsRetNoMarkerIsIgnored() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0900002F810002000000"));
        Assert.assertEquals(0, events.length);
    }

    @Test
    public void testEncodeBassBoostSetNeutral() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        final byte[] frame = protocol.encodeBassBoostSet(0);
        assertFrameEquals(hex("AA0A00001B04000300FB0500"), frame);
    }

    @Test
    public void testEncodeBassBoostSetPositiveAndNegative() {
        OnePlusBuds4Protocol protocol = createProtocol();
        assertFrameEquals(hex("AA0A00001B04000300FB0505"), protocol.encodeBassBoostSet(5));
        protocol = createProtocol();
        assertFrameEquals(hex("AA0A00001B04000300FB05FB"), protocol.encodeBassBoostSet(-5));
    }

    @Test
    public void testEncodeBassBoostSetClampsOutOfRange() {
        OnePlusBuds4Protocol protocol = createProtocol();
        assertFrameEquals(hex("AA0A00001B04000300FB0505"), protocol.encodeBassBoostSet(42));
        protocol = createProtocol();
        assertFrameEquals(hex("AA0A00001B04000300FB05FB"), protocol.encodeBassBoostSet(-42));
    }

    @Test
    public void testDecodeBassBoostRet() {
        final OnePlusBuds4Protocol protocol = createProtocol();
        GBDeviceEvent[] events = protocol.decodeResponse(hex("AA0B0000248100040000FB0500"));
        Assert.assertEquals(1, events.length);
        Assert.assertEquals(Integer.valueOf(0),
                ((GBDeviceEventUpdatePreferences) events[0]).preferences.get(OnePlusBuds4Preferences.BASS_BOOST));

        events = protocol.decodeResponse(hex("AA0B0000248100040000FB0505"));
        Assert.assertEquals(Integer.valueOf(5),
                ((GBDeviceEventUpdatePreferences) events[0]).preferences.get(OnePlusBuds4Preferences.BASS_BOOST));

        events = protocol.decodeResponse(hex("AA0B0000248100040000FB05FD"));
        Assert.assertEquals(Integer.valueOf(-3),
                ((GBDeviceEventUpdatePreferences) events[0]).preferences.get(OnePlusBuds4Preferences.BASS_BOOST));
    }

    private static void assertPayloadEquals(byte[] expectedPayload, byte[] frame) {
        if (frame.length < 9 + expectedPayload.length) {
            Assert.fail("Frame too short: " + bytesToHex(frame));
        }
        byte[] actualPayload = Arrays.copyOfRange(frame, 9, 9 + expectedPayload.length);
        if (!Arrays.equals(expectedPayload, actualPayload)) {
            Assert.fail("Payload mismatch - Expected: " + bytesToHex(expectedPayload)
                    + " Actual: " + bytesToHex(actualPayload));
        }
    }
}
