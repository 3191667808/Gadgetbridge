/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.fitbit.FitbitConstants;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattDescriptor;

final class FitbitPhoneLocalGatt {
    static final byte[] CCCD_DISABLED_RESPONSE = new byte[]{0x00, 0x00};

    private static final byte[] ZERO_RESPONSE = new byte[]{0x00};
    private static final byte[] PHONE_GATTLINK_STATUS_1_RESPONSE = new byte[18];

    private BluetoothGattCharacteristic phoneGattlinkStatus2Characteristic;

    BluetoothGattService createPhoneLocationService() {
        final BluetoothGattService service = new BluetoothGattService(
                FitbitConstants.PHONE_LOCATION_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_LOCATION_STATUS_1_CHARACTERISTIC));
        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_LOCATION_STATUS_2_CHARACTERISTIC));
        service.addCharacteristic(new BluetoothGattCharacteristic(
                FitbitConstants.PHONE_LOCATION_WRITE_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        ));
        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_LOCATION_STATUS_4_CHARACTERISTIC));

        return service;
    }

    BluetoothGattService createPhoneGattlinkService() {
        final BluetoothGattService service = new BluetoothGattService(
                FitbitConstants.PHONE_GATTLINK_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_GATTLINK_STATUS_1_CHARACTERISTIC));
        phoneGattlinkStatus2Characteristic = notifiableReadCharacteristic(FitbitConstants.PHONE_GATTLINK_STATUS_2_CHARACTERISTIC);
        service.addCharacteristic(phoneGattlinkStatus2Characteristic);
        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_GATTLINK_STATUS_3_CHARACTERISTIC));

        return service;
    }

    BluetoothGattCharacteristic getPhoneGattlinkStatus2Characteristic() {
        return phoneGattlinkStatus2Characteristic;
    }

    byte[] readCharacteristic(final UUID uuid) {
        if (FitbitConstants.PHONE_GATTLINK_STATUS_1_CHARACTERISTIC.equals(uuid)) {
            return PHONE_GATTLINK_STATUS_1_RESPONSE;
        }
        if (FitbitConstants.PHONE_GATTLINK_STATUS_2_CHARACTERISTIC.equals(uuid)) {
            return getPhoneGattlinkStatus2Value();
        }
        if (FitbitConstants.PHONE_GATTLINK_STATUS_3_CHARACTERISTIC.equals(uuid)
                || FitbitConstants.PHONE_LOCATION_STATUS_1_CHARACTERISTIC.equals(uuid)
                || FitbitConstants.PHONE_LOCATION_STATUS_2_CHARACTERISTIC.equals(uuid)
                || FitbitConstants.PHONE_LOCATION_STATUS_4_CHARACTERISTIC.equals(uuid)) {
            return ZERO_RESPONSE;
        }
        return null;
    }

    boolean isPhoneGattlinkDescriptor(final UUID characteristicUuid) {
        return FitbitConstants.PHONE_GATTLINK_STATUS_1_CHARACTERISTIC.equals(characteristicUuid)
                || FitbitConstants.PHONE_GATTLINK_STATUS_2_CHARACTERISTIC.equals(characteristicUuid)
                || FitbitConstants.PHONE_GATTLINK_STATUS_3_CHARACTERISTIC.equals(characteristicUuid);
    }

    private BluetoothGattCharacteristic notifiableReadCharacteristic(final UUID uuid) {
        final BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        characteristic.addDescriptor(new BluetoothGattDescriptor(
                GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        ));
        return characteristic;
    }

    byte[] getPhoneGattlinkStatus2Value() {
        return ZERO_RESPONSE;
    }
}
