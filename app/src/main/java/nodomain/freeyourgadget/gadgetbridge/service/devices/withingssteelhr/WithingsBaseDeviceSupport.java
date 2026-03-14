/*  Copyright (C) 2023-2024 Ascense, Frank Ertl

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.huami.HuamiConst;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.ServerTransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.activity.WithingsActivityType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.WithingsUUIDs;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.ActivitySampleHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.BatteryStateHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.Conversation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.ConversationQueue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.GetAlarmHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.HeartRateHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.ResponseHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.SetupFinishedHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.SimpleConversation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.SyncFinishedHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.ScreenSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation.WorkoutScreenListHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivityTarget;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.AlarmName;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.AlarmSettings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.AlarmStatus;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.AncsStatus;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.DataStructureFactory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.EndOfTransmission;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.GetActivitySamples;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ImageData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ImageMetaData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.Locale;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.MoveHand;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.Probe;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ProbeOsVersion;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ScreenSettings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WithingsScreenId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.Time;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.TypeVersion;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.User;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.UserUnit;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.UserUnitConstants;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WorkoutScreen;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.ExpectedResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.MessageBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.MessageFactory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.SimpleHexToByteMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessageType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.incoming.IncomingMessageHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.incoming.IncomingMessageHandlerFactory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.incoming.LiveWorkoutHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.notification.GetNotificationAttributes;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.notification.GetNotificationAttributesResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.notification.NotificationProvider;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.notification.NotificationSource;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_LANGUAGE;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_LANGUAGE_AUTO;

public abstract class WithingsBaseDeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger logger = LoggerFactory.getLogger(WithingsBaseDeviceSupport.class);
    public static final String LAST_ACTIVITY_SYNC = "lastActivitySync";
    public static final String HANDS_CALIBRATION_CMD = "withings_hands_calibration";
    public static final String START_HANDS_CALIBRATION_CMD = "start_withings_hands_calibration";
    public static final String STOP_HANDS_CALIBRATION_CMD = "stop_withings_hands_calibration";
    /**
     * Device-specific SharedPreferences key for the userId sent to the watch in SET_USER.
     * Used by ScreenSettings and FeatureTagsUserId to ensure a consistent, non-zero userId.
     */
    public static final String PREF_WITHINGS_USER_ID = "withings_user_id";
    private final MessageBuilder messageBuilder;
    private LiveWorkoutHandler liveWorkoutHandler;
    private ActivitySampleHandler activitySampleHandler;
    private final ConversationQueue conversationQueue;
    private boolean firstTimeConnect;
    private BluetoothGattCharacteristic notificationSourceCharacteristic;
    private BluetoothGattCharacteristic dataSourceCharacteristic;
    private BluetoothDevice device;

    /**
     * Creates a device-specific sample provider for storing activity data.
     * Subclasses return the provider appropriate for their database table.
     */
    public abstract nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider<? extends nodomain.freeyourgadget.gadgetbridge.entities.AbstractWithingsActivitySample> createSampleProvider(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice device, nodomain.freeyourgadget.gadgetbridge.entities.DaoSession session);

    /**
     * Returns the BLE UUID set for this device variant.
     * Steel HR uses suffix "0037"; Scanwatch uses suffix "005d".
     */
    protected abstract WithingsUUIDs getWithingsUUIDs();
    private boolean syncInProgress;
    private final ActivityUser activityUser;
    private final NotificationProvider notificationProvider;
    private final IncomingMessageHandlerFactory incomingMessageHandlerFactory;
    private final Handler backgroundTasksHandler = new Handler(Looper.getMainLooper());

    public WithingsBaseDeviceSupport() {
        super(logger);
        conversationQueue = new ConversationQueue(this);
        notificationProvider = NotificationProvider.getInstance(this);
        messageBuilder = new MessageBuilder(this, new MessageFactory(new DataStructureFactory()));
        liveWorkoutHandler = new LiveWorkoutHandler(this);
        incomingMessageHandlerFactory = IncomingMessageHandlerFactory.getInstance(this);
        addSupportedService(getWithingsUUIDs().WITHINGS_SERVICE_UUID);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addANCSService();
        activityUser = new ActivityUser();

        IntentFilter commandFilter = new IntentFilter(HANDS_CALIBRATION_CMD);
        commandFilter.addAction(START_HANDS_CALIBRATION_CMD);
        commandFilter.addAction(STOP_HANDS_CALIBRATION_CMD);
        final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() == null) {
                    return;
                }

                switch (intent.getAction()) {
                    case HANDS_CALIBRATION_CMD:
                        MoveHand moveHand = new MoveHand();
                        moveHand.setHand(intent.getShortExtra("hand", (short) 1));
                        moveHand.setMovement(intent.getShortExtra("movementAmount", (short) 1));
                        sendToDevice(new WithingsMessage(WithingsMessageType.MOVE_HAND, moveHand));
                        break;
                    case START_HANDS_CALIBRATION_CMD:
                        sendToDevice(new WithingsMessage(WithingsMessageType.START_HANDS_CALIBRATION));
                        break;
                    case STOP_HANDS_CALIBRATION_CMD:
                        sendToDevice(new WithingsMessage(WithingsMessageType.STOP_HANDS_CALIBRATION));
                        break;
                }
            }
        };

        LocalBroadcastManager.getInstance(GBApplication.getContext()).registerReceiver(commandReceiver, commandFilter);
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            backgroundTasksHandler.removeCallbacksAndMessages(null);

            super.dispose();
        }
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        logger.debug("Starting initialization...");
        conversationQueue.clear();
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // Delay initialization with 2 seconds to give the watch time to settle
        backgroundTasksHandler.removeCallbacksAndMessages(null);
        backgroundTasksHandler.postDelayed(this::postConnectInitialization, 2000);

        return builder;
    }

    private void postConnectInitialization() {
        final TransactionBuilder builder = createTransactionBuilder("delayed initialization");
        builder.notify(getWithingsUUIDs().WITHINGS_WRITE_CHARACTERISTIC_UUID, true);
        builder.requestMtu(512);
        builder.queue();
    }

    @Override
    public boolean connectFirstTime() {
        firstTimeConnect = true;
        return connect();
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        super.onMtuChanged(gatt, mtu, status);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger.error("Failed to change mtu - disconnecting");
            disconnect();
            return;
        }

        logger.debug("MTU has changed to {}", mtu);
        if (firstTimeConnect) {
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.INITIAL_CONNECT));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_LOCALE, getLocale()));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.START_HANDS_CALIBRATION));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.STOP_HANDS_CALIBRATION));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_TIME, new Time()));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_USER_UNIT, new UserUnit(UserUnitConstants.DISTANCE, getUnit())));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_USER_UNIT, new UserUnit(UserUnitConstants.CLOCK_MODE, getTimeMode())));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_ACTIVITY_TARGET, new ActivityTarget(ActivityTarget.GOAL_TYPE_STEPS, activityUser.getStepsGoal())));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_ANCS_STATUS));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_ANCS_STATUS, new AncsStatus(true)));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_BATTERY_STATUS), new BatteryStateHandler(this));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SETUP_FINISHED), new SetupFinishedHandler(this));
        } else {
            Message message = new WithingsMessage(WithingsMessageType.PROBE);
            message.addDataStructure(new Probe((short) 1, (short) 1, 5100401));
            message.addDataStructure(new ProbeOsVersion((short) Build.VERSION.SDK_INT));
            conversationQueue.clear();
            addSimpleConversationToQueue(message, new AuthenticationHandler(this));
        }

        if (!firstTimeConnect) {
            finishInitialization();
        }
        conversationQueue.send();
    }

    public void doSync() {
        activitySampleHandler = new ActivitySampleHandler(this);
        conversationQueue.clear();
        try {
            if (syncInProgress) {
                return;
            }

            getDevice().setBusyTask(R.string.busy_task_syncing, getContext());
            syncInProgress = true;
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.INITIAL_CONNECT));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_ANCS_STATUS));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_BATTERY_STATUS), new BatteryStateHandler(this));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_TIME, new Time()));

            if (shoudSync()) {
                logger.debug("Doing full sync...");
                final User user = getUser();
                // Store the userId we're about to send to the watch so that all subsequent
                // commands built in addExtraSyncCommands() (ScreenSettings, FeatureTagsUserId, etc.)
                // can read a consistent, non-zero userId from prefs.
                GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress()).edit()
                        .putInt(PREF_WITHINGS_USER_ID, user.getUserID())
                        .apply();
                WithingsMessage message = new WithingsMessage(WithingsMessageType.SET_USER);
                message.addDataStructure(user);
                // The UserSecret appears in the original communication with the HealthMate app. Until now GB works without the secret.
                // This makes the "authentication" far easier. However if it turns out that this is needed, we would need to find a way to savely store a unique generated secret.
                //  message.addDataStructure(new UserSecret());
                addSimpleConversationToQueue(message);
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_ACTIVITY_TARGET, new ActivityTarget(ActivityTarget.GOAL_TYPE_STEPS, activityUser.getStepsGoal())));
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_USER_UNIT, new UserUnit(UserUnitConstants.DISTANCE, getUnit())));
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_USER_UNIT, new UserUnit(UserUnitConstants.CLOCK_MODE, getTimeMode())));
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_ALARM_SETTINGS));
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_SCREEN_SETTINGS), new ScreenSettingsHandler(this));
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_MULTI_ALARM), new GetAlarmHandler(this));
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_ALARM_ENABLED));
                addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_WORKOUT_SCREEN_LIST), new WorkoutScreenListHandler(this));
                addExtraSyncCommands();
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(getLastSyncTimestamp());
                message = new WithingsMessage(WithingsMessageType.GET_ACTIVITY_SAMPLES, ExpectedResponse.EOT);
                message.addDataStructure(new GetActivitySamples(c.getTimeInMillis() / 1000, (short) 0));
                addSimpleConversationToQueue(message, activitySampleHandler);
                message = new WithingsMessage(WithingsMessageType.GET_MOVEMENT_SAMPLES, ExpectedResponse.EOT);
                message.addDataStructure(new GetActivitySamples(c.getTimeInMillis() / 1000, (short) 0));
                message.addDataStructure(new TypeVersion());
                addSimpleConversationToQueue(message, activitySampleHandler);
                message = new WithingsMessage(WithingsMessageType.GET_HEARTRATE_SAMPLES, ExpectedResponse.EOT);
                message.addDataStructure(new GetActivitySamples(c.getTimeInMillis() / 1000, (short) 0));
                message.addDataStructure(new TypeVersion());
                addSimpleConversationToQueue(message, activitySampleHandler);
            }
        } catch (Exception e) {
            logger.error("Could not synchronize! ", e);
            conversationQueue.clear();
        } finally {
            // This must be done in all cases or the watch won't respond anymore!
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SYNC_OK), new SyncFinishedHandler(this));
        }
        conversationQueue.send();
    }


    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           byte[] data) {
        if (super.onCharacteristicChanged(gatt, characteristic, data)) {
            return true;
        }

        logger.debug("onCharacteristicChanged raw: {}", nodomain.freeyourgadget.gadgetbridge.util.GB.hexdump(data));
        boolean complete = messageBuilder.buildMessage(data);
        if (complete) {
            Message message = messageBuilder.getMessage();
            if (message.isIncomingMessage()) {
                logger.debug("received incoming message: {}", message.getType());
                IncomingMessageHandler handler = incomingMessageHandlerFactory.getHandler(message);
                if (handler == null) {
                    logger.warn("No handler for incoming message type={}", message.getType());
                } else {
                    handler.handleMessage(message);
                }
            } else {
                logger.debug("received response message: type={} (0x{})", message.getType(), Integer.toHexString(message.getType() & 0xffff));
                conversationQueue.processResponse(message);
            }
        }

        return true;
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        if (callSpec.command == CallSpec.CALL_INCOMING) {
            NotificationSpec notificationSpec = new NotificationSpec();
            notificationSpec.sourceAppId = "incoming.call";
            notificationSpec.title = callSpec.number;
            notificationSpec.sender = callSpec.name;
            notificationSpec.type = NotificationType.GENERIC_PHONE;
            notificationProvider.notifyClient(notificationSpec);
        } else {
            logger.info("Received yet unhandled call command: " + callSpec.command);
        }
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        notificationProvider.notifyClient(notificationSpec);
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        if (alarms.size() == 0) {
            return;
        }

        conversationQueue.clear();
        addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_ALARM));

        // Build a single CMD_SET_MULTI_ALARM message containing ALL enabled alarms.
        // The watch replaces its entire alarm list with the contents of this message,
        // so sending one message per alarm would cause only the last one to survive.
        Message multiAlarmMessage = new WithingsMessage(WithingsMessageType.SET_ALARM);
        boolean anyAlarmEnabled = false;

        for (Alarm alarm : alarms) {
            if (alarm.getEnabled() && !alarm.getUnused()) {
                anyAlarmEnabled = true;
                addAlarmToMessage(multiAlarmMessage, alarm);
            }
        }

        if (anyAlarmEnabled) {
            addSimpleConversationToQueue(multiAlarmMessage);
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_ALARM_ENABLED, new AlarmStatus(true)));
        } else {
            // Send an empty SET_ALARM to clear all alarms, then disable
            addSimpleConversationToQueue(multiAlarmMessage);
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_ALARM_ENABLED, new AlarmStatus(false)));
        }

        conversationQueue.send();
    }

    @Override
    public boolean onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        this.device = device;
        return true;
    }

    @Override
    public boolean onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        if (characteristic.getUuid().equals(getWithingsUUIDs().CONTROL_POINT_CHARACTERISTIC_UUID)) {
            logger.debug("Got GetNotificationAttributesRequest: " + GB.hexdump(value));
            GetNotificationAttributes request = new GetNotificationAttributes();
            request.deserialize(value);
            notificationProvider.handleNotificationAttributeRequest(request);
        }

        return true;
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        doSync();
    }

    @Override
    public void onHeartRateTest() {
        conversationQueue.clear();
        addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_HR), new HeartRateHandler(this));
        conversationQueue.send();
    }

    @Override
    public void onSendConfiguration(String config) {
        try {
            switch (config) {
                case HuamiConst.PREF_WORKOUT_ACTIVITY_TYPES_SORTABLE:
                    setWorkoutActivityTypes();
                    break;
                case PREF_LANGUAGE:
                    setLanguage();
                    break;
                case ActivityUser.PREF_USER_STEPS_GOAL:
                    sendStepsGoal();
                    break;
                default:
                    if (!handleExtraConfiguration(config)) {
                        logger.debug("unknown configuration setting received: " + config);
                    }
            }
        } catch (Exception e) {
            GB.toast("Error setting configuration", Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    private void sendStepsGoal() {
        ActivityUser user = new ActivityUser();
        conversationQueue.clear();
        addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_ACTIVITY_TARGET,
                new ActivityTarget(ActivityTarget.GOAL_TYPE_STEPS, user.getStepsGoal())));
        conversationQueue.send();
    }

    /**
     * Called from {@link #onSendConfiguration(String)} for device-specific configuration keys
     * not handled by the base class.
     *
     * @param config the configuration key
     * @return {@code true} if the config was handled, {@code false} otherwise
     */
    protected boolean handleExtraConfiguration(String config) {
        return false;
    }

    @Override
    public void onTestNewFunction() {
        String hexMessage = "0105080015050900111006040102030507000000000000000000";
        conversationQueue.clear();
        addSimpleConversationToQueue(new SimpleHexToByteMessage(hexMessage));
        conversationQueue.send();
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    public void sendToDevice(Message message) {
        if (message == null) {
            return;
        }

        try {
            TransactionBuilder builder = createTransactionBuilder("conversation");
            builder.setCallback(this);
            BluetoothGattCharacteristic characteristic = getCharacteristic(getWithingsUUIDs().WITHINGS_WRITE_CHARACTERISTIC_UUID);
            if (characteristic == null) {
                logger.info("Characteristic with UUID " + getWithingsUUIDs().WITHINGS_WRITE_CHARACTERISTIC_UUID + " not found.");
                return;
            }

            byte[] rawData = message.getRawData();
            builder.writeChunkedData(characteristic, rawData, getMTU() - 3);
            builder.queue();
        } catch (Exception e) {
            logger.warn("Could not send message because of " + e.getMessage());
        }
    }

    public void sendAncsNotificationSourceNotification(NotificationSource notificationSource) {
        try {
            ServerTransactionBuilder builder = performServer("notificationSourceNotification");
            byte[] data = notificationSource.serialize();
            builder.notifyCharacteristicChanged(device, notificationSourceCharacteristic, data);
            builder.queue(getQueue());
        } catch (IOException e) {
            logger.error("Could not send notification.", e);
            GB.toast("Could not send notification.", Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    public void sendAncsDataSourceNotification(GetNotificationAttributesResponse response) {
        try {
            ServerTransactionBuilder builder = performServer("dataSourceNotification");
            byte[] data = response.serialize();
            builder.notifyCharacteristicChanged(device, dataSourceCharacteristic, data);
            builder.queue(getQueue());
        } catch (IOException e) {
            logger.error("Could not send notification.", e);
            GB.toast("Could not send notification.", Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    public void finishInitialization() {
        TransactionBuilder builder = createTransactionBuilder("setupFinished");
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        builder.queue();
        logger.debug("Finished initialization.");
    }

    public void finishSync() {
        syncInProgress = false;
        if (getDevice().isBusy()) {
            getDevice().unsetBusyTask();
            getDevice().sendDeviceUpdateIntent(getContext());
        }
        activitySampleHandler.onSyncFinished();
        saveLastSyncTimestamp(new Date().getTime());
    }

    void onAuthenticationFinished() {
        if (!firstTimeConnect) {
            doSync();
        } else {
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_ANCS_STATUS, new AncsStatus(true)));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_ANCS_STATUS));
            addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.GET_BATTERY_STATUS), new BatteryStateHandler(this));
            conversationQueue.send();
        }
    }

    private void addAlarmToMessage(Message multiAlarmMessage, Alarm alarm) {
        AlarmSettings alarmSettings = new AlarmSettings();
        alarmSettings.setHour((short) alarm.getHour());
        alarmSettings.setMinute((short) alarm.getMinute());
        alarmSettings.setDayOfWeek(mapRepetitionToWithingsValue(alarm));

        if (!alarm.isRepetitive()) {
            // One-time alarm: set the target date so the watch knows when to fire.
            // Without a date, flags=0x80 + date=00/00/00 is ambiguous and the watch
            // may ignore the alarm entirely.
            Calendar cal = AlarmUtils.toCalendar(alarm);
            alarmSettings.setDayOfMonth((short) cal.get(Calendar.DAY_OF_MONTH));
            alarmSettings.setMonth((short) (cal.get(Calendar.MONTH) + 1)); // Calendar.MONTH is 0-based
            alarmSettings.setYear((short) (cal.get(Calendar.YEAR) - 2000));
        }

        if (alarm.getSmartWakeup()) {
            final Integer interval = alarm.getSmartWakeupInterval();
            final int maxInterval = gbDevice.getDeviceCoordinator().getSmartWakeupMaxInterval(gbDevice);
            short minutes;
            if (interval != null && interval > 0) {
                minutes = (short) Math.min(interval, maxInterval);
            } else {
                // Fall back to a sensible default (20 min) if not configured
                minutes = 20;
            }
            alarmSettings.setSmartWakeupMinutes(minutes);
        }

        multiAlarmMessage.addDataStructure(alarmSettings);
        if (!StringUtils.isEmpty(alarm.getTitle())) {
            AlarmName alarmName = new AlarmName(alarm.getTitle());
            multiAlarmMessage.addDataStructure(alarmName);
        }
    }

    private short mapRepetitionToWithingsValue(Alarm alarm) {
        int repetition = 0;
        if (alarm.getRepetition(Alarm.ALARM_MON)) {
            repetition += 0x02;
        }
        if (alarm.getRepetition(Alarm.ALARM_TUE)) {
            repetition += 0x04;
        }
        if (alarm.getRepetition(Alarm.ALARM_WED)) {
            repetition += 0x08;
        }
        if (alarm.getRepetition(Alarm.ALARM_THU)) {
            repetition += 0x10;
        }
        if (alarm.getRepetition(Alarm.ALARM_FRI)) {
            repetition += 0x20;
        }
        if (alarm.getRepetition(Alarm.ALARM_SAT)) {
            repetition += 0x40;
        }
        if (alarm.getRepetition(Alarm.ALARM_SUN)) {
            repetition += 0x01;
        }

        return (short)(repetition + 0x80);
    }

    private void addANCSService() {
        BluetoothGattService withingsGATTService = new BluetoothGattService(getWithingsUUIDs().WITHINGS_ANCS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        notificationSourceCharacteristic = new BluetoothGattCharacteristic(getWithingsUUIDs().NOTIFICATION_SOURCE_CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        notificationSourceCharacteristic.addDescriptor(new BluetoothGattDescriptor(getWithingsUUIDs().CCC_DESCRIPTOR_UUID, BluetoothGattCharacteristic.PERMISSION_WRITE));
        withingsGATTService.addCharacteristic(notificationSourceCharacteristic);
        withingsGATTService.addCharacteristic(new BluetoothGattCharacteristic(getWithingsUUIDs().CONTROL_POINT_CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE));
        dataSourceCharacteristic = new BluetoothGattCharacteristic(getWithingsUUIDs().DATA_SOURCE_CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        dataSourceCharacteristic.addDescriptor(new BluetoothGattDescriptor(getWithingsUUIDs().CCC_DESCRIPTOR_UUID, BluetoothGattCharacteristic.PERMISSION_WRITE));
        withingsGATTService.addCharacteristic(dataSourceCharacteristic);
        addSupportedServerService(withingsGATTService);
    }

    protected void addSimpleConversationToQueue(Message message) {
        addSimpleConversationToQueue(message, null);
    }

    protected void addSimpleConversationToQueue(Message message, ResponseHandler handler) {
        Conversation conversation = new SimpleConversation(handler);
        conversation.setRequest(message);
        conversationQueue.addConversation(conversation);
    }

    /** Clears any pending conversations from the queue. */
    protected void clearQueue() {
        conversationQueue.clear();
    }

    /** Starts sending the queued conversations to the device. */
    protected void sendQueue() {
        conversationQueue.send();
    }

    private void saveLastSyncTimestamp(@NonNull long timestamp) {
        SharedPreferences.Editor editor = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit();
        editor.putLong(LAST_ACTIVITY_SYNC, timestamp);
        editor.apply();
    }

    private long getLastSyncTimestamp() {
        SharedPreferences settings = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        long lastSyncTime =  settings.getLong(LAST_ACTIVITY_SYNC, 0);
        if (lastSyncTime > 0) {
            return lastSyncTime;
        } else {
            Date currentDate = new Date();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(currentDate.getTime());
            c.add(Calendar.HOUR, - 10);
            return c.getTimeInMillis();
        }
    }

    private boolean shoudSync() {
        long lastSynced = getLastSyncTimestamp();
        int minuteInMillis = 60 * 1000;
        return new Date().getTime() - lastSynced > minuteInMillis;
    }

    private User getUser() {
        User user = new User();
        ActivityUser activityUser = new ActivityUser();
        user.setName(activityUser.getName());
        user.setGender((byte) activityUser.getGender());
        user.setHeight(activityUser.getHeightCm());
        user.setWeight(activityUser.getWeightKg());
        user.setBirthdate(activityUser.getUserBirthday());
        return user;
    }

    protected void addScreenListCommands() {
        // Default screen list used by the Withings Steel HR.
        // Subclasses (e.g. WithingsScanwatchDeviceSupport) should override this
        // to provide a device-specific or user-configurable screen list.
        // Screen IDs are defined in WithingsScreenId; idOnDevice is the slot/position on the watch.
        // TODO: these IDs need to be verified via BLE packet capture (HCI snoop log).
        Message message = new WithingsMessage(WithingsMessageType.SET_SCREEN_LIST);
        message.addDataStructure(buildScreen(WithingsScreenId.NOTIFICATIONS,   (byte) 6));
        message.addDataStructure(buildScreen(WithingsScreenId.HEART_RATE_STEEL,(byte) 1));
        message.addDataStructure(buildScreen(WithingsScreenId.ACTIVITY,        (byte) 4));
        message.addDataStructure(buildScreen(WithingsScreenId.CALORIES_STEEL,  (byte) 2));
        message.addDataStructure(buildScreen(WithingsScreenId.ALARM,           (byte) 3));
        message.addDataStructure(buildScreen(WithingsScreenId.CONFIG,          (byte) 7));
        message.addDataStructure(buildScreen(WithingsScreenId.CHRONOMETER,     (byte) 9));
        message.addDataStructure(new EndOfTransmission());
        addSimpleConversationToQueue(message);
    }

    /**
     * Convenience method: creates a {@link ScreenSettings} with the given screen ID and slot.
     */
    protected ScreenSettings buildScreen(int screenId, byte slotOnDevice) {
        ScreenSettings settings = new ScreenSettings();
        settings.setId(screenId);
        settings.setIdOnDevice(slotOnDevice);
        return settings;
    }

    /**
     * Hook for subclasses to add device-specific commands to the sync queue.
     * Called within the {@code shoudSync()} block of {@link #doSync()}, after the common
     * commands (user, alarms, screen settings, workout screens) have been queued.
     *
     * <p>The default implementation is a no-op.
     */
    protected void addExtraSyncCommands() {
        // no-op by default
    }

    private void setWorkoutActivityTypes() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());

        final List<String> allActivityTypes = Arrays.asList(getContext().getResources().getStringArray(R.array.pref_withings_steel_activity_types_values));
        final List<String> defaultActivityTypes = Arrays.asList(getContext().getResources().getStringArray(R.array.pref_withings_steel_activity_types_default));
        final String activityTypesPref = prefs.getString("workout_activity_types_sortable", null);

        final List<String> enabledActivityTypes;
        if (activityTypesPref == null || activityTypesPref.equals("")) {
            enabledActivityTypes = defaultActivityTypes;
        } else {
            enabledActivityTypes = Arrays.asList(activityTypesPref.split(","));
        }

        conversationQueue.clear();
        for (int i = 0; i < enabledActivityTypes.size(); i++) {
            String workoutType = enabledActivityTypes.get(i);
            try {
                Message message = createWorkoutScreenMessage(workoutType);
                if (i == enabledActivityTypes.size() - 1) {
                    message.addDataStructure(new EndOfTransmission());
                }
                addSimpleConversationToQueue(message);
            } catch (Exception e) {
                logger.warn("exception in setWorkoutActivityTypes", e);
            }
        }

        conversationQueue.send();
    }

    @NonNull
    protected Message createWorkoutScreenMessage(String workoutType) {
        WithingsActivityType withingsActivityType = WithingsActivityType.fromPrefValue(workoutType);
        int code = withingsActivityType.getCode();
        Message message = new WithingsMessage(WithingsMessageType.SET_WORKOUT_SCREEN, ExpectedResponse.NONE);
        WorkoutScreen workoutScreen = new WorkoutScreen();
        workoutScreen.setId(code);
        final int stringId = getContext().getResources().getIdentifier("activity_type_" + workoutType, "string", getContext().getPackageName());
        workoutScreen.setName(getContext().getString(stringId));
        message.addDataStructure(workoutScreen);

        ImageMetaData imageMetaData = new ImageMetaData();
        imageMetaData.setHeight((byte)24);
        imageMetaData.setWidth((byte)22);
        message.addDataStructure(imageMetaData);

        ImageData imageData = new ImageData();
        final int drawableId = withingsActivityType.toActivityKind().getIcon();
        Drawable drawable = getContext().getDrawable(drawableId);
        imageData.setImageData(IconHelper.getIconBytesFromDrawable(drawable));
        message.addDataStructure(imageData);

        return message;
    }

    protected void setLanguage() {
        Locale locale = getLocale();

        conversationQueue.clear();
        addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_LOCALE, locale));
        conversationQueue.send();
    }

    private Locale getLocale() {
        String localeString = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress())
                .getString(PREF_LANGUAGE, PREF_LANGUAGE_AUTO);

        if (localeString.equals(PREF_LANGUAGE_AUTO)) {
            localeString = java.util.Locale.getDefault().getLanguage();
        }

        String language = localeString.substring(0, 2);
        switch (language) {
            case "de":
            case "en":
            case "es":
            case "fr":
            case "it":
                return new Locale(language);
            default:
                return new Locale("en");
        }
    }

    private short getTimeMode() {
        if ("24h".equals(getDevicePrefs().getTimeFormat())) {
            return UserUnitConstants.UNIT_24H;
        } else {
            return UserUnitConstants.UNIT_12H;
        }
    }

    private short getUnit() {
        String units = GBApplication.getPrefs().getString(SettingsActivity.PREF_MEASUREMENT_SYSTEM, GBApplication.getContext().getString(R.string.p_unit_metric));

        if (units.equals(GBApplication.getContext().getString(R.string.p_unit_metric))) {
            return UserUnitConstants.UNIT_KM;
        } else {
            return UserUnitConstants.UNIT_MILES;
        }
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    public void onSetTime() {
        conversationQueue.clear();
        addSimpleConversationToQueue(new WithingsMessage(WithingsMessageType.SET_TIME, new Time()));
        conversationQueue.send();
    }
}
