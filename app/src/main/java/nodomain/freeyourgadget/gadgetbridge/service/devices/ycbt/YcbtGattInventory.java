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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class YcbtGattInventory {
    static final int MAX_SERVICES = 16;
    static final int MAX_CHARACTERISTICS_PER_SERVICE = 24;
    static final int MAX_DESCRIPTORS_PER_CHARACTERISTIC = 8;
    static final int MAX_EVENTS = 128;

    private YcbtGattInventory() {
    }

    static List<String> format(final Inventory inventory, final UUID configuredServiceUuid) {
        final EventCollector events = new EventCollector(MAX_EVENTS - 2);
        events.add("GATT service count=" + inventory.totalServiceCount);

        inventoryLoop:
        for (int serviceIndex = 0;
             serviceIndex < Math.min(inventory.services.size(), MAX_SERVICES);
             serviceIndex++) {
            final Service service = inventory.services.get(serviceIndex);
            if (!events.add(String.format(
                    Locale.ROOT,
                    "GATT service[%d] uuid=%s type=%s characteristicCount=%d",
                    serviceIndex,
                    service.uuid,
                    service.type,
                    service.totalCharacteristicCount
            ))) {
                break;
            }

            for (int characteristicIndex = 0;
                 characteristicIndex < Math.min(service.characteristics.size(), MAX_CHARACTERISTICS_PER_SERVICE);
                 characteristicIndex++) {
                final Characteristic characteristic = service.characteristics.get(characteristicIndex);
                if (!events.add(String.format(
                        Locale.ROOT,
                        "GATT characteristic[%d.%d] uuid=%s properties=0x%08x flags=[%s] descriptorCount=%d",
                        serviceIndex,
                        characteristicIndex,
                        characteristic.uuid,
                        characteristic.properties,
                        characteristic.propertyFlags,
                        characteristic.totalDescriptorCount
                ))) {
                    break inventoryLoop;
                }

                final int shownDescriptorCount = Math.min(
                        characteristic.descriptorUuids.size(),
                        MAX_DESCRIPTORS_PER_CHARACTERISTIC
                );
                for (int descriptorIndex = 0; descriptorIndex < shownDescriptorCount; descriptorIndex++) {
                    if (!events.add(String.format(
                            Locale.ROOT,
                            "GATT descriptor[%d.%d.%d] uuid=%s",
                            serviceIndex,
                            characteristicIndex,
                            descriptorIndex,
                            characteristic.descriptorUuids.get(descriptorIndex)
                    ))) {
                        break inventoryLoop;
                    }
                }
                if (characteristic.totalDescriptorCount > shownDescriptorCount
                        && !events.add(String.format(
                        Locale.ROOT,
                        "GATT inventory truncated: characteristic=%s descriptors shown=%d total=%d",
                        characteristic.uuid,
                        shownDescriptorCount,
                        characteristic.totalDescriptorCount
                ))) {
                    break inventoryLoop;
                }
            }

            final int shownCharacteristicCount = Math.min(
                    service.characteristics.size(),
                    MAX_CHARACTERISTICS_PER_SERVICE
            );
            if (service.totalCharacteristicCount > shownCharacteristicCount
                    && !events.add(String.format(
                    Locale.ROOT,
                    "GATT inventory truncated: service=%s characteristics shown=%d total=%d",
                    service.uuid,
                    shownCharacteristicCount,
                    service.totalCharacteristicCount
            ))) {
                break;
            }
        }

        final int shownServiceCount = Math.min(inventory.services.size(), MAX_SERVICES);
        if (!events.isTruncated() && inventory.totalServiceCount > shownServiceCount) {
            events.add(String.format(
                    Locale.ROOT,
                    "GATT inventory truncated: services shown=%d total=%d",
                    shownServiceCount,
                    inventory.totalServiceCount
            ));
        }
        if (events.isTruncated()) {
            events.addReserved("GATT inventory truncated: event limit=" + MAX_EVENTS);
        }

        if (inventory.configuredServiceCharacteristicCount == null) {
            events.addReserved("GATT configured service " + configuredServiceUuid + " present=false");
        } else {
            events.addReserved(String.format(
                    Locale.ROOT,
                    "GATT configured service %s present=true characteristicCount=%d",
                    configuredServiceUuid,
                    inventory.configuredServiceCharacteristicCount
            ));
        }
        return events.getEvents();
    }

    static final class Inventory {
        private final int totalServiceCount;
        private final List<Service> services;
        private final Integer configuredServiceCharacteristicCount;

        Inventory(final int totalServiceCount,
                  final List<Service> services,
                  final Integer configuredServiceCharacteristicCount) {
            this.totalServiceCount = totalServiceCount;
            this.services = new ArrayList<>(services);
            this.configuredServiceCharacteristicCount = configuredServiceCharacteristicCount;
        }
    }

    static final class Service {
        private final UUID uuid;
        private final String type;
        private final int totalCharacteristicCount;
        private final List<Characteristic> characteristics;

        Service(final UUID uuid,
                final String type,
                final int totalCharacteristicCount,
                final List<Characteristic> characteristics) {
            this.uuid = uuid;
            this.type = type;
            this.totalCharacteristicCount = totalCharacteristicCount;
            this.characteristics = new ArrayList<>(characteristics);
        }
    }

    static final class Characteristic {
        private final UUID uuid;
        private final int properties;
        private final String propertyFlags;
        private final int totalDescriptorCount;
        private final List<UUID> descriptorUuids;

        Characteristic(final UUID uuid,
                       final int properties,
                       final String propertyFlags,
                       final int totalDescriptorCount,
                       final List<UUID> descriptorUuids) {
            this.uuid = uuid;
            this.properties = properties;
            this.propertyFlags = propertyFlags;
            this.totalDescriptorCount = totalDescriptorCount;
            this.descriptorUuids = new ArrayList<>(descriptorUuids);
        }
    }

    private static final class EventCollector {
        private final int regularEventLimit;
        private final List<String> events = new ArrayList<>();
        private boolean truncated;

        private EventCollector(final int regularEventLimit) {
            this.regularEventLimit = regularEventLimit;
        }

        private boolean add(final String event) {
            if (events.size() >= regularEventLimit) {
                truncated = true;
                return false;
            }
            events.add(event);
            return true;
        }

        private void addReserved(final String event) {
            events.add(event);
        }

        private boolean isTruncated() {
            return truncated;
        }

        private List<String> getEvents() {
            return Collections.unmodifiableList(events);
        }
    }
}
