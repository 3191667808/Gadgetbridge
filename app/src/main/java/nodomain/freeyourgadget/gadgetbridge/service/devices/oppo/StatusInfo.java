/*  Copyright (C) 2026 NTeditor

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.StatusInfoSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.StatusInfoValue;

public class StatusInfo extends AbstractInfo {
    private static final Logger LOG = LoggerFactory.getLogger(StatusInfo.class);

    StatusInfo(@NonNull final Context context) {
        super(context);
    }

    public Map<StatusInfoSide, StatusInfoValue> decode(@NonNull final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        final int zero = buf.get();

        final Map<StatusInfoSide, StatusInfoValue> map = new HashMap<>();
        final int sidesNum = buf.get() & 0xff;
        for (int i = 0; i < sidesNum; i++) {
            final int sideCode = buf.get() & 0xff;
            final int valueCode = buf.get() & 0xff;

            final StatusInfoSide side = StatusInfoSide.fromCode(sideCode);
            if (side == null) {
                LOG.warn("Unknown StatusInfoSide code 0x{}", OppoUtils.numberToHex(sideCode));
                continue;
            }

            final StatusInfoValue value = StatusInfoValue.fromCode(valueCode);
            if (value == null) {
                LOG.warn("Unknown StatusInfoValue code 0x{}", OppoUtils.numberToHex(valueCode));
                continue;
            }

            LOG.warn("Got status for {} = {}", side, value);
            map.put(side, value);
        }
        return map;
    }
}
