package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;


import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * Base interface for all specific Magene command messages that can be serialized.
 */
public interface SRAPMessage {
    byte getNodeAddress();
    FunctionCode getFunctionCode();
    ResourceType getResourceType();
    PageNumber getPageNumber();
    byte[] getPayload();
    byte[] toByteArray();
}


