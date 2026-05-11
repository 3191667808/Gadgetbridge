/*  Copyright (C) 2022-2026 José Rebelo, Martin Braun

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.


    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.ConfigureAlarms;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;

public class DeviceAlarmReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceAlarmReceiver.class);

    private static final String macAddrPattern = "^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$";

    public static final String COMMAND_SET_ALARM = "nodomain.freeyourgadget.gadgetbridge.command.SET_ALARM";
    public static final String COMMAND_DISMISS_ALARM = "nodomain.freeyourgadget.gadgetbridge.command.DISMISS_ALARM";

    public static final String EXTRA_MAC_ADDR = "device";
    public static final String EXTRA_INDEX = "id";
    public static final String EXTRA_DAYS = "days";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTES = "minutes";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_ALARM_SEARCH_MODE = "mode";
    public static final String ALARM_SEARCH_MODE_ALL = "all";
    public static final String ALARM_SEARCH_MODE_INDEX = "index";
    public static final String ALARM_SEARCH_MODE_TIME = "time";
    public static final String ALARM_SEARCH_MODE_LABEL = "label";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();

        if (!COMMAND_SET_ALARM.equals(action) && !COMMAND_DISMISS_ALARM.equals(action)) {
            LOG.warn("Unexpected action {}", intent.getAction());
        }
        LOG.debug("Got third party alarm action: {}", action);

        final String deviceAddress = intent.getStringExtra(EXTRA_MAC_ADDR);

        if (deviceAddress == null) {
            LOG.warn("Missing device address");
            return;
        }

        if (!deviceAddress.matches(macAddrPattern)) {
            LOG.warn("Device address '{}' does not match '{}'", deviceAddress, macAddrPattern);
            return;
        }

        final GBDevice targetDevice = GBApplication.app()
                .getDeviceManager()
                .getDeviceByAddress(deviceAddress);

        if (targetDevice == null) {
            LOG.warn("Unknown device {}", deviceAddress);
            return;
        }

        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(targetDevice.getAddress());

        if (!prefs.getBoolean("third_party_apps_set_alarms", false)) {
            LOG.warn("Setting alarms from 3rd party apps not allowed for {}", deviceAddress);
            return;
        }

        if (GBApplication.getPrefs().getString("notification_list_is_blacklist", "true").equals("true")) {
            if (GBApplication.appIsNotifBlacklisted(intent.getPackage())) {
                LOG.info("Ignoring alarm action, application is blacklisted");
                return;
            }
        } else {
            if (!GBApplication.appIsNotifBlacklisted(intent.getPackage())) {
                LOG.info("Ignoring alarm action, application is not whitelisted");
                return;
            }
        }

        final ArrayList<Alarm> alarms = (ArrayList<Alarm>) DBHelper.getAlarmsWithDefaults(targetDevice);
        if (alarms.isEmpty()) {
            LOG.error("alarms not supported by device");
            return;
        }

        boolean changed = false;
        final String mode = intent.getStringExtra(EXTRA_ALARM_SEARCH_MODE);
        final String title = intent.getStringExtra(EXTRA_MESSAGE);
        if (COMMAND_SET_ALARM.equals(action) ||
                (COMMAND_DISMISS_ALARM.equals(action) && Objects.equals(mode, ALARM_SEARCH_MODE_INDEX))) {
            // user wants to update a single alarm based on index,
            // so wants to create or dismiss a positional alarm
            boolean enable = COMMAND_SET_ALARM.equals(action);
            final int position = intent.getIntExtra(EXTRA_INDEX, -1);
            // slot ID -1 indicates the user wants to create an alarm on the next free slot (next disabled alarm)
            if (position < 0 && !enable) {
                return; // as it makes no sense to disable the next disabled alarm
            }
            final int hour = intent.getIntExtra(EXTRA_HOUR, -1);
            final int minute = intent.getIntExtra(EXTRA_MINUTES, -1);
            if (COMMAND_SET_ALARM.equals(action) && (hour < 0 || minute < 0)) {
                LOG.warn("both hour and minutes have to be provided when creating an alarm");
                return;
            }
            final ArrayList<Integer> days = intent.getIntegerArrayListExtra(EXTRA_DAYS); // Calendar.SUNDAY, Calendar.MONDAY, Cal..;
            int repetition = convertRepetitionMask(days);
            for (Alarm alarm : alarms) {
                if ((position == -1 && !alarm.getEnabled()) || alarm.getPosition() == position) {
                    updateAlarm(
                            alarm, enable, hour, minute, repetition,
                            title != null && !title.isEmpty() ? title : alarm.getTitle()
                    );
                    DBHelper.store(alarm);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(
                            new Intent(DeviceService.ACTION_SAVE_ALARMS)
                    );
                    changed = true;
                    break;
                }
            }
            if (!changed && position == -1) {
                LOG.warn("free alarm slot unavailable");
                return;
            }
        } else if (COMMAND_DISMISS_ALARM.equals(action)) {
            // user wants to dismiss all alarms or alarm(s) by time or label/title
            if (!Objects.equals(mode, ALARM_SEARCH_MODE_TIME) &&
                    !Objects.equals(mode, ALARM_SEARCH_MODE_ALL) &&
                    !Objects.equals(mode, ALARM_SEARCH_MODE_LABEL)) {
                LOG.warn("unknown mode");
                return;
            }
            final int hour = intent.getIntExtra(EXTRA_HOUR, -1);
            final int minutes = intent.getIntExtra(EXTRA_MINUTES, -1);
            if (Objects.equals(mode, ALARM_SEARCH_MODE_TIME) && hour < 0 && minutes < 0) {
                LOG.warn("either hour, minutes, or both have to be provided when dismissing an alarm by time");
                return;
            }
            if (Objects.equals(mode, ALARM_SEARCH_MODE_LABEL) && (title == null || title.isEmpty())) {
                LOG.warn("message has to be provided when dismissing an alarm by label");
                return;
            }
            for (Alarm alarm : alarms) {
                if (Objects.equals(mode, ALARM_SEARCH_MODE_ALL) ||
                        (Objects.equals(mode, ALARM_SEARCH_MODE_LABEL)
                                && alarm.getTitle() != null
                                && alarm.getTitle().contains(title)
                        ) || (hour > -1 && alarm.getHour() == hour)
                        || (minutes > -1 && alarm.getMinute() == minutes)) {
                    updateAlarm(alarm, false, alarm.getHour(), alarm.getMinute(), 0, alarm.getTitle());
                    DBHelper.store(alarm);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(
                            new Intent(DeviceService.ACTION_SAVE_ALARMS)
                    );
                    changed = true;
                }
            }
        } else {
            LOG.warn("unknown action");
        }

        if (changed) {
            GBApplication.deviceService(targetDevice).onSetAlarms(alarms);
        }
    }

    private static int convertRepetitionMask(ArrayList<Integer> days) {
        int repetitionMask = 0;
        if (days != null && !days.isEmpty()) {
            for (int day : days) {
                switch (day) {
                    case Calendar.MONDAY:
                        repetitionMask += Alarm.ALARM_MON;
                        break;
                    case Calendar.TUESDAY:
                        repetitionMask += Alarm.ALARM_TUE;
                        break;
                    case Calendar.WEDNESDAY:
                        repetitionMask += Alarm.ALARM_WED;
                        break;
                    case Calendar.THURSDAY:
                        repetitionMask += Alarm.ALARM_THU;
                        break;
                    case Calendar.FRIDAY:
                        repetitionMask += Alarm.ALARM_FRI;
                        break;
                    case Calendar.SATURDAY:
                        repetitionMask += Alarm.ALARM_SAT;
                        break;
                    case Calendar.SUNDAY:
                        repetitionMask += Alarm.ALARM_SUN;
                        break;
                }
            }
        }
        return repetitionMask;
    }

    private static void updateAlarm(Alarm alarm, boolean enable, int hour, int minute, int repetition, String title) {
        alarm.setHour(hour);
        alarm.setMinute(minute);
        alarm.setRepetition(repetition);
        alarm.setTitle(title);
        alarm.setEnabled(enable);
    }

    public IntentFilter buildFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(COMMAND_SET_ALARM);
        intentFilter.addAction(COMMAND_DISMISS_ALARM);
        return intentFilter;
    }
}
