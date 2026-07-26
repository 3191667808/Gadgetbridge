/*  Copyright (C) 2024 José Rebelo
    Copyright (C) 2026 NTeditor

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import android.content.Context;
import androidx.annotation.NonNull;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;

public class FirmwareInfo extends AbstractInfo {
    private static final Logger LOG = LoggerFactory.getLogger(FirmwareInfo.class);

    FirmwareInfo(@NonNull final Context context) {
        super(context);
    }

    public List<GBDeviceEvent> decode(@NonNull final byte[] payload) {
        return List.of(decodeSingle(payload));
    }

    private GBDeviceEvent decodeSingle(final byte[] payload) {
        final String fwString;
        if (payload[payload.length - 1] == 0) {
            fwString = new String(ArrayUtils.subarray(payload, 2, payload.length - 1)).strip();
        } else {
            fwString = new String(ArrayUtils.subarray(payload, 2, payload.length)).strip();
        }
        final String[] parts = fwString.split(",");
        if (parts.length % 3 != 0) {
            LOG.warn("Fw parts length {} from '{}' is not divisible by 3", parts.length, fwString);

            // We need to persist something, otherwise Gb misbehaves
            final GBDeviceEventVersionInfo eventVersionInfo = new GBDeviceEventVersionInfo();
            eventVersionInfo.fwVersion = fwString;
            eventVersionInfo.hwVersion = getContext().getString(R.string.n_a);
            return eventVersionInfo;
        }
        final String[] fwVersionParts = new String[3];
        for (int i = 0; i < parts.length; i += 3) {
            final String versionPart = parts[i];
            final String versionType = parts[i + 1];
            final String version = parts[i + 2];
            if (!"2".equals(versionType)) {
                continue; // not fw
            }

            switch (versionPart) {
                case "1":
                    fwVersionParts[0] = version;
                    break;
                case "2":
                    fwVersionParts[1] = version;
                    break;
                case "3":
                    fwVersionParts[2] = version;
                    break;
                default:
                    LOG.warn("Unknown firmware version part {}", versionPart);
            }
        }

        final List<String> nonNullParts = new ArrayList<>(fwVersionParts.length);
        for (int i = 0; i < fwVersionParts.length; i++) {
            if (fwVersionParts[i] == null) {
                continue;
            }
            nonNullParts.add(fwVersionParts[i]);
            if (fwVersionParts[i].contains(".")) {
                // Realme devices have the version already with the dots, repeated multiple
                // times
                break;
            }
        }
        final String fwVersion = String.join(".", nonNullParts);

        final GBDeviceEventVersionInfo eventVersionInfo = new GBDeviceEventVersionInfo();
        eventVersionInfo.fwVersion = fwVersion;
        eventVersionInfo.hwVersion = getContext().getString(R.string.n_a);

        LOG.debug("Got firmware version: {}", fwVersion);
        return eventVersionInfo;
    }
}
