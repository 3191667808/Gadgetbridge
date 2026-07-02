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
package nodomain.freeyourgadget.gadgetbridge.util.tasks;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class OpenTasksManager {
    private static final Logger LOG = LoggerFactory.getLogger(OpenTasksManager.class);

    public static final String PREF_ENABLED = "pref_tasks_sync_enabled";

    public static final String PERMISSION_READ_TASKS = "org.tasks.permission.READ_TASKS";
    public static final String PERMISSION_WRITE_TASKS = "org.tasks.permission.WRITE_TASKS";

    // tasks.org re-hosts the provider under its own authority (not org.dmfs.tasks).
    private static final Uri TASKS_URI = Uri.parse("content://org.tasks.opentasks/tasks");

    private static final String COL_ID = "_id";
    private static final String COL_UID = "_uid";
    private static final String COL_TITLE = "title";
    private static final String COL_DUE = "due";
    private static final String COL_CREATED = "created";
    private static final String COL_STATUS = "status";
    private static final String COL_PARENT_ID = "parent_id";
    private static final String COL_IS_CLOSED = "is_closed";

    private static final int STATUS_NEEDS_ACTION = 0;
    private static final int STATUS_IN_PROCESS = 1;
    private static final int STATUS_COMPLETED = 2;

    private static final String COL_COMPLETED = "completed";
    private static final String COL_PERCENT_COMPLETE = "percent_complete";

    public static class Task {
        public final long id;
        public final String uid;
        public final String title;
        public final Date due;

        public Task(final long id, final String uid, final String title, final Date due) {
            this.id = id;
            this.uid = uid;
            this.title = title;
            this.due = due;
        }
    }

    private final Context context;

    public OpenTasksManager(@NonNull final Context context) {
        this.context = context;
    }

    /** Whether tasks.org sync is both supported by the device and enabled in settings. */
    public static boolean isSyncEnabled(@NonNull final GBDevice device) {
        return device.getDeviceCoordinator().supportsTasksSync(device)
                && GBApplication.getPrefs().getBoolean(PREF_ENABLED, false);
    }

    public boolean isProviderAvailable() {
        try (final Cursor c = context.getContentResolver().query(TASKS_URI, new String[]{COL_ID}, null, null, null)) {
            return c != null;
        } catch (final SecurityException e) {
            LOG.warn("OpenTasks provider permission denied: {}", e.getMessage());
            return false;
        } catch (final Exception e) {
            LOG.debug("OpenTasks provider not available: {}", e.getMessage());
            return false;
        }
    }

    public List<Task> getOpenTasks() {
        final List<Task> result = new ArrayList<>();

        final String[] projection = new String[]{COL_ID, COL_UID, COL_TITLE, COL_DUE, COL_CREATED};

        final String selection = COL_IS_CLOSED + " = 0"
                + " AND " + COL_PARENT_ID + " IS NULL"
                + " AND (" + COL_STATUS + " = " + STATUS_NEEDS_ACTION
                + "      OR " + COL_STATUS + " = " + STATUS_IN_PROCESS + ")";

        final String sortOrder = "COALESCE(" + COL_DUE + ", 99999999999999) ASC, " + COL_CREATED + " ASC";

        LOG.debug("Querying OpenTasks: uri={} selection=({}) sort=({})", TASKS_URI, selection, sortOrder);

        final ContentResolver cr = context.getContentResolver();
        try (final Cursor c = cr.query(TASKS_URI, projection, selection, null, sortOrder)) {
            if (c == null) {
                LOG.warn("OpenTasks query returned null cursor");
                return result;
            }
            LOG.debug("OpenTasks query returned cursor with {} row(s); columns: {}",
                    c.getCount(), java.util.Arrays.toString(c.getColumnNames()));
            final int idxId = c.getColumnIndexOrThrow(COL_ID);
            final int idxUid = c.getColumnIndex(COL_UID);
            final int idxTitle = c.getColumnIndexOrThrow(COL_TITLE);
            final int idxDue = c.getColumnIndexOrThrow(COL_DUE);
            final int idxCreated = c.getColumnIndex(COL_CREATED);

            while (c.moveToNext()) {
                final long id = c.getLong(idxId);
                final String uid = (idxUid >= 0 && !c.isNull(idxUid)) ? c.getString(idxUid) : String.valueOf(id);
                final String title = c.isNull(idxTitle) ? null : c.getString(idxTitle);
                final boolean dueNull = c.isNull(idxDue);
                final long dueRaw = dueNull ? 0L : c.getLong(idxDue);
                final boolean createdNull = idxCreated < 0 || c.isNull(idxCreated);
                final long createdRaw = createdNull ? 0L : c.getLong(idxCreated);
                if (title == null || title.isEmpty()) {
                    LOG.debug("Skipping task id={} uid={}: empty title", id, uid);
                    continue;
                }
                // Undated tasks use created time so they appear but won't ring.
                final long ts;
                if (!dueNull) {
                    ts = dueRaw;
                } else if (!createdNull) {
                    ts = createdRaw;
                } else {
                    ts = System.currentTimeMillis();
                }
                if (ts <= 0) {
                    LOG.debug("Skipping task id={} uid={} title='{}': non-positive ts", id, uid, title);
                    continue;
                }
                // Truncate millis to match Xiaomi reminder precision (seconds).
                final long tsSec = (ts / 1000) * 1000;
                LOG.debug("Task id={} uid={} title='{}' due={} created={} -> reminder ts={}",
                        id, uid, title, dueRaw, createdRaw, new Date(tsSec));
                result.add(new Task(id, uid, title, new Date(tsSec)));
            }
            LOG.debug("OpenTasks: built {} task reminder(s) from {} cursor row(s)", result.size(), c.getCount());
        } catch (final SecurityException e) {
            LOG.warn("OpenTasks read permission denied: {}", e.getMessage());
        } catch (final Exception e) {
            LOG.warn("Failed to read OpenTasks", e);
        }

        return result;
    }

    public boolean markCompleted(final long taskId) {
        final ContentValues values = new ContentValues();
        values.put(COL_STATUS, STATUS_COMPLETED);
        values.put(COL_COMPLETED, System.currentTimeMillis());
        values.put(COL_PERCENT_COMPLETE, 100);

        final Uri uri = ContentUris.withAppendedId(TASKS_URI, taskId);
        try {
            final int updated = context.getContentResolver().update(uri, values, null, null);
            LOG.debug("Marked OpenTasks task id={} completed; rows updated={}", taskId, updated);
            return updated > 0;
        } catch (final SecurityException e) {
            LOG.warn("OpenTasks write permission denied for task id={}: {}", taskId, e.getMessage());
        } catch (final Exception e) {
            LOG.warn("Failed to mark OpenTasks task id=" + taskId + " completed", e);
        }
        return false;
    }
}
