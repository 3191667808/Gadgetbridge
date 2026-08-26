/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service;

import android.content.Intent;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.externalevents.sleepasandroid.SleepAsAndroidAction;

import static nodomain.freeyourgadget.gadgetbridge.model.DeviceService.EXTRA_SLEEP_AS_ANDROID_ACTION;

/**
 * Holds the one Sleep as Android action that has to survive a connect.
 * <p>
 * Sleep as Android starts tracking whether or not the wearable happens to be connected, so a
 * disconnected provider gets connected on demand and the request is replayed once it is ready.
 * Only START_TRACKING is worth holding: CHECK_CONNECTED is repeated every few seconds anyway, and
 * the remaining actions are meaningless without an active session.
 */
public class PendingSleepAsAndroidAction {

    /**
     * Sleep as Android falls back to the phone sensors after about two minutes, so a connect that
     * has not completed by then is replayed too late to be of any use. The hold expires earlier
     * than that, leaving room for the session to actually start on the wearable.
     */
    static final long TIMEOUT_MS = 90_000L;

    @Nullable
    private Intent intent;
    private String address;
    private long deadline;

    /**
     * @return true if the action was worth holding across a connect
     */
    public boolean store(final Intent intent, final String deviceAddress) {
        if (!SleepAsAndroidAction.START_TRACKING.equals(intent.getStringExtra(EXTRA_SLEEP_AS_ANDROID_ACTION))) {
            return false;
        }

        this.intent = new Intent(intent);
        this.address = deviceAddress;
        this.deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        return true;
    }

    /**
     * Hand back the held action for a device that has just become usable, clearing it either way so
     * it can never fire twice or into a later, unrelated session.
     *
     * @return the action, or null if none is held, it belongs to another device, or it expired
     */
    @Nullable
    public Intent take(final String deviceAddress) {
        if (intent == null || !this.address.equals(deviceAddress)) {
            return null;
        }

        final Intent held = intent;
        final boolean expired = SystemClock.elapsedRealtime() > deadline;
        clear();

        return expired ? null : held;
    }

    public void clear() {
        intent = null;
        address = null;
        deadline = 0;
    }

    /**
     * @return true while an action is held and still worth replaying
     */
    public boolean isPending() {
        return intent != null && SystemClock.elapsedRealtime() <= deadline;
    }
}
