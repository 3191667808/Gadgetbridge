/*  Copyright (C) 2015-2024 Carsten Pfeiffer

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
package nodomain.freeyourgadget.gadgetbridge.service.btle.actions;

import android.bluetooth.BluetoothGatt;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;

/**
 * A special action that checks for an abort-condition, and if met, the currently
 * executing transaction will be aborted by returning false.
 */
public abstract class AbortTransactionAction extends PlainAction {
    private static final Logger LOG = LoggerFactory.getLogger(AbortTransactionAction.class);

    @Override
    public int run(@NonNull BluetoothGatt gatt, @NonNull AbstractBTLEDeviceSupport deviceSupport, int deviceIdx) {
        if (shouldAbort()) {
            LOG.info("Aborting transaction because abort criteria met.");
            return Integer.MIN_VALUE;
        }
        return 1;
    }

    protected abstract boolean shouldAbort();

    @Override
    public String toString() {
        return getCreationTime() + " " + getClass().getSimpleName() + " abort=" + shouldAbort();
    }
}
