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
import java.util.Collections;
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
            final int titleMaxLength = support.getCoordinator().getMaximumReminderMessageLength();
            for (final OpenTasksManager.Task task : tasksManager.getOpenTasks()) {
                if (dismissedTitles.contains(task.title.substring(0, Math.min(task.title.length(), titleMaxLength)))){
                    tasksManager.markCompleted(task.id);
                 }
            }
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

        // second fetch intentional, makes content provider more consistent and avoid desync
        final List<OpenTasksManager.Task> openTasks = tasksManager.getOpenTasks();
        LOG.info("Syncing {} OpenTasks task(s) to watch reminders", openTasks.size());
        final int titleMaxLength = support.getCoordinator().getMaximumReminderMessageLength();
        Collections.sort(openTasks, (a, b) -> {
            int cmp = a.due.compareTo(b.due);
            if (cmp != 0) return cmp;
            return a.title.compareTo(b.title);
        });

        final int remSlotCount = support.getCoordinator().getReminderSlotCount(support.getDevice());
        final int cap = remSlotCount > 0 ? remSlotCount : 20;
        final List<OpenTasksManager.Task> toPush = openTasks.subList(0, Math.min(openTasks.size(), cap));

        final ArrayList<Reminder> reminders = new ArrayList<>();
        for (final OpenTasksManager.Task task : toPush) {
            final Reminder r = new Reminder();
            final String taskTitle = task.title.substring(0, Math.min(task.title.length(), titleMaxLength));
            final String existing = titleToWatchId.get(taskTitle);
            r.setReminderId(existing != null ? existing : "task_" + UUID.randomUUID());
            r.setMessage(taskTitle);
            r.setDate(task.due);
            r.setRepetition(nodomain.freeyourgadget.gadgetbridge.model.Reminder.ONCE);
            reminders.add(r);
        }

        scheduleService.onSetReminders(reminders);
    }
}
