package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages;

import org.apache.commons.lang3.EnumUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;

public class SubscribeMessage { //TODO: they do not match
    private static final Logger LOG = LoggerFactory.getLogger(SubscribeMessage.class);

    public static List<GBDeviceEvent> decodeMessage(byte[] payload, CardoMap<ByteUtils.CardoField, Object> deviceStatus) {
        final List<GBDeviceEvent> deviceEvents = new ArrayList<>();


//        LOG.debug("services: {} -> {}", Integer.toBinaryString(payload[0] & 0xff), Services.fromBitMask(payload[0] & 0xff));
//        LOG.debug("specific services: {} {} {} -> {}", ((payload[1] << 8) | payload[2]), (((payload[1] << 8) | payload[2]) & 0xffff), Integer.toBinaryString(((payload[1] << 8) | payload[2]) & 0xffff), SpecificServices.fromBitMask(((payload[1] << 8) | payload[2]) & 0xffff));
//        LOG.debug("specific services req: {}", EnumUtils.generateBitVector(SpecificServices.class, SpecificServices.knownValues()));

        deviceStatus.putAll(ByteUtils.parseStructure(payload, Arrays.asList(
                new ByteUtils.CardoField("services", 8, value -> Services.fromBitMask((int) value)),
                new ByteUtils.CardoField("specific services", 16, value -> {
                    LOG.debug("VALUE IS: {}", Integer.toBinaryString((int) value));
                    return SpecificServices.fromBitMask(((int) value & 0xffff));
                })
        )));

        return deviceEvents;
    }

    public enum Services {
        CAIP_SRVC_UPDATE,
        NA_1,
        NA_2,
        NA_3,
        CAIP_SRVC_DISCONNECT,
        UNK_5,
        CAIP_SRVC_BATTERY,
        CAIP_SRVC_STATE,
        ;

        public static EnumSet<Services> fromBitMask(final int code) {
            return EnumUtils.processBitVector(Services.class, code);
        }

        public static EnumSet<Services> knownValues() {
            EnumSet<Services> filteredSet = EnumSet.noneOf(Services.class);
            for (Services service : Services.values()) {
                if (!service.name().startsWith("NA_")) {
                    filteredSet.add(service);
                }
            }
            return filteredSet;
        }
    }


    public enum SpecificServices {
        NA_0,
        NA_1,
        NA_2,
        NA_3,
        NA_4,
        NA_5,
        NA_6,
        CAIP_SRVC_MUSIC_SHARING,
        CAIP_SRVC_IC_MODULE_STATE,
        CAIP_SRVC_DIRECT_IC_STATE,
        CAIP_SRVC_BRIDGE,
        CAIP_SRVC_UNICAST,
        CAIP_SRVC_DMC_GROUP_EVENT,
        CAIP_SRVC_DMC_TOPO,
        CAIP_SRVC_GROUP_NAME,
        CAIP_SRVC_GROUP_HEADER,
        ;

        public static EnumSet<SpecificServices> fromBitMask(final int code) {
            return EnumUtils.processBitVector(SpecificServices.class, code);
        }

        public static EnumSet<SpecificServices> knownValues() {
            EnumSet<SpecificServices> filteredSet = EnumSet.noneOf(SpecificServices.class);
            for (SpecificServices specificServices : SpecificServices.values()) {
                if (!specificServices.name().startsWith("NA_")) {
                    filteredSet.add(specificServices);
                }
            }
            return filteredSet;
        }
    }
}
