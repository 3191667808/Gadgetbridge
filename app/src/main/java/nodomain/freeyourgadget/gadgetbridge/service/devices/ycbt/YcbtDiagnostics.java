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

import android.content.Context;
import android.content.Intent;

public final class YcbtDiagnostics {
    public static final String ACTION_EVENT = "nodomain.freeyourgadget.gadgetbridge.ycbt.DIAGNOSTIC_EVENT";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    public static final String EXTRA_EVENT_TYPE = "event_type";
    public static final String EXTRA_MESSAGE = "message";

    public static final String TYPE_STAGE = "stage";
    public static final String TYPE_INITIALIZE_ENTERED = "initialize_entered";
    public static final String TYPE_FAILURE = "failure";
    public static final String TYPE_INITIALIZED = "initialized";
    public static final String TYPE_DISCONNECTED = "disconnected";

    private YcbtDiagnostics() {
    }

    public static void emit(final Context context,
                            final String deviceAddress,
                            final String eventType,
                            final String message) {
        final Intent intent = new Intent(ACTION_EVENT)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
                .putExtra(EXTRA_EVENT_TYPE, eventType)
                .putExtra(EXTRA_MESSAGE, message);
        context.sendBroadcast(intent);
    }
}
