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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt.YcbtDiagnostics;

final class YcbtPairingLifecycle {
    enum Outcome {
        NONE,
        SUCCESS,
        FAILURE
    }

    private YcbtPairingLifecycle() {
    }

    static Outcome classify(final String eventType,
                            final boolean attemptInProgress,
                            final boolean success,
                            final boolean terminalFailure) {
        if (!attemptInProgress || success) {
            return Outcome.NONE;
        }
        if (YcbtDiagnostics.TYPE_FAILURE.equals(eventType)
                || YcbtDiagnostics.TYPE_DISCONNECTED.equals(eventType)) {
            return Outcome.FAILURE;
        }
        if (!terminalFailure && YcbtDiagnostics.TYPE_INITIALIZED.equals(eventType)) {
            return Outcome.SUCCESS;
        }
        return Outcome.NONE;
    }
}
