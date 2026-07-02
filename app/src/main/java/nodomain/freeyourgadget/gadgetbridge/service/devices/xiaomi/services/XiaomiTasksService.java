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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.entities.Reminder;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.util.tasks.OpenTasksManager;

public class XiaomiTasksService {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiTasksService.class);

    private final XiaomiSupport support;
    private final XiaomiScheduleService scheduleService;

    private volatile boolean pushAfterNextWatchResponse = false;

    public XiaomiTasksService(final XiaomiSupport support, final XiaomiScheduleService scheduleService) {
        this.support = support;
        this.scheduleService = scheduleService;
    }

    public void syncTasks() {
        if (support.getContext() == null || support.getDevice() == null) {
            return;
        }
        pushAfterNextWatchResponse = true;
        scheduleService.requestReminders();
    }

    /**
     * @return true if tasks were pushed (caller should not re-sync from stale response)
     */
    public boolean onWatchReminders(final XiaomiProto.Reminders reminders) {
        if (support.getContext() == null) {
            return false;
        }
        if (!OpenTasksManager.isSyncEnabled(support.getDevice())) {
            return false;
        }

        final Set<String> dismissedTitles = new HashSet<>();
        for (final XiaomiProto.Reminder r : reminders.getReminderList()) {
            final XiaomiProto.ReminderDetails details = r.getReminderDetails();
            final int state = details.hasState() ? details.getState() : -1;
            LOG.debug("Watch reminder id={} title='{}' state={} dismissedAt={}",
                    r.getId(), details.getTitle(), state,
                    details.hasDismissedAt() ? details.getDismissedAt() : 0);
            if (state == 2) {
                final String title = details.getTitle();
                if (title != null && !title.isEmpty()) {
                    dismissedTitles.add(title);
                }
            }
        }

        final OpenTasksManager tasksManager = new OpenTasksManager(support.getContext());
        if (!tasksManager.isProviderAvailable()) {
            if (pushAfterNextWatchResponse) {
                pushAfterNextWatchResponse = false;
                LOG.debug("OpenTasks provider not available, skipping push");
            }
            return false;
        }

        if (!dismissedTitles.isEmpty()) {
            int markedCount = 0;
            for (final OpenTasksManager.Task task : tasksManager.getOpenTasks()) {
                if (dismissedTitles.contains(task.title)) {
                    if (tasksManager.markCompleted(task.id)) {
                        markedCount++;
                    }
                }
            }
            LOG.info("Sync-back: marked {} task(s) completed from {} watch reminder(s) with state=2",
                    markedCount, dismissedTitles.size());
        } else {
            LOG.debug("Sync-back: no watch reminders reported state=2");
        }

        if (!pushAfterNextWatchResponse) {
            return false;
        }
        pushAfterNextWatchResponse = false;

        pushTasks(reminders, tasksManager);
        return true;
    }

    private void pushTasks(final XiaomiProto.Reminders watchReminders, final OpenTasksManager tasksManager) {
        final Map<String, String> titleToWatchId = new HashMap<>();
        for (final XiaomiProto.Reminder r : watchReminders.getReminderList()) {
            final String title = r.getReminderDetails().getTitle();
            if (title != null && !title.isEmpty()) {
                titleToWatchId.put(title, "xiaomi_" + r.getId());
            }
        }

        final List<OpenTasksManager.Task> openTasks = tasksManager.getOpenTasks();
        LOG.info("Syncing {} OpenTasks task(s) to watch reminders", openTasks.size());

        final ArrayList<Reminder> reminders = new ArrayList<>();
        for (final OpenTasksManager.Task task : openTasks) {
            final Reminder r = new Reminder();
            final String existing = titleToWatchId.get(task.title);
            r.setReminderId(existing != null ? existing : "task_" + UUID.randomUUID());
            r.setMessage(task.title);
            r.setDate(task.due);
            r.setRepetition(nodomain.freeyourgadget.gadgetbridge.model.Reminder.ONCE);
            reminders.add(r);
        }

        scheduleService.onSetReminders(reminders);
    }
}
