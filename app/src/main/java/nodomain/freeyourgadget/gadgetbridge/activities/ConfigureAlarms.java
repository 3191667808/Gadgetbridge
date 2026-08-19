/*  Copyright (C) 2015-2024 Andreas Shimokawa, Carsten Pfeiffer, Damien
    Gaignon, Daniel Dakhno, Daniele Gobbetti, Dmitry Markin, José Rebelo,
    Lem Dulfo, Martin Braun, Petr Vaněk

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
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.adapter.GBAlarmListAdapter;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;


public class ConfigureAlarms extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigureAlarms.class);

    private static final int REQ_CONFIGURE_ALARM = 1;
    private static final String STATE_CONFIGURED_ALARM_POSITION = "configured_alarm_position";
    private static final String STATE_MODIFIED_ALARM_POSITIONS = "modified_alarm_positions";

    private GBAlarmListAdapter mGBAlarmListAdapter;
    private boolean avoidSendAlarmsToDevice;
    private final Set<Integer> locallyModifiedAlarmPositions = new HashSet<>();
    private int configuredAlarmPosition = -1;
    private GBDevice gbDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_configure_alarms);

        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(DeviceService.ACTION_SAVE_ALARMS);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filterLocal);

        gbDevice = getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        if (savedInstanceState != null) {
            configuredAlarmPosition = savedInstanceState.getInt(
                    STATE_CONFIGURED_ALARM_POSITION,
                    -1
            );
            final ArrayList<Integer> modifiedPositions =
                    savedInstanceState.getIntegerArrayList(STATE_MODIFIED_ALARM_POSITIONS);
            if (modifiedPositions != null) {
                locallyModifiedAlarmPositions.addAll(modifiedPositions);
            }
        }

        mGBAlarmListAdapter = new GBAlarmListAdapter(this);

        RecyclerView alarmsRecyclerView = findViewById(R.id.alarm_list);
        alarmsRecyclerView.setHasFixedSize(true);
        alarmsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        alarmsRecyclerView.setAdapter(mGBAlarmListAdapter);
        updateAlarmsFromDB();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!avoidSendAlarmsToDevice) {
            requestAlarmsFromDevice();
        }
    }

    @Override
    protected void onPause() {
        if (!avoidSendAlarmsToDevice &&
                (!supportsAlarmListSynchronization() ||
                        !locallyModifiedAlarmPositions.isEmpty()) &&
                gbDevice.isInitialized()) {
            sendAlarmsToDevice();
        }
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONFIGURE_ALARM) {
            avoidSendAlarmsToDevice = false;
            onAlarmChangedByUser(configuredAlarmPosition);
            configuredAlarmPosition = -1;
            updateAlarmsFromDB();
        }
    }

    /**
     * Reads the available alarms from the database and updates the view afterwards.
     */
    private void updateAlarmsFromDB() {
        List<Alarm> alarms = DBHelper.getAlarms(getGbDevice());
        if (alarms.isEmpty()) {
            alarms = AlarmUtils.readAlarmsFromPrefs(getGbDevice());
            storeMigratedAlarms(alarms);
        }
        DBHelper.fillMissingAlarms(gbDevice, alarms);

        mGBAlarmListAdapter.setAlarmList(alarms);
        mGBAlarmListAdapter.notifyDataSetChanged();
    }

    private void storeMigratedAlarms(List<Alarm> alarms) {
        for (Alarm alarm : alarms) {
            DBHelper.store(alarm);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            // back button
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void configureAlarm(Alarm alarm) {
        avoidSendAlarmsToDevice = true;
        configuredAlarmPosition = alarm.getPosition();
        Intent startIntent = new Intent(getApplicationContext(), AlarmDetails.class);
        startIntent.putExtra(Alarm.EXTRA_ALARM, alarm);
        startIntent.putExtra(GBDevice.EXTRA_DEVICE, getGbDevice());
        startActivityForResult(startIntent, REQ_CONFIGURE_ALARM);
    }

    private GBDevice getGbDevice() {
        return gbDevice;
    }

    private void sendAlarmsToDevice() {
        if (supportsAlarmListSynchronization() && !locallyModifiedAlarmPositions.isEmpty()) {
            for (final Alarm alarm : mGBAlarmListAdapter.getAlarmList()) {
                if (locallyModifiedAlarmPositions.contains(alarm.getPosition())) {
                    DBHelper.store(alarm);
                }
            }
            updateAlarmsFromDB();
        }
        GBApplication.deviceService(gbDevice).onSetAlarms(mGBAlarmListAdapter.getAlarmList());
        locallyModifiedAlarmPositions.clear();
    }

    private void requestAlarmsFromDevice() {
        if (gbDevice.isInitialized() && supportsAlarmListSynchronization()) {
            GBApplication.deviceService(gbDevice).onReadConfiguration(DeviceService.CONFIG_ALARMS);
        }
    }

    public void onAlarmChangedByUser(final Alarm alarm) {
        onAlarmChangedByUser(alarm.getPosition());
    }

    private void onAlarmChangedByUser(final int position) {
        if (!supportsAlarmListSynchronization()) {
            return;
        }
        if (position >= 0) {
            locallyModifiedAlarmPositions.add(position);
        }
    }

    public boolean supportsAlarmListSynchronization() {
        return gbDevice.getDeviceCoordinator().supportsAlarmListSynchronization(gbDevice);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            switch (action) {
                case DeviceService.ACTION_SAVE_ALARMS: {
                    if (!supportsAlarmListSynchronization()) {
                        updateAlarmsFromDB();
                        break;
                    }
                    final GBDevice sourceDevice = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                    if (sourceDevice != null && !gbDevice.equals(sourceDevice)) {
                        break;
                    }
                    if (!locallyModifiedAlarmPositions.isEmpty()) {
                        LOG.debug("Ignoring alarm screen refresh because local changes are pending");
                        break;
                    }
                    updateAlarmsFromDB();
                    break;
                }
            }
        }
    };

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        outState.putInt(STATE_CONFIGURED_ALARM_POSITION, configuredAlarmPosition);
        outState.putIntegerArrayList(
                STATE_MODIFIED_ALARM_POSITIONS,
                new ArrayList<>(locallyModifiedAlarmPositions)
        );
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        super.onDestroy();
    }

}
