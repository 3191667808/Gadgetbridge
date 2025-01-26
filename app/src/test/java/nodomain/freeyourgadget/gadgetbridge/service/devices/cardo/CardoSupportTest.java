package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo;

import org.apache.commons.lang3.EnumUtils;
import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmRegion;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.SubscribeMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.ControlRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioToggleRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioTuneRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class CardoSupportTest extends TestBase {

    @Test
    public void testMessages() {
//        testCardoMessage(new byte[]{0x40, 0xf, 0x0, 0xc, 0x21, 0x11, 0x4, 0x0, 0x54, 0x0, 0x0, 0x0, 0x5, 0x0, 0x17, 0x1, 0x0},
//                "selectedLanguage - ENGLISH_US\n" +
//                        "isASREnable - true\n" +
//                        "isVoicePromptsEnabled - true\n" +
//                        "fmRegion - WORLDWIDE\n" +
//                        "isFMRDSEnabled - false\n" +
//                        "isHFPMixingEnabled - false\n" +
//                        "microphoneSensitivity - MEDIUM\n" + //fixed typo
//                        "agcSensitivity - 0\n" + //changed from OFF
//                        "isNoiseGateEnabled - true\n" +
//                        "isEcoModeEnabled - false\n" +
//                        "isDMCModeEnabled - false\n" +
//                        "equalizerProfile - JBL_BASS_BOOST\n" +
//                        "device letter - 84\n" +
//                        "version - 0\n" +
//                        "subVersion - 5\n" +
//                        "softwareRevision - 23\n" +
//                        "txPowerProfile - CE\n" +
//                        "lrSpeakers - STRAIGHT\n"
//        );
        testCardoMessage(new byte[]{0x40, 0xc, (byte) 0x81, 0x0, 0x32, 0x0, 0x0, (byte) 0x82, (byte) 0xfa, 0x62, (byte) 0x87, 0x1, 0x0, 0x0},
                "sensitivity - 50\n" +
                        "isAutomaticVolumeAvailable - true\n" +
                        "isMusicSharingAvailable - true\n" +
                        "isBluetoothICAvailable - true\n" +
                        "isHFPMixingAvailable - true\n" +
                        "isASRAvailable - true\n" +
                        "isDMCAvailable - false\n" +
                        "numberOfSupportedICChannels - 2\n" +
                        "isDynamicICAvailable - false\n" +
                        "isOTAAvailable - true\n" +
                        "isFMAvailable - true\n" +
                        "isPrivateChatAvailable - false\n" +
                        "isEcoModeAvailable - false\n" +
                        "isMobileBridgeAvailable - false\n" +
                        "isLRSpeakersAvailable - true\n" +
                        "isIСDMCBridgeAvailable - false\n" +
                        "isAutoOnOffAvailable - false\n" +
                        "isCSLNXTVADLicence - false\n" +
                        "isAdvancedMMIAvailable - true\n"
        );
        testCardoMessage(new byte[]{0x40, 0x12, 0x3, 0x0, 0x0, 0x0, 0x3, 0x0, 0x2, 0x2, 0x0, (byte) 0xa8, (byte) 0xaa, (byte) 0xb9, (byte) 0x98, 0x4, 0x6, 0x0, 0x0, 0x0},
                "headsetType - null\n" +
                        "firmwareVersion - 3.2\n" +
                        "volumeID - 0\n" +
                        "standByVolume - 10\n" +
                        "groupingVolume - 8\n" +
                        "ag1Volume - 10\n" +
                        "ag2Volume - 10\n" +
                        "fmVolume - 11\n" +
                        "a2dp1Volume - 9\n" +
                        "a2dp2Volume - 9\n" +
                        "intercomBackgroundMusicVolume - PERCENT_90\n" +
                        "mixActiveSpeakerVolume - 4\n" +
                        "isDMCAGCEnabled - false\n" +
                        "isAdvancedMMIEnabled - false\n" +
                        "isRedialASREnabled - false\n" +
                        "isRadioONASREnabled - false\n" +
                        "isAutoOnOffEnabled - false\n"
        );
        testCardoMessage(new byte[]{0x40, 0xa, 0x5, 0xf, 0x7f, (byte) 0x84, 0x0, 0xf, 0x0, 0xf, (byte) 0x83, (byte) 0x80},
                "languageList - [ENGLISH_US, ENGLISH_UK, SPANISH, FRENCH, DEUTSCH, JAPANESE, CHINESE, ITALIAN, RUSSIAN, HEBREW, PORTUGUESE]\n" +//fixed typo and reordered
                        "equalizer Profiles - [HIGH_VOLUME, BASS_BOOST, VOCAL, OFF, JBL_HIGH_VOLUME, JBL_BASS_BOOST, JBL_VOCAL, JBL_OFF]\n" +
                        "isAccessoriesActivated - true\n");

        testCardoMessage(new byte[]{0x40, 0xe, (byte) 0x80, 0x0, 0x22, 0x6a, 0x22, 0x7e, 0x23, 0xa, 0x23, 0x46, 0x23, 0x78, 0x23, (byte) 0xbe},
                "stationList - [8810, 8830, 8970, 9030, 9080, 9150]\n"); //removed dots, added trailing zeroes


        testCardoMessage(new byte[]{0x50, 0x1, 0x0, 0x6, 0x23, (byte) 0xbe, 0x0},
                "state - STAND_BY\n" +
                        "callState - IDLE\n" +
                        "callDirection - UNKNOWN\n" +
                        "fmState - IDLE\n" +
                        "currentSelectedIndex - 6\n" +
                        "currentStation - 9150\n" + //changed from 91.5
                        "dmcGroupState - READY\n"
        );
    }

    @Test
    public void testSubscribe() {

        System.out.println(0x3 << 8);
        System.out.println(0x3 << 8 | 0x80);
        System.out.println(0x3 << 8 | 0x80 & 0xffff);
        System.out.println((0x3 << 8 | 0x80) & 0xffff);

        testCardoMessage(new byte[]{0x22, (byte) 0xd1, 0x3, (byte) 0x80},
                "services - [CAIP_SRVC_UPDATE, CAIP_SRVC_DISCONNECT, CAIP_SRVC_BATTERY, CAIP_SRVC_STATE]\n" +
                        "specific services - [CAIP_SRVC_MUSIC_SHARING, CAIP_SRVC_IC_MODULE_STATE, CAIP_SRVC_DIRECT_IC_STATE, CAIP_SRVC_BRIDGE, CAIP_SRVC_UNICAST, CAIP_SRVC_DMC_GROUP_EVENT, CAIP_SRVC_DMC_TOPO, CAIP_SRVC_GROUP_NAME, CAIP_SRVC_GROUP_HEADER]\n"
        );

        CardoRequest req = new CardoRequest(CardoMessage.SUBSCRIBE, new byte[]{
                (byte) EnumUtils.generateBitVector(SubscribeMessage.Services.class, SubscribeMessage.Services.knownValues()),
                (byte) 0xff, (byte) 0x80});

        System.out.println(GB.hexdump(req.getBtMessage()));

        CardoRequest req2 = new CardoRequest(CardoMessage.SUBSCRIBE);
        req2.appendPayload(EnumUtils.generateBitVector(SubscribeMessage.Services.class, SubscribeMessage.Services.knownValues()), 1);
        req2.appendPayload(EnumUtils.generateBitVector(SubscribeMessage.SpecificServices.class, SubscribeMessage.SpecificServices.knownValues()), 2);

        System.out.println(GB.hexdump(req2.getBtMessage()));

    }

    @Test
    public void testStringMessage() {
        testCardoMessage(new byte[]{0x42, 0xc, 0x55, 0x43, 0x53, 0x20, 0x4c, 0x53, 0x32, 0x20, 0x44, 0x44, 0x44, 0x44},
                "friendlyName - UCS LS2 DDDD\n");

        testCardoMessage(new byte[]{0x43, 0xa, 0x55, 0x43, 0x34, 0x30, 0x31, 0x33, 0x41, 0x31, 0x33, 0x39},
                "serial - UC4013A139\n");
    }

    @Test
    public void testControlMessage() {
        ControlRequest controlRequest = new ControlRequest(ControlRequest.ControlSubset.FM, new byte[]{0x00, 0x00});
        System.out.println(GB.hexdump(controlRequest.getBtMessage()));
        FmRadioToggleRequest fm = new FmRadioToggleRequest(false);
        System.out.println(GB.hexdump(fm.getBtMessage()));

        FmRadioTuneRequest tuneRequest = new FmRadioTuneRequest(FmRadioTuneRequest.TUNE_FREQ, 9150, CardoFmRegion.WORLDWIDE);
        System.out.println(GB.hexdump(tuneRequest.getBtMessage()));


    }

    private void testCardoMessage(byte[] message, String expectedOutput) {
        CardoResponse response = new CardoResponse(message);
        Assert.assertEquals(expectedOutput, deviceStatusToString(response.getDeviceStatus()));
    }

    private String deviceStatusToString(CardoMap<ByteUtils.CardoField, Object> deviceStatus) {
        StringBuilder stringBuilder = new StringBuilder();
        deviceStatus.forEach(
                (field, value) -> {
                    if (!field.getName().startsWith("skip")) {
                        stringBuilder.append(field.getName()).append(" - ").append(value).append(System.lineSeparator());
                    }
                }
        );

        return stringBuilder.toString();
    }

}
