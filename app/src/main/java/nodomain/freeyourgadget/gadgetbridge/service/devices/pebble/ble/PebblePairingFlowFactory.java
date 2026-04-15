/*  Copyright (C) 2024 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.pebble.ble;

import android.bluetooth.BluetoothGattService;
import android.content.Context;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.devices.pebble.PebbleHardware;

/**
 * Factory for creating the appropriate Pebble pairing flow based on device hardware and characteristics.
 * <p>
 * Decision order:
 * 1. BASALT/CHALK (from model): V2 without trigger — per libpebble3, these don't write the trigger
 * 2. CONNECTIVITY_CHARACTERISTIC present: V2 with trigger (BLE-only devices)
 * 3. CONNECTION_PARAMETERS_CHARACTERISTIC present: V1
 * 4. Neither found: V1 (fallback, should not happen in practice)
 */
class PebblePairingFlowFactory {
    private static final Logger LOG = LoggerFactory.getLogger(PebblePairingFlowFactory.class);

    static PebblePairingFlow createPairingFlow(
            Context context,
            @Nullable String deviceModel,
            BluetoothGattService pairingService,
            boolean clientOnly,
            PairingCallback callback) {

        // Dual-mode watches (BASALT/CHALK) don't write the pairing trigger — per libpebble3.
        if (deviceModel != null && !deviceModel.isEmpty()) {
            PebbleHardware.HardwareRevision hw = PebbleHardware.getByModelString(deviceModel);
            if (hw != null) {
                PebbleHardware.Platform platform = hw.getPlatform();
                if (platform == PebbleHardware.Platform.BASALT || platform == PebbleHardware.Platform.CHALK) {
                    LOG.info("Using PebblePairingFlowV2 without trigger for dual-mode device: model='{}'", deviceModel);
                    return new PebblePairingFlowV2(context, clientOnly, false, callback);
                }
            }
        }

        if (pairingService.getCharacteristic(PebbleGATTConstants.CONNECTIVITY_CHARACTERISTIC) != null) {
            LOG.info("Using PebblePairingFlowV2 (CONNECTIVITY_CHARACTERISTIC present)");
            return new PebblePairingFlowV2(context, clientOnly, true, callback);
        }

        if (pairingService.getCharacteristic(PebbleGATTConstants.CONNECTION_PARAMETERS_CHARACTERISTIC) != null) {
            LOG.info("Using PebblePairingFlowV1 (CONNECTION_PARAMETERS_CHARACTERISTIC present)");
            return new PebblePairingFlowV1(clientOnly, callback);
        }

        LOG.warn("No pairing characteristics found for model='{}', defaulting to V1", deviceModel);
        return new PebblePairingFlowV1(clientOnly, callback);
    }

    private PebblePairingFlowFactory() {
        // Utility class
    }
}

