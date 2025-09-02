package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.BondSyncStateControlPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeAddressInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeBasicInfoReadPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeSerialInfoPacket;

/**
 * A factory class to parse a raw byte array from a BLE characteristic
 * into a specific, structured response object.
 */
public class SRAPPacketParser {
    private static final Logger LOG = LoggerFactory.getLogger(SRAPPacketParser.class);
    /**
     * Parses a raw byte array from a BLE characteristic.
     *
     * @param data The raw byte array received.
     * @return A specific response object (e.g., NodeBasicInfoPacket.Response) if the type is known, otherwise null.
     */
    public static Object parse(byte[] data) {
        SRAPPacket genericPacket = SRAPPacket.fromBytes(data);
        if (genericPacket == null) {
            return null;
        }

        // Dispatch based on function and page number to the correct response parser
        if (genericPacket.getFunctionCode() == FunctionCode.RESPONSE) {
            switch (genericPacket.getPageNumber()) {
                case NODE_BASIC_INFO:
                    try {
                        // The inner Response class handles parsing its own payload
                        return new NodeBasicInfoReadPacket.Response(genericPacket.getPayload());
                    } catch (IllegalArgumentException e) {
                        // Log error: payload was malformed for this specific type
                        LOG.error("Error parsing NODE_BASIC_INFO response: " + e.getMessage());
                        return null;
                    }
                case NODE_SERIAL_INFO:
                    try {
                        return new NodeSerialInfoPacket.Response(genericPacket.getPayload());
                    } catch (IllegalArgumentException e) {
                        LOG.error("Error parsing NODE_SERIAL_INFO response: " + e.getMessage());
                        return null;
                    }
                case NODE_ADDRESS_INFO:
                    try {
                        return new NodeAddressInfoPacket.Response(genericPacket.getPayload());
                    } catch (IllegalArgumentException e) {
                        LOG.error("Error parsing NODE_ADDRESS_INFO response: " + e.getMessage());
                        return null;
                    }
                case BOND_SYNC_STATE_CONTROL:
                    BondSyncStateControlPacket.BondControl control = BondSyncStateControlPacket.BondControl.fromValue(genericPacket.getPayload()[0]);
                    switch (control) {
                        case SET_ACTIVE_STATE:
                            return new BondSyncStateControlPacket.SetActiveState.Response(genericPacket.getPayload());
                        case SET_TIMESTAMP:
                            return new BondSyncStateControlPacket.SetTimestamp.Response(genericPacket.getPayload());
                        case SET_TIMEZONE:
                            return new BondSyncStateControlPacket.SetTimezone.Response(genericPacket.getPayload());
                        case SYNC_CONTROL:
                            BondSyncStateControlPacket.SyncStart.Response response = new BondSyncStateControlPacket.SyncStart.Response(genericPacket.getPayload());
                            if (response.getSyncCommand() == BondSyncStateControlPacket.SyncAction.SYNC_START.getValue() ) {
                                return new BondSyncStateControlPacket.SyncStart.Response(genericPacket.getPayload());
                            } else {
                                return new BondSyncStateControlPacket.SyncEnd.Response(genericPacket.getPayload());
                            }
                        case SET_ALTITUDE_CORRECTION:
                            return new BondSyncStateControlPacket.SetAltitudeCorrection.Response(genericPacket.getPayload());
                        case REQUEST_BOND:
                            return new BondSyncStateControlPacket.RequestBond.Response(genericPacket.getPayload());
                        case REQUEST_UNBOND:
                            return new BondSyncStateControlPacket.RequestUnbond.Response(genericPacket.getPayload());
                        case CANCEL_BOND:
                            return new BondSyncStateControlPacket.CancelBond.Response(genericPacket.getPayload());
                        default:
                            LOG.error("Unknown payload start for BOND_SYNC_STATE_CONTROL: " + control);
                            return null;

                    }

                    // Add more cases for other PageNumbers here
                    // case DEVICE_STATUS:
                    //     return new DeviceStatusPacket.Response(genericPacket.getPayload());
                default:
                    // This is a valid response, but we don't have a specific parser for it yet.
                    return null;
            }
        }
        // You could add handlers for NOTIFICATION function codes here as well

        return null; // Unhandled packet type
    }
}

