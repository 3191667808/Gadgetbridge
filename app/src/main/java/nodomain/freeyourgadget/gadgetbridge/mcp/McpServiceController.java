/*  Copyright (C) 2026 Liu Haoxin

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
package nodomain.freeyourgadget.gadgetbridge.mcp;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService;

/** Starts the existing Gadgetbridge foreground service when MCP is enabled while it is stopped. */
public final class McpServiceController {
    public static final String ACTION_RECONFIGURE =
            BuildConfig.APPLICATION_ID + ".mcp.action.RECONFIGURE";

    private McpServiceController() {
    }

    public static void ensureServiceRunning(@NonNull final Context context) {
        if (!McpPreferences.isEnabled() || DeviceCommunicationService.isRunning(context)) {
            return;
        }
        final Intent intent = new Intent(context, DeviceCommunicationService.class)
                .setAction(ACTION_RECONFIGURE);
        ContextCompat.startForegroundService(context, intent);
    }
}
