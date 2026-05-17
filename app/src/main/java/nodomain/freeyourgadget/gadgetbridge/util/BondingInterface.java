/*  Copyright (C) 2020-2025 Andreas Böhler, Taavi Eomäe, Thomas Kuehne

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
package nodomain.freeyourgadget.gadgetbridge.util;


import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;

public interface BondingInterface {
    /**
     * Called when pairing is complete
     **/
    void onBondingComplete(boolean success);

    /**
     * Should return the device that is currently being paired
     **/
    GBDeviceCandidate getCurrentTarget();

    /**
     * This forces bonding activities to encapsulate the removal
     * of all broadcast receivers on demand
     **/
    void unregisterBroadcastReceivers();

    @Nullable
    default String getMacAddress() {
        GBDeviceCandidate candidate = getCurrentTarget();
        if (candidate != null) {
            BluetoothDevice device = candidate.getDevice();
            if (device != null) {
                return device.getAddress();
            }
        }
        return null;
    }

    boolean getAttemptToConnect();

    /**
     * Whether this device requires an established GATT connection before bonding.
     * When true, CDM association will not trigger bonding directly — instead the device is
     * associated, then connected via GATT, and the device service is responsible for writing
     * any necessary pairing triggers and calling createBond() over that connection.
     * {@link BondingUtil#tryBondThenComplete} connects rather than bonding directly, and the
     * existing GATT connection is not interrupted when BOND_BONDED fires.
     */
    default boolean bondRequiresGattConnection() {
        return false;
    }

    /**
     * This forces bonding activities to handle the addition
     * of all broadcast receivers in the same place
     **/
    void registerBroadcastReceivers();

    /**
     * Just returns the Context
     */
    Context getContext();
}
