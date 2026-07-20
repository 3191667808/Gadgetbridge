/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.ycbt.YcbtConstants;

public class YcbtGattInventoryTest {
    private static final UUID SERVICE_UUID = UUID.fromString("12345678-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("12345678-0001-1000-8000-00805f9b34fb");
    private static final UUID DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    @Test
    public void formatsOnlyGattMetadataAndConfiguredServiceResult() {
        final YcbtGattInventory.Characteristic characteristic = new YcbtGattInventory.Characteristic(
                CHARACTERISTIC_UUID,
                0x00000022,
                "read,indicate",
                1,
                Collections.singletonList(DESCRIPTOR_UUID)
        );
        final YcbtGattInventory.Service service = new YcbtGattInventory.Service(
                SERVICE_UUID,
                "primary(0)",
                1,
                Collections.singletonList(characteristic)
        );

        final List<String> events = YcbtGattInventory.format(
                new YcbtGattInventory.Inventory(1, Collections.singletonList(service), 2),
                YcbtConstants.SERVICE_UUID
        );

        assertEquals(Arrays.asList(
                "GATT service count=1",
                "GATT service[0] uuid=12345678-0000-1000-8000-00805f9b34fb type=primary(0) characteristicCount=1",
                "GATT characteristic[0.0] uuid=12345678-0001-1000-8000-00805f9b34fb properties=0x00000022 flags=[read,indicate] descriptorCount=1",
                "GATT descriptor[0.0.0] uuid=00002902-0000-1000-8000-00805f9b34fb",
                "GATT configured service be940000-7333-be46-b7ae-689e71722bd5 present=true characteristicCount=2"
        ), events);
    }

    @Test
    public void reportsPerDimensionTruncation() {
        final List<UUID> descriptors = repeatedUuids(
                DESCRIPTOR_UUID,
                YcbtGattInventory.MAX_DESCRIPTORS_PER_CHARACTERISTIC + 1
        );
        final YcbtGattInventory.Characteristic characteristic = new YcbtGattInventory.Characteristic(
                CHARACTERISTIC_UUID,
                0,
                "",
                descriptors.size(),
                descriptors
        );
        final YcbtGattInventory.Characteristic characteristicWithoutDescriptors =
                new YcbtGattInventory.Characteristic(
                        CHARACTERISTIC_UUID,
                        0,
                        "",
                        0,
                        Collections.emptyList()
                );
        final List<YcbtGattInventory.Characteristic> characteristics = repeatedCharacteristics(
                characteristicWithoutDescriptors,
                YcbtGattInventory.MAX_CHARACTERISTICS_PER_SERVICE + 1
        );
        final YcbtGattInventory.Service service = new YcbtGattInventory.Service(
                SERVICE_UUID,
                "primary(0)",
                characteristics.size(),
                characteristics
        );
        final List<YcbtGattInventory.Service> services = repeatedServices(
                new YcbtGattInventory.Service(SERVICE_UUID, "primary(0)", 0, Collections.emptyList()),
                YcbtGattInventory.MAX_SERVICES + 1
        );

        final List<String> descriptorEvents = YcbtGattInventory.format(
                new YcbtGattInventory.Inventory(1, Collections.singletonList(new YcbtGattInventory.Service(
                        SERVICE_UUID,
                        "primary(0)",
                        1,
                        Collections.singletonList(characteristic)
                )), null),
                YcbtConstants.SERVICE_UUID
        );
        final List<String> characteristicEvents = YcbtGattInventory.format(
                new YcbtGattInventory.Inventory(1, Collections.singletonList(service), null),
                YcbtConstants.SERVICE_UUID
        );
        final List<String> serviceEvents = YcbtGattInventory.format(
                new YcbtGattInventory.Inventory(services.size(), services, null),
                YcbtConstants.SERVICE_UUID
        );

        assertTrue(descriptorEvents.contains(
                "GATT inventory truncated: characteristic=" + CHARACTERISTIC_UUID
                        + " descriptors shown=" + YcbtGattInventory.MAX_DESCRIPTORS_PER_CHARACTERISTIC
                        + " total=" + descriptors.size()
        ));
        assertTrue(characteristicEvents.contains(
                "GATT inventory truncated: service=" + SERVICE_UUID
                        + " characteristics shown=" + YcbtGattInventory.MAX_CHARACTERISTICS_PER_SERVICE
                        + " total=" + characteristics.size()
        ));
        assertTrue(serviceEvents.contains(
                "GATT inventory truncated: services shown=" + YcbtGattInventory.MAX_SERVICES
                        + " total=" + services.size()
        ));
    }

    @Test
    public void capsEventsAndKeepsConfiguredServiceResultLast() {
        final List<UUID> descriptors = repeatedUuids(
                DESCRIPTOR_UUID,
                YcbtGattInventory.MAX_DESCRIPTORS_PER_CHARACTERISTIC
        );
        final YcbtGattInventory.Characteristic characteristic = new YcbtGattInventory.Characteristic(
                CHARACTERISTIC_UUID,
                0xff,
                "all",
                descriptors.size(),
                descriptors
        );
        final List<YcbtGattInventory.Characteristic> characteristics = repeatedCharacteristics(
                characteristic,
                YcbtGattInventory.MAX_CHARACTERISTICS_PER_SERVICE
        );
        final YcbtGattInventory.Service service = new YcbtGattInventory.Service(
                SERVICE_UUID,
                "primary(0)",
                characteristics.size(),
                characteristics
        );
        final List<YcbtGattInventory.Service> services = repeatedServices(
                service,
                YcbtGattInventory.MAX_SERVICES
        );

        final List<String> events = YcbtGattInventory.format(
                new YcbtGattInventory.Inventory(services.size(), services, null),
                YcbtConstants.SERVICE_UUID
        );

        assertEquals(YcbtGattInventory.MAX_EVENTS, events.size());
        assertEquals(
                "GATT inventory truncated: event limit=" + YcbtGattInventory.MAX_EVENTS,
                events.get(events.size() - 2)
        );
        assertEquals(
                "GATT configured service be940000-7333-be46-b7ae-689e71722bd5 present=false",
                events.get(events.size() - 1)
        );
    }

    private static List<UUID> repeatedUuids(final UUID uuid, final int count) {
        return new ArrayList<>(Collections.nCopies(count, uuid));
    }

    private static List<YcbtGattInventory.Characteristic> repeatedCharacteristics(
            final YcbtGattInventory.Characteristic characteristic,
            final int count) {
        return new ArrayList<>(Collections.nCopies(count, characteristic));
    }

    private static List<YcbtGattInventory.Service> repeatedServices(final YcbtGattInventory.Service service,
                                                                    final int count) {
        return new ArrayList<>(Collections.nCopies(count, service));
    }
}
