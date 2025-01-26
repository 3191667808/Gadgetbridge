package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoBackgroundVolume;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoEqualizerProfile;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmRegion;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoLanguage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoMicrophoneSensitivity;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;

public class ConfigMessage {
    static final ByteUtils.CardoField selectedLanguage = new ByteUtils.CardoField("selectedLanguage", 4, value -> CardoLanguage.values()[(int) value]);
    static final ByteUtils.CardoField equalizerProfile = new ByteUtils.CardoField("equalizerProfile", 6, value -> CardoEqualizerProfile.values()[(int) value]);
    private static final Logger LOG = LoggerFactory.getLogger(ConfigMessage.class);

    public static List<GBDeviceEvent> decodeMessage(byte[] payload, CardoMap<ByteUtils.CardoField, Object> deviceStatus) {
        final List<GBDeviceEvent> deviceEvents = new ArrayList<>();

        int index = 0;
        while (index < payload.length) {
            byte infoTypeByte = payload[index];
            InfoType infoType = InfoType.fromByte(infoTypeByte);

            int infoPayloadLength = infoType.getPayloadLength();
            if (index + 1 + infoPayloadLength > payload.length) {
                throw new IllegalArgumentException("Incomplete payload for infoType: " + infoType);
            }
            byte[] infoPayload = Arrays.copyOfRange(payload, index + 1, index + 1 + infoPayloadLength);
            Object decodedData = infoType.decode(infoPayload);

            if (decodedData instanceof GBDeviceEvent)
                deviceEvents.add((GBDeviceEvent) decodedData);
            else if (decodedData instanceof CardoMap)
                deviceStatus.putAll((CardoMap) decodedData);
            LOG.debug("{}", decodedData);

            index += 1 + infoPayloadLength;
        }
        return deviceEvents;
    }

    public enum InfoType {
        CONFIG(0x00, 3, Arrays.asList(
                selectedLanguage,
                new ByteUtils.CardoField("isASREnable", 1),
                new ByteUtils.CardoField("isVoicePromptsEnabled", 1),
                new ByteUtils.CardoField("fmRegion", 1, value -> CardoFmRegion.values()[(int) value]),
                new ByteUtils.CardoField("isFMRDSEnabled", 1),

                new ByteUtils.CardoField("isHFPMixingEnabled", 1),
                new ByteUtils.CardoField("microphoneSensitivity", 2, value -> CardoMicrophoneSensitivity.values()[(int) value]),
                new ByteUtils.CardoField("agcSensitivity", 4),
                new ByteUtils.CardoField("isNoiseGateEnabled", 1),

                new ByteUtils.CardoField("isEcoModeEnabled", 1),
                new ByteUtils.CardoField("isDMCModeEnabled", 1),
                equalizerProfile
        )),
        HARDWARE(0x01, 1, Arrays.asList(
                new ByteUtils.CardoField("txPowerProfile", 4),
                new ByteUtils.CardoField("lrSpeakers", 1)
        )),
        VOLUMES(0x02, 6, Arrays.asList(
                new ByteUtils.CardoField("volumeID", 8),
                new ByteUtils.CardoField("standByVolume", 4),
                new ByteUtils.CardoField("groupingVolume", 4),
                new ByteUtils.CardoField("ag1Volume", 4),
                new ByteUtils.CardoField("ag2Volume", 4),
                new ByteUtils.CardoField("fmVolume", 4),
                new ByteUtils.CardoField("a2dp1Volume", 4),
                new ByteUtils.CardoField("a2dp2Volume", 4),
                new ByteUtils.CardoField("intercomBackgroundMusicVolume", 4, value -> CardoBackgroundVolume.fromCustomIndex((int) value)),
                new ByteUtils.CardoField("mixActiveSpeakerVolume", 8)
        )),
        FIRMWARE(0x03, 6, Arrays.asList(
                new ByteUtils.CardoField("headsetType", 16),
                new ByteUtils.CardoField("major", 16),
                new ByteUtils.CardoField("minor", 16)
        )) {
            @Override
            public Object decode(byte[] payload) {
                final CardoMap<ByteUtils.CardoField, Object> rawResult = ByteUtils.parseStructure(payload, this.getFields());
                String fwVersion = String.format("%s.%s",
                        rawResult.getValueByName("major"),
                        rawResult.getValueByName("minor"));
                GBDeviceEventVersionInfo gbDeviceEventVersionInfo = new GBDeviceEventVersionInfo();
                gbDeviceEventVersionInfo.fwVersion = "v." + fwVersion;
                final CardoMap<ByteUtils.CardoField, Object> result = new CardoMap<>();
                result.put(new ByteUtils.CardoField("headsetType", 8), rawResult.getValueByName("headsetType").equals(0) ? null : rawResult.getValueByName("headsetType"));
                result.put(new ByteUtils.CardoField("firmwareVersion", 16), fwVersion);
                return result;
            }
        },
        SOFTWARE(0x04, 8, Arrays.asList(
                new ByteUtils.CardoField("device letter", 16),
                new ByteUtils.CardoField("version", 16),
                new ByteUtils.CardoField("subVersion", 16),
                new ByteUtils.CardoField("softwareRevision", 16)
        )),
        LANGUAGE_LIST(0x05, 2, Collections.singletonList(
                new ByteUtils.CardoField("languageList", 16, value -> CardoLanguage.fromBitMask((int) value))
        )),
        CONFIG_OTHER(0x06, 3, Arrays.asList(
                new ByteUtils.CardoField("isDMCAGCEnabled", 1),
                new ByteUtils.CardoField("isAdvancedMMIEnabled", 1),
                new ByteUtils.CardoField("isRedialASREnabled", 1),
                new ByteUtils.CardoField("isRadioONASREnabled", 1),
                new ByteUtils.CardoField("isAutoOnOffEnabled", 1)
        )),
        FM_STATION(0x80, 13, Arrays.asList(
                new ByteUtils.CardoField("stat", 8),
                new ByteUtils.CardoField("stat1", 16),
                new ByteUtils.CardoField("stat2", 16),
                new ByteUtils.CardoField("stat3", 16),
                new ByteUtils.CardoField("stat4", 16),
                new ByteUtils.CardoField("stat5", 16),
                new ByteUtils.CardoField("stat6", 16)
        )) {
            @Override
            public Object decode(byte[] payload) {
                final CardoMap<ByteUtils.CardoField, Object> rawResult = ByteUtils.parseStructure(payload, this.getFields());
                List<Object> frequencies = new ArrayList<>();
                for (int i = 1; i <= 6; i++)
                    frequencies.add(rawResult.getValueByName("stat" + i));

                return new CardoMap<ByteUtils.CardoField, Object>() {{
                    put(new ByteUtils.CardoField("stationList", 16 * 6), frequencies);
                }};
            }
        },
        SENSITIVITY(0x81, 4, Collections.singletonList(
                new ByteUtils.CardoField("sensitivity", 16)
        )),
        FEATURES(0x82, 2, Arrays.asList(
                new ByteUtils.CardoField("isAutomaticVolumeAvailable", 1),
                new ByteUtils.CardoField("isMusicSharingAvailable", 1),
                new ByteUtils.CardoField("isBluetoothICAvailable", 1),
                new ByteUtils.CardoField("isHFPMixingAvailable", 1),
                new ByteUtils.CardoField("isASRAvailable", 1),
                new ByteUtils.CardoField("isDMCAvailable", 1),
                new ByteUtils.CardoField("numberOfSupportedICChannels", 2),
                new ByteUtils.CardoField("isDynamicICAvailable", 1),
                new ByteUtils.CardoField("isOTAAvailable", 1),
                new ByteUtils.CardoField("isFMAvailable", 1),
                new ByteUtils.CardoField("isPrivateChatAvailable", 1),
                new ByteUtils.CardoField("isEcoModeAvailable", 1),
                new ByteUtils.CardoField("isMobileBridgeAvailable", 1),
                new ByteUtils.CardoField("isLRSpeakersAvailable", 1),
                new ByteUtils.CardoField("isIСDMCBridgeAvailable", 1)
        )),
        ACCESSORIES_ACTIVATE(0x83, 1, Collections.singletonList(
                new ByteUtils.CardoField("isAccessoriesActivated", 1)
        )),
        EQUALIZER_PROFILES(0x84, 4, Collections.singletonList(
                new ByteUtils.CardoField("equalizer Profiles", 32, value -> CardoEqualizerProfile.fromBitMask((int) value))
        )) {
            @Override
            public Object decode(byte[] payload) {
                final CardoMap<ByteUtils.CardoField, Object> rawResult = ByteUtils.parseStructure(payload, this.getFields());
                return rawResult;
            }
        },
        FEATURES2(0x87, 3, Arrays.asList(
                new ByteUtils.CardoField("skip", 5),
                new ByteUtils.CardoField("isAutoOnOffAvailable", 1),
                new ByteUtils.CardoField("isCSLNXTVADLicence", 1),
                new ByteUtils.CardoField("isAdvancedMMIAvailable", 1)
        )),
        ;

        private final int typeId;
        private final int payloadLength;
        private final List<ByteUtils.CardoField> fields;

        InfoType(int typeId, int payloadLength, List<ByteUtils.CardoField> fields) {
            this.typeId = typeId;
            this.payloadLength = payloadLength;
            this.fields = fields;
        }

        public static InfoType fromByte(byte b) {
            for (InfoType type : values()) {
                if (type.getTypeId() == (b & 0xFF)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unhandled Type: " + b);
        }

        public List<ByteUtils.CardoField> getFields() {
            return fields;
        }

        public boolean containsFieldWithName(String name) {
            for (ByteUtils.CardoField field : getFields()) {
                if (field.getName().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        public List<String> getOtherFieldNames(String name) {
            final List<String> names = new ArrayList<>(fields.size());
            if (!containsFieldWithName(name))
                return null;

            for (ByteUtils.CardoField field : getFields()) {
                if (!field.getName().equals(name)) {
                    names.add(field.getName());
                }
            }
            return names;
        }

        public int getTypeId() {
            return typeId;
        }

        public int getPayloadLength() {
            return payloadLength;
        }

        public Object decode(byte[] payload) {
            return ByteUtils.parseStructure(payload, this.getFields());
        }

//        public abstract Object decode(byte[] payload);
    }

}

