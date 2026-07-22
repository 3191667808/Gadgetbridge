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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GlucoseSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.ycbt.YcbtConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.ycbt.YcbtActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSample;
import nodomain.freeyourgadget.gadgetbridge.entities.YcbtActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BleNamesResolver;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.NotifyAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.WriteAction;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.RealtimeSamplesAggregator;

public class YcbtDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(YcbtDeviceSupport.class);
    private static final long BLOOD_PRESSURE_RESULT_TIMEOUT_MILLIS = 60_000L;
    private static final long BLOOD_PRESSURE_STOP_REPLY_TIMEOUT_MILLIS = 10_000L;
    private static final long HEART_RATE_RESULT_TIMEOUT_MILLIS = 60_000L;
    private static final long SPO2_RESULT_TIMEOUT_MILLIS = 60_000L;
    private static final long MEASUREMENT_CONTROL_REPLY_TIMEOUT_MILLIS = 10_000L;
    private static final long MEASUREMENT_REPLY_QUARANTINE_MILLIS = 10_000L;
    private static final long SESSION_REPLY_TIMEOUT_MILLIS = 5_000L;
    private static final long HISTORY_WATCHDOG_INTERVAL_MILLIS = 1_000L;
    private static final long HISTORY_MAX_FUTURE_MILLIS = 60L * 60L * 1_000L;
    private static final int HISTORY_GROUP = 0x05;
    private static final int SLEEP_STAGE_UNKNOWN = 0;
    private static final int SLEEP_STAGE_DEEP = 1;
    private static final int SLEEP_STAGE_LIGHT = 2;
    private static final int SLEEP_STAGE_AWAKE = 3;
    private static final int SLEEP_STAGE_REM = 4;
    private static final int DEFAULT_AUTOMATIC_MONITORING_INTERVAL_SECONDS = 30 * 60;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler sessionTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler historyTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler heartRateTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler spo2TimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler heartRateControlTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler spo2ControlTimeoutHandler = new Handler(Looper.getMainLooper());
    private final YcbtBloodPressureOperation bloodPressureOperation = new YcbtBloodPressureOperation();
    private final YcbtSessionNegotiation sessionNegotiation = new YcbtSessionNegotiation(2);
    private final YcbtHistoryTransfer historyTransfer = new YcbtHistoryTransfer();
    private YcbtInboundRouter inboundRouter = new YcbtInboundRouter();
    private volatile Boolean bloodPressureSupported;
    private volatile YcbtProtocol.Capabilities capabilities;
    private long bloodPressureTimeoutGeneration;
    private long sessionTimeoutGeneration;
    private long historyTimeoutGeneration;
    private long heartRateTimeoutGeneration;
    private long spo2TimeoutGeneration;
    private long heartRateControlTimeoutGeneration;
    private long spo2ControlTimeoutGeneration;
    private long measurementReplyQuarantineUntilMillis;
    private boolean historyFetchActive;
    private boolean manualHeartRateActive;
    private boolean manualHeartRateResultReceived;
    private boolean realtimeHeartRateEnabled;
    private Boolean deferredRealtimeHeartRateEnabled;
    private boolean manualSpo2Active;
    private boolean manualSpo2ResultReceived;
    private boolean mayReceiveLateHeartRateStartReply;
    private boolean mayReceiveLateSpo2StartReply;
    private volatile HeartRateControl pendingHeartRateControl = HeartRateControl.NONE;
    private volatile Spo2Control pendingSpo2Control = Spo2Control.NONE;
    private RealtimeSamplesAggregator realtimeSamplesAggregator;

    private enum BloodPressureCommand {
        START,
        STOP,
        CLEANUP_STOP
    }

    private enum HeartRateControl {
        NONE,
        MANUAL_START,
        MANUAL_STOP,
        REALTIME_START,
        REALTIME_STOP
    }

    private enum Spo2Control {
        NONE,
        START,
        STOP
    }

    enum RealtimeHeartRateRequestAction {
        IGNORE,
        DEFER,
        START,
        STOP
    }

    enum LiveVitalsFrameRoute {
        VITALS,
        BLOOD_PRESSURE
    }

    static final class RealtimeHeartRateRequest {
        private final RealtimeHeartRateRequestAction action;
        private final Boolean deferredTarget;

        private RealtimeHeartRateRequest(final RealtimeHeartRateRequestAction action,
                                         final Boolean deferredTarget) {
            this.action = action;
            this.deferredTarget = deferredTarget;
        }

        RealtimeHeartRateRequestAction getAction() {
            return action;
        }

        Boolean getDeferredTarget() {
            return deferredTarget;
        }
    }

    public YcbtDeviceSupport() {
        super(LOG);
        addSupportedService(YcbtConstants.SERVICE_UUID);
    }

    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt) {
        final List<BluetoothGattService> services = gatt.getServices();
        final List<YcbtGattInventory.Service> serviceMetadata = new ArrayList<>();
        final int shownServiceCount = Math.min(services.size(), YcbtGattInventory.MAX_SERVICES);
        for (int serviceIndex = 0; serviceIndex < shownServiceCount; serviceIndex++) {
            final BluetoothGattService service = services.get(serviceIndex);
            final List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            final List<YcbtGattInventory.Characteristic> characteristicMetadata = new ArrayList<>();
            final int shownCharacteristicCount = Math.min(
                    characteristics.size(),
                    YcbtGattInventory.MAX_CHARACTERISTICS_PER_SERVICE
            );
            for (int characteristicIndex = 0; characteristicIndex < shownCharacteristicCount; characteristicIndex++) {
                final BluetoothGattCharacteristic characteristic = characteristics.get(characteristicIndex);
                final List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
                final List<UUID> descriptorUuids = new ArrayList<>();
                final int shownDescriptorCount = Math.min(
                        descriptors.size(),
                        YcbtGattInventory.MAX_DESCRIPTORS_PER_CHARACTERISTIC
                );
                for (int descriptorIndex = 0; descriptorIndex < shownDescriptorCount; descriptorIndex++) {
                    descriptorUuids.add(descriptors.get(descriptorIndex).getUuid());
                }
                characteristicMetadata.add(new YcbtGattInventory.Characteristic(
                        characteristic.getUuid(),
                        characteristic.getProperties(),
                        BleNamesResolver.getCharacteristicPropertyString(characteristic.getProperties()),
                        descriptors.size(),
                        descriptorUuids
                ));
            }
            serviceMetadata.add(new YcbtGattInventory.Service(
                    service.getUuid(),
                    serviceType(service.getType()),
                    characteristics.size(),
                    characteristicMetadata
            ));
        }

        final BluetoothGattService configuredService = gatt.getService(YcbtConstants.SERVICE_UUID);
        final Integer configuredServiceCharacteristicCount = configuredService == null
                ? null
                : configuredService.getCharacteristics().size();
        final YcbtGattInventory.Inventory inventory = new YcbtGattInventory.Inventory(
                services.size(),
                serviceMetadata,
                configuredServiceCharacteristicCount
        );
        for (final String event : YcbtGattInventory.format(inventory, YcbtConstants.SERVICE_UUID)) {
            diagnostic(YcbtDiagnostics.TYPE_STAGE, event);
        }

        super.onServicesDiscovered(gatt);
    }

    @Override
    protected TransactionBuilder initializeDevice(@NonNull final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        inboundRouter = new YcbtInboundRouter();
        bloodPressureSupported = null;
        capabilities = null;
        cancelSessionTimeout();
        sessionNegotiation.begin();
        cancelHistoryFetch(false);
        cancelBloodPressureTimeout();
        bloodPressureOperation.cancel();
        cancelHeartRateMeasurement(false);
        cancelSpo2Measurement(false);
        realtimeHeartRateEnabled = false;
        deferredRealtimeHeartRateEnabled = null;
        measurementReplyQuarantineUntilMillis = 0;
        realtimeSamplesAggregator = new RealtimeSamplesAggregator(getContext(), getDevice());
        diagnostic(YcbtDiagnostics.TYPE_INITIALIZE_ENTERED, "initialize entered");

        final List<BluetoothGattCharacteristic> indicationCharacteristics = new ArrayList<>(2);
        boolean allCharacteristicsUsable = true;
        for (final UUID characteristicUuid : YcbtInboundRouter.getInboundCharacteristicUuids()) {
            final BluetoothGattCharacteristic characteristic = getCharacteristic(characteristicUuid);
            if (!isUsableInboundCharacteristic(characteristic, characteristicUuid)) {
                allCharacteristicsUsable = false;
            } else {
                indicationCharacteristics.add(characteristic);
            }
        }
        if (!allCharacteristicsUsable) {
            LOG.error("YCBT initialization failed: service {} or required transport characteristics are missing or invalid",
                    YcbtConstants.SERVICE_UUID);
            builder.setDeviceState(GBDevice.State.NOT_CONNECTED);
            builder.run(this::disconnect);
            return builder;
        }

        for (final BluetoothGattCharacteristic characteristic : indicationCharacteristics) {
            builder.add(new YcbtIndicateAction(characteristic));
        }
        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        builder.run(() -> diagnostic(
                YcbtDiagnostics.TYPE_STAGE,
                "model probe write request=020308004750ef20"
        ));
        builder.run(this::scheduleSessionTimeout);
        builder.write(commandCharacteristic, YcbtProtocol.buildModelRequest());
        return builder;
    }

    private boolean isUsableInboundCharacteristic(final BluetoothGattCharacteristic characteristic,
                                                   final UUID expectedUuid) {
        final String characteristicName = characteristicName(expectedUuid);
        if (characteristic == null) {
            LOG.error("YCBT characteristic {} was not discovered under service {}", expectedUuid, YcbtConstants.SERVICE_UUID);
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " missing");
            return false;
        }
        diagnostic(YcbtDiagnostics.TYPE_STAGE, characteristicName + " found");

        final int properties = characteristic.getProperties();
        diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                Locale.ROOT,
                "%s properties=0x%08x",
                characteristicName,
                properties
        ));
        if (!supportsIndications(properties)) {
            LOG.error("YCBT characteristic {} does not advertise indication support; properties=0x{}",
                    expectedUuid, Integer.toHexString(properties));
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " indication property missing");
            return false;
        }
        if (YcbtConstants.WRITE_CHARACTERISTIC_UUID.equals(expectedUuid)
                && !supportsWriteWithoutResponse(properties)) {
            LOG.error("YCBT command characteristic {} does not advertise write-without-response support; properties=0x{}",
                    expectedUuid, Integer.toHexString(properties));
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " writeNoResponse property missing");
            return false;
        }

        final BluetoothGattDescriptor cccd = characteristic.getDescriptor(
                GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION
        );
        if (cccd == null) {
            LOG.error("YCBT characteristic {} has no CCCD", expectedUuid);
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " CCCD missing");
            return false;
        }
        diagnostic(YcbtDiagnostics.TYPE_STAGE, characteristicName + " CCCD present");
        return true;
    }

    static boolean supportsIndications(final int properties) {
        return (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }

    static boolean supportsWriteWithoutResponse(final int properties) {
        return (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }

    private final class YcbtIndicateAction extends BtLEAction {
        private boolean descriptorWriteStarted;

        private YcbtIndicateAction(final BluetoothGattCharacteristic characteristic) {
            super(characteristic);
        }

        @Override
        @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
        public boolean run(@NonNull final BluetoothGatt gatt) {
            descriptorWriteStarted = false;
            final String characteristicName = characteristicName(getCharacteristic().getUuid());
            diagnostic(YcbtDiagnostics.TYPE_STAGE, characteristicName + " forced indication start");
            if (gatt.setCharacteristicNotification(getCharacteristic(), true)) {
                final BluetoothGattDescriptor cccd = getCharacteristic().getDescriptor(
                        GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION
                );
                descriptorWriteStarted = NotifyAction.writeDescriptor(
                        gatt,
                        cccd,
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                );
            }
            diagnostic(
                    descriptorWriteStarted ? YcbtDiagnostics.TYPE_STAGE : YcbtDiagnostics.TYPE_FAILURE,
                    characteristicName + " forced indication synchronous result=" + descriptorWriteStarted
            );

            if (!descriptorWriteStarted) {
                final UUID characteristicUuid = getCharacteristic().getUuid();
                LOG.error("Failed to start YCBT indication setup on {}", characteristicUuid);
                getDevice().setUpdateState(GBDevice.State.NOT_CONNECTED, getContext());
                disconnect();
            }
            return descriptorWriteStarted;
        }

        @Override
        public boolean expectsResult() {
            return descriptorWriteStarted;
        }
    }

    private final class YcbtBloodPressureWriteAction extends BtLEAction {
        private final BloodPressureCommand command;
        private final byte[] request;
        private final String diagnosticMessage;
        private final long replyTimeoutMillis;
        private boolean writeStarted;

        private YcbtBloodPressureWriteAction(final BluetoothGattCharacteristic characteristic,
                                             final BloodPressureCommand command,
                                             final byte[] request,
                                             final String diagnosticMessage,
                                             final long replyTimeoutMillis) {
            super(characteristic);
            this.command = command;
            this.request = request;
            this.diagnosticMessage = diagnosticMessage;
            this.replyTimeoutMillis = replyTimeoutMillis;
        }

        @Override
        @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
        public boolean run(@NonNull final BluetoothGatt gatt) {
            boolean disconnectAfterFailure = false;
            synchronized (ConnectionMonitor) {
                final boolean current;
                switch (command) {
                    case START:
                        current = bloodPressureOperation.markStartRequested();
                        break;
                    case STOP:
                        current = bloodPressureOperation.markStopRequested();
                        break;
                    case CLEANUP_STOP:
                        current = bloodPressureOperation.markCleanupStopRequested();
                        break;
                    default:
                        throw new IllegalStateException("Unexpected blood pressure command " + command);
                }
                if (!current) {
                    diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                            Locale.ROOT,
                            "blood pressure %s write skipped state=%s",
                            command,
                            bloodPressureOperation.getState()
                    ));
                    return true;
                }
                diagnostic(YcbtDiagnostics.TYPE_STAGE, diagnosticMessage);
                scheduleBloodPressureTimeout(replyTimeoutMillis);
                writeStarted = WriteAction.writeCharacteristic(gatt, getCharacteristic(), request);
                if (!writeStarted) {
                    cancelBloodPressureTimeout();
                    bloodPressureOperation.cancel();
                    diagnostic(YcbtDiagnostics.TYPE_FAILURE,
                            "blood pressure " + command + " write failed synchronously");
                    disconnectAfterFailure = true;
                }
            }
            if (disconnectAfterFailure) {
                disconnect();
            }
            return writeStarted;
        }

        @Override
        public boolean expectsResult() {
            return writeStarted;
        }
    }

    @Override
    public boolean onDescriptorWrite(final BluetoothGatt gatt,
                                     final BluetoothGattDescriptor descriptor,
                                     final int status) {
        final boolean handledByParent = super.onDescriptorWrite(gatt, descriptor, status);
        if (!GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION.equals(descriptor.getUuid())
                || !inboundRouter.accepts(descriptor.getCharacteristic().getUuid())) {
            return handledByParent;
        }

        final UUID characteristicUuid = descriptor.getCharacteristic().getUuid();
        diagnostic(
                status == BluetoothGatt.GATT_SUCCESS ? YcbtDiagnostics.TYPE_STAGE : YcbtDiagnostics.TYPE_FAILURE,
                characteristicName(characteristicUuid) + " descriptor callback status=" + status
        );
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.error("Failed to enable YCBT indications on {}: GATT status {}", characteristicUuid, status);
            getDevice().setUpdateState(GBDevice.State.NOT_CONNECTED, getContext());
            disconnect();
        } else {
            LOG.info("Enabled YCBT indications on {}", characteristicUuid);
        }
        return true;
    }

    @Override
    public boolean onCharacteristicWrite(final BluetoothGatt gatt,
                                         final BluetoothGattCharacteristic characteristic,
                                         final int status) {
        if (YcbtConstants.WRITE_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
            diagnostic(
                    status == BluetoothGatt.GATT_SUCCESS ? YcbtDiagnostics.TYPE_STAGE : YcbtDiagnostics.TYPE_FAILURE,
                    "command write callback status=" + status
            );
        }
        final boolean handledByParent = super.onCharacteristicWrite(gatt, characteristic, status);
        return handledByParent || YcbtConstants.WRITE_CHARACTERISTIC_UUID.equals(characteristic.getUuid());
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                            final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }

        final UUID characteristicUuid = characteristic.getUuid();
        final YcbtInboundRouter.RouteResult result;
        try {
            result = inboundRouter.accept(characteristicUuid, value);
        } catch (final RuntimeException e) {
            LOG.error("Unexpected YCBT frame reassembly failure from {}", characteristicUuid, e);
            diagnostic(YcbtDiagnostics.TYPE_STAGE,
                    characteristicName(characteristicUuid) + " malformed inbound reason=unexpected reassembly failure");
            return inboundRouter.accepts(characteristicUuid);
        }
        if (!result.isAccepted()) {
            return false;
        }

        if (result.getMalformedReason() != null) {
            LOG.warn("Malformed YCBT chunk from {} (length={}): {}",
                    characteristicUuid, value == null ? 0 : value.length, result.getMalformedReason());
            diagnostic(YcbtDiagnostics.TYPE_STAGE,
                    characteristicName(characteristicUuid) + " malformed inbound reason=" + result.getMalformedReason());
        }
        for (final YcbtFrameCodec.Frame frame : result.getFrames()) {
            if (frame.getGroup() == HISTORY_GROUP) {
                handleHistoryFrame(frame);
                continue;
            }
            final String model = YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseModelResponse(frame)
                    : null;
            if (model != null) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "model probe response=" + model);
                handleSessionModel(model);
            }
            final Integer batteryLevel = YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseBatteryLevel(frame)
                    : null;
            if (batteryLevel != null) {
                final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
                batteryInfo.level = batteryLevel;
                handleGBDeviceEvent(batteryInfo);
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "battery query response=" + batteryLevel + "%");
                handleSessionBattery(batteryLevel);
            }
            final YcbtProtocol.Capabilities capabilities = YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(
                    characteristicUuid
            ) ? YcbtProtocol.parseCapabilities(frame) : null;
            if (capabilities != null) {
                this.capabilities = capabilities;
                bloodPressureSupported = capabilities.hasManualBloodPressure();
                persistCapabilities(capabilities);
                diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                        Locale.ROOT,
                        "capability query response steps=%s sleep=%s heartRate=%s bloodPressure=%s spo2=%s hrv=%s "
                                + "temperature=%s findDevice=%s bloodSugar=%s stress=%s",
                        capabilities.hasSteps(),
                        capabilities.hasSleep(),
                        capabilities.hasHeartRate(),
                        capabilities.hasBloodPressure(),
                        capabilities.hasSpo2(),
                        capabilities.hasHrv(),
                        capabilities.hasTemperature(),
                        capabilities.hasFindDevice(),
                        capabilities.hasBloodSugar(),
                        capabilities.hasStress()
                ));
                handleSessionCapabilities();
            }
            final Integer liveBattery = YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseLiveBattery(frame)
                    : null;
            if (liveBattery != null) {
                final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
                batteryInfo.level = liveBattery;
                handleGBDeviceEvent(batteryInfo);
            }
            final Integer liveHeartRate = YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseLiveHeartRate(frame)
                    : null;
            if (liveHeartRate != null) {
                persistLiveMeasurements(liveHeartRate, 0, 0, 0);
                handleLiveHeartRate(liveHeartRate);
            }
            final Integer liveSpo2 = YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseLiveSpo2(frame)
                    : null;
            if (liveSpo2 != null) {
                persistLiveMeasurements(0, 0, liveSpo2, 0);
                handleLiveSpo2(liveSpo2);
            }
            final LiveVitalsFrameRoute liveVitalsFrameRoute = routeLiveVitalsFrame(
                    bloodPressureOperation.getState()
            );
            final YcbtProtocol.LiveVitals liveVitals = liveVitalsFrameRoute == LiveVitalsFrameRoute.VITALS
                    && YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseLiveVitals(frame)
                    : null;
            if (liveVitals != null) {
                persistLiveMeasurements(
                        liveVitals.getHeartRate(),
                        liveVitals.getHrv(),
                        liveVitals.getSpo2(),
                        liveVitals.getTemperatureCelsius()
                );
                if (liveVitals.getHeartRate() > 0) {
                    handleLiveHeartRate(liveVitals.getHeartRate());
                }
                if (liveVitals.getSpo2() > 0) {
                    handleLiveSpo2(liveVitals.getSpo2());
                }
            }
            final YcbtProtocol.Activity activity = YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(
                    characteristicUuid
            ) ? YcbtProtocol.parseLiveActivity(frame) : null;
            if (activity != null) {
                if (realtimeSamplesAggregator != null) {
                    realtimeSamplesAggregator.broadcastSteps(activity.getSteps());
                }
                diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                        Locale.ROOT,
                        "live activity steps=%d distanceMeters=%d calories=%d",
                        activity.getSteps(),
                        activity.getDistanceMeters(),
                        activity.getCalories()
                ));
            }
            if (YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(characteristicUuid)) {
                final Integer status = YcbtProtocol.parseBloodPressureControlReply(frame);
                if (status != null) {
                    handleMeasurementControlReply(status);
                }
            } else if (liveVitalsFrameRoute == LiveVitalsFrameRoute.BLOOD_PRESSURE
                    && YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(characteristicUuid)) {
                final YcbtProtocol.BloodPressure bloodPressure = YcbtProtocol.parseBloodPressureResult(frame);
                if (bloodPressure != null) {
                    handleBloodPressureResult(bloodPressure);
                }
            }
            LOG.debug("Decoded YCBT frame from {}: group=0x{}, command=0x{}, payloadLength={}",
                    characteristicUuid,
                    String.format(Locale.ROOT, "%02x", frame.getGroup()),
                    String.format(Locale.ROOT, "%02x", frame.getCommand()),
                    frame.getPayload().length);
            diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                    Locale.ROOT,
                    "%s decoded group=0x%02x command=0x%02x payloadLength=%d",
                    characteristicName(characteristicUuid),
                    frame.getGroup(),
                    frame.getCommand(),
                    frame.getPayload().length
            ));
        }
        return true;
    }

    private void handleSessionModel(final String model) {
        synchronized (ConnectionMonitor) {
            final YcbtSessionNegotiation.Result result = sessionNegotiation.handleModel(model);
            if (result == YcbtSessionNegotiation.Result.REQUEST_BATTERY) {
                cancelSessionTimeout();
                queueSessionRequest(YcbtSessionNegotiation.Stage.BATTERY);
            } else if (result == YcbtSessionNegotiation.Result.FAILED) {
                failSession("unsupported model " + model);
            }
        }
    }

    private void handleSessionBattery(final int batteryLevel) {
        synchronized (ConnectionMonitor) {
            if (sessionNegotiation.handleBattery(batteryLevel)
                    == YcbtSessionNegotiation.Result.REQUEST_CAPABILITIES) {
                cancelSessionTimeout();
                queueSessionRequest(YcbtSessionNegotiation.Stage.CAPABILITIES);
            }
        }
    }

    private void handleSessionCapabilities() {
        synchronized (ConnectionMonitor) {
            if (sessionNegotiation.handleCapabilities() == YcbtSessionNegotiation.Result.READY) {
                cancelSessionTimeout();
                if (!queueTimeSync()) {
                    failSession("could not queue time sync");
                    return;
                }
                getDevice().setFirmwareVersion("N/A");
                getDevice().setFirmwareVersion2("N/A");
                getDevice().setUpdateState(GBDevice.State.INITIALIZED, getContext());
                diagnostic(YcbtDiagnostics.TYPE_INITIALIZED,
                        "INITIALIZED after model, battery, and capability negotiation");
                applyAutomaticMonitoringSettings();
                requestLiveActivity();
            }
        }
    }

    private void persistCapabilities(final YcbtProtocol.Capabilities capabilities) {
        GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit()
                .putBoolean(YcbtConstants.PREF_CAPABILITY_HEART_RATE, true)
                .putBoolean(YcbtConstants.PREF_CAPABILITY_SPO2, true)
                .putBoolean(YcbtConstants.PREF_CAPABILITY_HRV, capabilities.hasHrv())
                .putBoolean(YcbtConstants.PREF_CAPABILITY_STEPS, true)
                .putBoolean(YcbtConstants.PREF_CAPABILITY_SLEEP, true)
                .putBoolean(YcbtConstants.PREF_CAPABILITY_FIND_DEVICE, capabilities.hasFindDevice())
                .putBoolean(YcbtConstants.PREF_CAPABILITY_BLOOD_PRESSURE, capabilities.hasBloodPressure())
                .putBoolean(YcbtConstants.PREF_CAPABILITY_MANUAL_HEART_RATE, true)
                .putBoolean(YcbtConstants.PREF_CAPABILITY_MANUAL_BLOOD_PRESSURE,
                        capabilities.hasManualBloodPressure())
                .putBoolean(YcbtConstants.PREF_CAPABILITY_MANUAL_SPO2, true)
                .putBoolean(YcbtConstants.PREF_CAPABILITY_MANUAL_HRV, capabilities.hasManualHrv())
                .putBoolean(YcbtConstants.PREF_CAPABILITY_TEMPERATURE, capabilities.hasTemperature())
                .putBoolean(YcbtConstants.PREF_CAPABILITY_BLOOD_SUGAR, capabilities.hasBloodSugar())
                .putBoolean(YcbtConstants.PREF_CAPABILITY_STRESS, capabilities.hasStress())
                .apply();
    }

    private void requestLiveActivity() {
        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        if (commandCharacteristic == null) {
            return;
        }
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        final TransactionBuilder builder = createTransactionBuilder("YCBT live activity");
        builder.write(commandCharacteristic, YcbtProtocol.buildLiveActivityRequest());
        builder.queue();
    }

    @Override
    public void onSetTime() {
        synchronized (ConnectionMonitor) {
            if (isInitialized()) {
                queueTimeSync();
            }
        }
    }

    private boolean queueTimeSync() {
        return queueCommand(
                "YCBT time sync",
                YcbtProtocol.buildSetTimeRequest(Instant.now(), ZoneId.systemDefault())
        );
    }

    private void applyAutomaticMonitoringSettings() {
        final Prefs prefs = getDevicePrefs();
        if (prefs.getBoolean(YcbtConstants.PREF_HEART_RATE_MONITORING_CONFIGURED, false)) {
            queueHeartRateMonitoring(prefs.getInt(
                    DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
                    0
            ));
        }
        if (prefs.getBoolean(YcbtConstants.PREF_SPO2_MONITORING_CONFIGURED, false)) {
            final boolean enabled = prefs.getBoolean(
                    DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
                    false
            );
            final int intervalSeconds = prefs.getInt(
                    DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL,
                    DEFAULT_AUTOMATIC_MONITORING_INTERVAL_SECONDS
            );
            queueSpo2Monitoring(enabled, intervalSeconds);
        }
    }

    @Override
    public void onFetchRecordedData(final int dataTypes) {
        synchronized (ConnectionMonitor) {
            if (!isInitialized() || !isConnected()) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, "history fetch ignored: device not initialized");
                return;
            }
            if (historyFetchActive || historyTransfer.isActive()) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "history fetch ignored: transfer already active");
                return;
            }

            final List<YcbtHistoryTransfer.HistoryType> historyTypes = historyTypesFor(dataTypes, capabilities);
            if (historyTypes.isEmpty()) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "history fetch skipped: no supported requested types");
                GB.signalActivityDataFinish(getDevice());
                return;
            }

            historyFetchActive = true;
            getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
            getDevice().sendDeviceUpdateIntent(getContext());
            GB.updateTransferNotification(
                    getContext().getString(R.string.busy_task_fetch_activity_data),
                    "",
                    true,
                    0,
                    getContext()
            );
            diagnostic(YcbtDiagnostics.TYPE_STAGE, "history fetch started types=" + historyTypes);
            consumeHistoryResult(historyTransfer.start(historyTypes, SystemClock.elapsedRealtime()));
            if (historyFetchActive) {
                scheduleHistoryWatchdog();
            }
        }
    }

    static List<YcbtHistoryTransfer.HistoryType> historyTypesFor(
            final int dataTypes,
            final YcbtProtocol.Capabilities capabilities) {
        final Set<YcbtHistoryTransfer.HistoryType> types = new LinkedHashSet<>();
        if (capabilities == null) {
            return new ArrayList<>();
        }
        final boolean fullSync = dataTypes == 0
                || dataTypes == RecordedDataTypes.TYPE_SYNC
                || dataTypes == RecordedDataTypes.TYPE_ALL;

        if (requested(dataTypes, RecordedDataTypes.TYPE_ACTIVITY, fullSync)) {
            types.add(YcbtHistoryTransfer.HistoryType.SPORT);
        }
        if (requested(dataTypes, RecordedDataTypes.TYPE_SLEEP, fullSync)) {
            types.add(YcbtHistoryTransfer.HistoryType.SLEEP);
        }
        if (requested(dataTypes, RecordedDataTypes.TYPE_HEART_RATE, fullSync)) {
            types.add(YcbtHistoryTransfer.HistoryType.HEART_RATE);
        }
        if (fullSync && capabilities.hasBloodPressure()) {
            types.add(YcbtHistoryTransfer.HistoryType.BLOOD_PRESSURE);
        }

        final boolean wantsVitals = requested(dataTypes, RecordedDataTypes.TYPE_SPO2, fullSync)
                || (capabilities.hasHrv() && requested(dataTypes, RecordedDataTypes.TYPE_HRV, fullSync));
        if (wantsVitals) {
            types.add(YcbtHistoryTransfer.HistoryType.VITALS);
        }
        if (capabilities.hasTemperature()
                && requested(dataTypes, RecordedDataTypes.TYPE_TEMPERATURE, fullSync)) {
            types.add(YcbtHistoryTransfer.HistoryType.TEMPERATURE);
        }
        if (fullSync && capabilities.hasBloodSugar()) {
            types.add(YcbtHistoryTransfer.HistoryType.COMPREHENSIVE);
        }
        if ((capabilities.hasHrv() && requested(dataTypes, RecordedDataTypes.TYPE_HRV, fullSync))
                || (capabilities.hasStress() && requested(dataTypes, RecordedDataTypes.TYPE_STRESS, fullSync))) {
            types.add(YcbtHistoryTransfer.HistoryType.BODY_DATA);
        }
        return new ArrayList<>(types);
    }

    private static boolean requested(final int dataTypes, final int type, final boolean fullSync) {
        return fullSync || (dataTypes & type) != 0;
    }

    static LiveVitalsFrameRoute routeLiveVitalsFrame(final YcbtBloodPressureOperation.State state) {
        return state == YcbtBloodPressureOperation.State.IDLE
                ? LiveVitalsFrameRoute.VITALS
                : LiveVitalsFrameRoute.BLOOD_PRESSURE;
    }

    private void handleHistoryFrame(final YcbtFrameCodec.Frame frame) {
        synchronized (ConnectionMonitor) {
            final YcbtHistoryTransfer.Result result = historyTransfer.handle(
                    frame.getCommand(),
                    frame.getPayload(),
                    SystemClock.elapsedRealtime()
            );
            if (result.getStatus() != YcbtHistoryTransfer.Status.IGNORED) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                        Locale.ROOT,
                        "history command=0x%02x status=%s buffered=%d",
                        frame.getCommand(),
                        result.getStatus(),
                        historyTransfer.getBufferedByteCount()
                ));
            }
            consumeHistoryResult(result);
        }
    }

    private void consumeHistoryResult(final YcbtHistoryTransfer.Result result) {
        if (!processHistoryResult(result, this::persistHistoryBlock, this::queueHistoryAction)) {
            cancelHistoryFetch(true);
            return;
        }
        if (result.isFinished() && historyFetchActive) {
            finishHistoryFetch();
        }
    }

    static boolean processHistoryResult(
            final YcbtHistoryTransfer.Result result,
            final BiFunction<YcbtHistoryTransfer.HistoryType, byte[], Boolean> persister,
            final Predicate<YcbtHistoryTransfer.Action> actionConsumer) {
        if (result.getCompletedBlock() != null
                && !Boolean.TRUE.equals(persister.apply(result.getCompletedType(), result.getCompletedBlock()))) {
            return false;
        }
        for (final YcbtHistoryTransfer.Action action : result.getActions()) {
            if (!actionConsumer.test(action)) {
                return false;
            }
        }
        return true;
    }

    private boolean queueHistoryAction(final YcbtHistoryTransfer.Action action) {
        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        if (commandCharacteristic == null || !isConnected()) {
            diagnostic(YcbtDiagnostics.TYPE_FAILURE,
                    "history " + action.getType() + " failed: command characteristic unavailable");
            return false;
        }
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        final TransactionBuilder builder = createTransactionBuilder(
                "YCBT history " + action.getType().name().toLowerCase(Locale.ROOT)
        );
        builder.write(commandCharacteristic, YcbtProtocol.frameLogicalCommand(action.getCommand()));
        builder.queue();
        return true;
    }

    private void scheduleHistoryWatchdog() {
        final long generation = ++historyTimeoutGeneration;
        historyTimeoutHandler.removeCallbacksAndMessages(null);
        historyTimeoutHandler.postDelayed(
                () -> onHistoryWatchdog(generation),
                HISTORY_WATCHDOG_INTERVAL_MILLIS
        );
    }

    private void onHistoryWatchdog(final long generation) {
        synchronized (ConnectionMonitor) {
            if (generation != historyTimeoutGeneration || !historyFetchActive) {
                return;
            }
            consumeHistoryResult(historyTransfer.onTimeout(SystemClock.elapsedRealtime()));
            if (historyFetchActive) {
                historyTimeoutHandler.postDelayed(
                        () -> onHistoryWatchdog(generation),
                        HISTORY_WATCHDOG_INTERVAL_MILLIS
                );
            }
        }
    }

    private void cancelHistoryFetch(final boolean signalCompletion) {
        historyTimeoutGeneration++;
        historyTimeoutHandler.removeCallbacksAndMessages(null);
        historyTransfer.cancel();
        if (!historyFetchActive) {
            return;
        }
        historyFetchActive = false;
        GB.updateTransferNotification(null, "", false, 100, getContext());
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        if (signalCompletion) {
            GB.signalActivityDataFinish(getDevice());
        }
    }

    private void finishHistoryFetch() {
        historyTimeoutGeneration++;
        historyTimeoutHandler.removeCallbacksAndMessages(null);
        historyFetchActive = false;
        GB.updateTransferNotification(null, "", false, 100, getContext());
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDevice());
        diagnostic(YcbtDiagnostics.TYPE_STAGE, "history fetch finished");
    }

    @Override
    public void onSendConfiguration(final String config) {
        if (YcbtConstants.CONFIG_MEASURE_BLOOD_PRESSURE.equals(config)) {
            startBloodPressureMeasurement();
            return;
        }
        if (YcbtConstants.CONFIG_MEASURE_SPO2.equals(config)) {
            startSpo2Measurement();
            return;
        }
        if (DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING.equals(config)
                || DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL.equals(config)) {
            synchronized (ConnectionMonitor) {
                getDevicePrefs().getPreferences().edit()
                        .putBoolean(YcbtConstants.PREF_SPO2_MONITORING_CONFIGURED, true)
                        .apply();
                if (!isInitialized()) {
                    return;
                }
                queueSpo2Monitoring(
                        getDevicePrefs().getBoolean(
                                DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
                                false
                        ),
                        getDevicePrefs().getInt(
                                DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL,
                                DEFAULT_AUTOMATIC_MONITORING_INTERVAL_SECONDS
                        )
                );
            }
            return;
        }
        super.onSendConfiguration(config);
    }

    @Override
    public void onSetHeartRateMeasurementInterval(final int seconds) {
        synchronized (ConnectionMonitor) {
            getDevicePrefs().getPreferences().edit()
                    .putBoolean(YcbtConstants.PREF_HEART_RATE_MONITORING_CONFIGURED, true)
                    .apply();
            if (!isInitialized()) {
                return;
            }
            queueHeartRateMonitoring(seconds);
        }
    }

    private void queueHeartRateMonitoring(final int intervalSeconds) {
        final boolean enabled = intervalSeconds > 0;
        final int intervalMinutes = YcbtProtocol.normalizeMonitoringInterval(intervalSeconds / 60);
        if (queueCommand(
                "YCBT automatic heart rate",
                YcbtProtocol.buildHeartRateMonitoringRequest(enabled, intervalMinutes)
        )) {
            diagnostic(YcbtDiagnostics.TYPE_STAGE,
                    "automatic heart rate requested enabled=" + enabled + " intervalMinutes=" + intervalMinutes);
        }
    }

    private void queueSpo2Monitoring(final boolean enabled, final int intervalSeconds) {
        final int intervalMinutes = YcbtProtocol.normalizeMonitoringInterval(intervalSeconds / 60);
        if (queueCommand(
                "YCBT automatic SpO2",
                YcbtProtocol.buildSpo2MonitoringRequest(enabled, intervalMinutes)
        )) {
            diagnostic(YcbtDiagnostics.TYPE_STAGE,
                    "automatic SpO2 requested enabled=" + enabled + " intervalMinutes=" + intervalMinutes);
        }
    }

    @Override
    public void onHeartRateTest() {
        synchronized (ConnectionMonitor) {
            if (!isInitialized() || capabilities == null) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, "heart rate start ignored: unavailable");
                return;
            }
            if (measurementReplyQuarantined() || manualHeartRateActive
                    || pendingHeartRateControl != HeartRateControl.NONE
                    || realtimeHeartRateEnabled || manualSpo2Active
                    || pendingSpo2Control != Spo2Control.NONE
                    || bloodPressureOperation.getState() != YcbtBloodPressureOperation.State.IDLE) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "heart rate start ignored: another measurement is active");
                return;
            }
            manualHeartRateActive = true;
            manualHeartRateResultReceived = false;
            if (!queueHeartRateControl(
                    HeartRateControl.MANUAL_START,
                    "YCBT heart rate start",
                    YcbtProtocol.buildHeartRateStartRequest()
            )) {
                manualHeartRateActive = false;
                return;
            }
            scheduleHeartRateTimeout();
            diagnostic(YcbtDiagnostics.TYPE_STAGE, "heart rate start requested");
        }
    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(final boolean enable) {
        synchronized (ConnectionMonitor) {
            if (!isInitialized() || manualHeartRateActive || manualSpo2Active
                    || pendingSpo2Control != Spo2Control.NONE
                    || bloodPressureOperation.getState() != YcbtBloodPressureOperation.State.IDLE) {
                return;
            }
            final boolean quarantined = measurementReplyQuarantined();
            final boolean realtimeControlPending = pendingHeartRateControl == HeartRateControl.REALTIME_START
                    || pendingHeartRateControl == HeartRateControl.REALTIME_STOP;
            final RealtimeHeartRateRequest request = decideRealtimeHeartRateRequest(
                    enable,
                    realtimeHeartRateEnabled,
                    quarantined,
                    realtimeControlPending,
                    pendingHeartRateControl != HeartRateControl.NONE,
                    deferredRealtimeHeartRateEnabled
            );
            deferredRealtimeHeartRateEnabled = request.getDeferredTarget();
            if (request.getAction() == RealtimeHeartRateRequestAction.DEFER) {
                if (quarantined) {
                    scheduleDeferredRealtimeHeartRateState();
                }
            } else if (request.getAction() == RealtimeHeartRateRequestAction.START) {
                queueHeartRateControl(
                        HeartRateControl.REALTIME_START,
                        "YCBT realtime heart rate start",
                        YcbtProtocol.buildHeartRateStartRequest()
                );
            } else if (request.getAction() == RealtimeHeartRateRequestAction.STOP) {
                queueHeartRateControl(
                        HeartRateControl.REALTIME_STOP,
                        "YCBT realtime heart rate stop",
                        YcbtProtocol.buildHeartRateStopRequest()
                );
            }
        }
    }

    static RealtimeHeartRateRequest decideRealtimeHeartRateRequest(
            final boolean requestedEnabled,
            final boolean currentlyEnabled,
            final boolean quarantined,
            final boolean realtimeControlPending,
            final boolean anyControlPending,
            final Boolean deferredTarget) {
        if (quarantined || realtimeControlPending) {
            return new RealtimeHeartRateRequest(RealtimeHeartRateRequestAction.DEFER, requestedEnabled);
        }
        if (anyControlPending) {
            return new RealtimeHeartRateRequest(RealtimeHeartRateRequestAction.IGNORE, deferredTarget);
        }
        if (requestedEnabled == currentlyEnabled) {
            return new RealtimeHeartRateRequest(RealtimeHeartRateRequestAction.IGNORE, null);
        }
        return new RealtimeHeartRateRequest(
                requestedEnabled ? RealtimeHeartRateRequestAction.START : RealtimeHeartRateRequestAction.STOP,
                null
        );
    }

    @Override
    public void onEnableRealtimeSteps(final boolean enable) {
        if (enable) {
            requestLiveActivity();
        }
    }

    @Override
    public void onFindDevice(final boolean start) {
        synchronized (ConnectionMonitor) {
            if (!start || !isInitialized() || capabilities == null || !capabilities.hasFindDevice()) {
                return;
            }
            queueCommand("YCBT find device", YcbtProtocol.buildFindDeviceRequest());
        }
    }

    private void handleMeasurementControlReply(final int status) {
        synchronized (ConnectionMonitor) {
            if (measurementReplyQuarantined()) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "ignored quarantined measurement control reply");
                return;
            }
            if (pendingHeartRateControl != HeartRateControl.NONE) {
                handleHeartRateControlReply(status);
            } else if (pendingSpo2Control != Spo2Control.NONE) {
                handleSpo2ControlReply(status);
            } else {
                handleBloodPressureControlReply(status);
            }
        }
    }

    private void handleHeartRateControlReply(final int status) {
        synchronized (ConnectionMonitor) {
            final HeartRateControl command = pendingHeartRateControl;
            if (mayReceiveLateHeartRateStartReply
                    && (command == HeartRateControl.MANUAL_STOP
                    || command == HeartRateControl.REALTIME_STOP)) {
                mayReceiveLateHeartRateStartReply = false;
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "ignored possible late heart rate start reply");
                return;
            }
            clearHeartRateControlWait();
            if (status != 0) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE,
                        "heart rate control reply rejected command=" + command + " status=" + status);
                if (command == HeartRateControl.MANUAL_START) {
                    cancelHeartRateMeasurement(false);
                } else if (command == HeartRateControl.REALTIME_START) {
                    realtimeHeartRateEnabled = false;
                }
                if (command == HeartRateControl.REALTIME_START || command == HeartRateControl.REALTIME_STOP) {
                    applyDeferredRealtimeHeartRateState();
                }
                return;
            }
            diagnostic(YcbtDiagnostics.TYPE_STAGE, "heart rate control reply accepted command=" + command);
            if (command == HeartRateControl.MANUAL_START && manualHeartRateResultReceived) {
                cancelHeartRateMeasurement(true);
            } else if (command == HeartRateControl.REALTIME_START) {
                realtimeHeartRateEnabled = true;
            } else if (command == HeartRateControl.REALTIME_STOP) {
                realtimeHeartRateEnabled = false;
            }
            if (command == HeartRateControl.REALTIME_START || command == HeartRateControl.REALTIME_STOP) {
                applyDeferredRealtimeHeartRateState();
            }
        }
    }

    private void applyDeferredRealtimeHeartRateState() {
        synchronized (ConnectionMonitor) {
            if (deferredRealtimeHeartRateEnabled == null) {
                return;
            }
            final boolean enable = deferredRealtimeHeartRateEnabled;
            deferredRealtimeHeartRateEnabled = null;
            onEnableRealtimeHeartRateMeasurement(enable);
        }
    }

    private void handleLiveHeartRate(final int heartRate) {
        if (realtimeSamplesAggregator != null) {
            realtimeSamplesAggregator.broadcastHeartRate(heartRate);
        }
        synchronized (ConnectionMonitor) {
            if (!manualHeartRateActive) {
                return;
            }
            diagnostic(YcbtDiagnostics.TYPE_STAGE, "heart rate result=" + heartRate);
            manualHeartRateResultReceived = true;
            if (pendingHeartRateControl != HeartRateControl.MANUAL_START) {
                cancelHeartRateMeasurement(true);
            }
        }
    }

    private void startSpo2Measurement() {
        synchronized (ConnectionMonitor) {
            if (!isInitialized() || capabilities == null) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, "SpO2 start ignored: unavailable");
                return;
            }
            if (measurementReplyQuarantined() || manualSpo2Active
                    || pendingSpo2Control != Spo2Control.NONE || manualHeartRateActive
                    || pendingHeartRateControl != HeartRateControl.NONE || realtimeHeartRateEnabled
                    || bloodPressureOperation.getState() != YcbtBloodPressureOperation.State.IDLE) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "SpO2 start ignored: another measurement is active");
                return;
            }
            manualSpo2Active = true;
            manualSpo2ResultReceived = false;
            if (!queueSpo2Control(Spo2Control.START, "YCBT SpO2 start", YcbtProtocol.buildSpo2StartRequest())) {
                manualSpo2Active = false;
                return;
            }
            scheduleSpo2Timeout();
            GB.toast(getContext(), R.string.spo2_measurement_started, Toast.LENGTH_SHORT, GB.INFO);
            diagnostic(YcbtDiagnostics.TYPE_STAGE, "SpO2 start requested");
        }
    }

    private void handleSpo2ControlReply(final int status) {
        synchronized (ConnectionMonitor) {
            final Spo2Control command = pendingSpo2Control;
            if (mayReceiveLateSpo2StartReply && command == Spo2Control.STOP) {
                mayReceiveLateSpo2StartReply = false;
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "ignored possible late SpO2 start reply");
                return;
            }
            clearSpo2ControlWait();
            if (status != 0) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE,
                        "SpO2 control reply rejected command=" + command + " status=" + status);
                if (command == Spo2Control.START) {
                    cancelSpo2Measurement(false);
                }
                return;
            }
            diagnostic(YcbtDiagnostics.TYPE_STAGE, "SpO2 control reply accepted command=" + command);
            if (command == Spo2Control.START && manualSpo2ResultReceived) {
                cancelSpo2Measurement(true);
            }
        }
    }

    private void handleLiveSpo2(final int spo2) {
        synchronized (ConnectionMonitor) {
            if (!manualSpo2Active) {
                return;
            }
            diagnostic(YcbtDiagnostics.TYPE_STAGE, "SpO2 result=" + spo2);
            GB.toast(getContext(), getContext().getString(R.string.spo2_measurement_result, spo2),
                    Toast.LENGTH_LONG, GB.INFO);
            manualSpo2ResultReceived = true;
            if (pendingSpo2Control != Spo2Control.START) {
                cancelSpo2Measurement(true);
            }
        }
    }

    private void startBloodPressureMeasurement() {
        synchronized (ConnectionMonitor) {
            if (!isInitialized()) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, "blood pressure start ignored: device not initialized");
                GB.toast(getContext(), R.string.blood_pressure_measurement_unavailable,
                        Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (!Boolean.TRUE.equals(bloodPressureSupported)) {
                final String reason = bloodPressureSupported == null ? "capability unknown" : "capability absent";
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, "blood pressure start ignored: " + reason);
                GB.toast(getContext(), R.string.blood_pressure_measurement_unavailable,
                        Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (measurementReplyQuarantined() || manualHeartRateActive
                    || pendingHeartRateControl != HeartRateControl.NONE
                    || realtimeHeartRateEnabled || manualSpo2Active
                    || pendingSpo2Control != Spo2Control.NONE) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE,
                        "blood pressure start ignored: heart rate measurement is active");
                return;
            }
            if (!bloodPressureOperation.requestStart()) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE,
                        "blood pressure start ignored: state=" + bloodPressureOperation.getState());
                return;
            }
            if (!queueBloodPressureCommand(
                    "YCBT blood pressure start",
                    YcbtProtocol.buildBloodPressureStartRequest(),
                    "blood pressure start write request=032f080001016e0b",
                    BloodPressureCommand.START,
                    BLOOD_PRESSURE_RESULT_TIMEOUT_MILLIS
            )) {
                bloodPressureOperation.cancel();
                return;
            }
            GB.toast(getContext(), R.string.blood_pressure_measurement_started,
                    Toast.LENGTH_SHORT, GB.INFO);
        }
    }

    private void handleBloodPressureControlReply(final int status) {
        synchronized (ConnectionMonitor) {
            final YcbtBloodPressureOperation.Reply reply = bloodPressureOperation.handleReply(status);
            switch (reply) {
                case START_ACCEPTED:
                    diagnostic(YcbtDiagnostics.TYPE_STAGE,
                            "blood pressure start reply accepted status=" + status);
                    break;
                case START_REJECTED:
                    cancelBloodPressureTimeout();
                    diagnostic(YcbtDiagnostics.TYPE_FAILURE,
                            "blood pressure start reply rejected status=" + status);
                    break;
                case DELAYED_START_REPLY:
                    diagnostic(YcbtDiagnostics.TYPE_STAGE,
                            "blood pressure delayed start reply ignored status=" + status);
                    break;
                case STOP_REPLY:
                    cancelBloodPressureTimeout();
                    diagnostic(YcbtDiagnostics.TYPE_STAGE,
                            "blood pressure stop reply status=" + status);
                    break;
                case IGNORED:
                    diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                            Locale.ROOT,
                            "blood pressure control reply ignored status=%d state=%s",
                            status,
                            bloodPressureOperation.getState()
                    ));
                    break;
            }
        }
    }

    private void handleBloodPressureResult(final YcbtProtocol.BloodPressure bloodPressure) {
        synchronized (ConnectionMonitor) {
            if (!bloodPressureOperation.handleResult()) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                        Locale.ROOT,
                        "blood pressure result ignored systolic=%d diastolic=%d state=%s",
                        bloodPressure.getSystolic(),
                        bloodPressure.getDiastolic(),
                        bloodPressureOperation.getState()
                ));
                return;
            }

            diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                    Locale.ROOT,
                    "blood pressure result systolic=%d diastolic=%d pulse=%d",
                    bloodPressure.getSystolic(),
                    bloodPressure.getDiastolic(),
                    bloodPressure.getPulse()
            ));
            persistBloodPressure(bloodPressure);
            scheduleBloodPressureTimeout(BLOOD_PRESSURE_STOP_REPLY_TIMEOUT_MILLIS);
            if (!queueBloodPressureCommand(
                    "YCBT blood pressure stop",
                    YcbtProtocol.buildBloodPressureStopRequest(),
                    "blood pressure stop write request=032f080000015f38",
                    BloodPressureCommand.STOP,
                    BLOOD_PRESSURE_STOP_REPLY_TIMEOUT_MILLIS
            )) {
                cancelBloodPressureTimeout();
                bloodPressureOperation.cancel();
                return;
            }
        }
    }

    private void persistBloodPressure(final YcbtProtocol.BloodPressure bloodPressure) {
        final GenericBloodPressureSample sample = new GenericBloodPressureSample();
        sample.setTimestamp(System.currentTimeMillis());
        sample.setBpSystolic(bloodPressure.getSystolic());
        sample.setBpDiastolic(bloodPressure.getDiastolic());
        sample.setPulseRate(bloodPressure.getPulse());
        sample.setMeanArterialPressure(
                (bloodPressure.getSystolic() + 2 * bloodPressure.getDiastolic()) / 3
        );
        sample.setUserIndex(0);
        sample.setMeasurementStatus(0);

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final GenericBloodPressureSampleProvider provider =
                    new GenericBloodPressureSampleProvider(getDevice(), session);
            if (provider.persistSamples(List.of(sample), getContext())) {
                GB.toast(getContext(), getContext().getString(
                                R.string.blood_pressure_measurement_result,
                                bloodPressure.getSystolic(),
                                bloodPressure.getDiastolic(),
                                bloodPressure.getPulse()),
                        Toast.LENGTH_LONG, GB.INFO);
            }
        } catch (final Exception e) {
            GB.toast(getContext(), getContext().getString(R.string.blood_pressure_measurement_save_failed),
                    Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    private void persistLiveMeasurements(final int heartRate,
                                         final int hrv,
                                         final int spo2,
                                         final double temperatureCelsius) {
        final YcbtProtocol.Capabilities currentCapabilities = capabilities;
        if (currentCapabilities == null) {
            return;
        }
        final long timestamp = System.currentTimeMillis();

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            if (heartRate > 0) {
                final GenericHeartRateSample sample = new GenericHeartRateSample();
                sample.setTimestamp(timestamp);
                sample.setHeartRate(heartRate);
                new GenericHeartRateSampleProvider(getDevice(), session)
                        .persistSamples(List.of(sample), getContext());
            }
            if (hrv > 0 && currentCapabilities.hasHrv()) {
                final GenericHrvValueSample sample = new GenericHrvValueSample();
                sample.setTimestamp(timestamp);
                sample.setValue(hrv);
                new GenericHrvValueSampleProvider(getDevice(), session)
                        .persistSamples(List.of(sample), getContext());
            }
            if (spo2 > 0) {
                final GenericSpo2Sample sample = new GenericSpo2Sample();
                sample.setTimestamp(timestamp);
                sample.setSpo2(spo2);
                new GenericSpo2SampleProvider(getDevice(), session)
                        .persistSamples(List.of(sample), getContext());
            }
            if (temperatureCelsius > 0 && currentCapabilities.hasTemperature()) {
                final GenericTemperatureSample sample = new GenericTemperatureSample();
                sample.setTimestamp(timestamp);
                sample.setTemperature((float) temperatureCelsius);
                sample.setTemperatureType(TemperatureSample.TYPE_SKIN);
                sample.setTemperatureLocation(TemperatureSample.LOCATION_FINGER);
                new GenericTemperatureSampleProvider(
                        getDevice(),
                        session,
                        TemperatureSample.TYPE_SKIN,
                        TemperatureSample.LOCATION_FINGER
                ).persistSamples(List.of(sample), getContext());
            }
        } catch (final Exception e) {
            LOG.error("Could not persist YCBT live measurements", e);
        }
    }

    private boolean persistHistoryBlock(final YcbtHistoryTransfer.HistoryType historyType, final byte[] block) {
        final List<YcbtHealthRecordParser.Record> records = YcbtHealthRecordParser.parse(
                historyType.getQueryKey(),
                block
        );
        final long now = System.currentTimeMillis();
        final List<GenericBloodPressureSample> bloodPressureSamples = new ArrayList<>();
        final List<GenericHeartRateSample> heartRateSamples = new ArrayList<>();
        final List<GenericHrvValueSample> hrvSamples = new ArrayList<>();
        final List<GenericSleepStageSample> sleepStageSamples = new ArrayList<>();
        final List<GenericSpo2Sample> spo2Samples = new ArrayList<>();
        final List<GenericStressSample> stressSamples = new ArrayList<>();
        final List<GenericTemperatureSample> temperatureSamples = new ArrayList<>();
        final List<GlucoseSample> glucoseSamples = new ArrayList<>();
        final List<YcbtActivitySample> activitySamples = new ArrayList<>();
        final List<YcbtHealthRecordParser.SleepRecord> sleepRecords = new ArrayList<>();

        for (final YcbtHealthRecordParser.Record record : records) {
            final long timestamp = record.getTimestamp().toEpochMilli();
            if (!historyTimestampSupported(timestamp, now)) {
                LOG.warn("Ignoring YCBT {} history record too far in the future: {}", historyType, record.getTimestamp());
                continue;
            }
            if (!historyRecordSupported(record, capabilities)) {
                continue;
            }
            if (record instanceof YcbtHealthRecordParser.ActivityRecord) {
                final YcbtHealthRecordParser.ActivityRecord activity =
                        (YcbtHealthRecordParser.ActivityRecord) record;
                final YcbtActivitySample sample = new YcbtActivitySample();
                sample.setTimestamp((int) (record.getTimestamp().getEpochSecond() / 60L) * 60);
                sample.setRawKind(ActivityKind.ACTIVITY.getCode());
                sample.setRawIntensity(ActivitySample.NOT_MEASURED);
                sample.setSteps(activity.getSteps());
                sample.setDistanceCm(activity.getDistanceMeters() * 100);
                sample.setHeartRate(ActivitySample.NOT_MEASURED);
                activitySamples.add(sample);
            } else if (record instanceof YcbtHealthRecordParser.SleepRecord) {
                final YcbtHealthRecordParser.SleepRecord sleepRecord =
                        (YcbtHealthRecordParser.SleepRecord) record;
                sleepRecords.add(sleepRecord);
                appendSleepStageSamples(sleepStageSamples, sleepRecord);
            } else if (record instanceof YcbtHealthRecordParser.BloodPressureRecord) {
                final YcbtHealthRecordParser.BloodPressureRecord value =
                        (YcbtHealthRecordParser.BloodPressureRecord) record;
                final GenericBloodPressureSample sample = new GenericBloodPressureSample();
                sample.setTimestamp(timestamp);
                sample.setBpSystolic(value.getSystolic());
                sample.setBpDiastolic(value.getDiastolic());
                sample.setMeanArterialPressure((value.getSystolic() + 2 * value.getDiastolic()) / 3);
                sample.setUserIndex(0);
                sample.setMeasurementStatus(0);
                bloodPressureSamples.add(sample);
            } else if (record instanceof YcbtHealthRecordParser.MeasurementRecord) {
                final YcbtHealthRecordParser.MeasurementRecord measurement =
                        (YcbtHealthRecordParser.MeasurementRecord) record;
                switch (measurement.getKind()) {
                    case HEART_RATE:
                        final GenericHeartRateSample heartRateSample = new GenericHeartRateSample();
                        heartRateSample.setTimestamp(timestamp);
                        heartRateSample.setHeartRate((int) Math.round(measurement.getValue()));
                        heartRateSamples.add(heartRateSample);
                        break;
                    case SPO2:
                        final GenericSpo2Sample spo2Sample = new GenericSpo2Sample();
                        spo2Sample.setTimestamp(timestamp);
                        spo2Sample.setSpo2((int) Math.round(measurement.getValue()));
                        spo2Samples.add(spo2Sample);
                        break;
                    case RESPIRATORY_RATE:
                        LOG.debug("YCBT respiratory-rate history is not exposed for this device family");
                        break;
                    case HRV:
                        final GenericHrvValueSample hrvSample = new GenericHrvValueSample();
                        hrvSample.setTimestamp(timestamp);
                        hrvSample.setValue((int) Math.round(measurement.getValue()));
                        hrvSamples.add(hrvSample);
                        break;
                    case TEMPERATURE:
                        final GenericTemperatureSample temperatureSample = new GenericTemperatureSample();
                        temperatureSample.setTimestamp(timestamp);
                        temperatureSample.setTemperature((float) measurement.getValue());
                        temperatureSample.setTemperatureType(TemperatureSample.TYPE_SKIN);
                        temperatureSample.setTemperatureLocation(TemperatureSample.LOCATION_FINGER);
                        temperatureSamples.add(temperatureSample);
                        break;
                    case BLOOD_SUGAR:
                        final GlucoseSample glucoseSample = new GlucoseSample();
                        glucoseSample.setTimestamp(timestamp);
                        glucoseSample.setValueMgDl(measurement.getValue());
                        glucoseSamples.add(glucoseSample);
                        break;
                    case STRESS:
                        final GenericStressSample stressSample = new GenericStressSample();
                        stressSample.setTimestamp(timestamp);
                        stressSample.setStress((int) Math.round(measurement.getValue()));
                        stressSamples.add(stressSample);
                        break;
                    case FATIGUE:
                        LOG.debug("YCBT fatigue history has no native persistence provider");
                        break;
                    case VO2_MAX:
                        LOG.debug("YCBT VO2 max history has no advertised capability");
                        break;
                }
            }
        }

        boolean persisted = true;
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            persisted &= new YcbtActivitySampleProvider(getDevice(), session)
                    .persistSamples(activitySamples, getContext());
            persisted &= new GenericBloodPressureSampleProvider(getDevice(), session)
                    .persistSamples(bloodPressureSamples, getContext());
            persisted &= new GenericHeartRateSampleProvider(getDevice(), session)
                    .persistSamples(heartRateSamples, getContext());
            persisted &= new GenericHrvValueSampleProvider(getDevice(), session)
                    .persistSamples(hrvSamples, getContext());
            final GenericSleepStageSampleProvider sleepProvider =
                    new GenericSleepStageSampleProvider(getDevice(), session);
            final List<GenericSleepStageSample> previousSleepStages = new ArrayList<>();
            for (final YcbtHealthRecordParser.SleepRecord sleepRecord : sleepRecords) {
                if (!sleepRecord.isCompleteSession()) {
                    continue;
                }
                final long start = sleepRecord.getTimestamp().toEpochMilli();
                final long packetEnd = sleepRecord.getEndTimestamp().toEpochMilli();
                previousSleepStages.addAll(findOverlappingSleepSessionStages(
                        sleepProvider,
                        start,
                        packetEnd
                ));
            }
            final boolean sleepPersisted = sleepProvider.persistSamples(sleepStageSamples, getContext());
            persisted &= sleepPersisted;
            if (sleepPersisted && !previousSleepStages.isEmpty()) {
                final Set<Long> currentStageTimestamps = new HashSet<>();
                for (final GenericSleepStageSample sample : sleepStageSamples) {
                    currentStageTimestamps.add(sample.getTimestamp());
                }
                previousSleepStages.removeIf(sample -> currentStageTimestamps.contains(sample.getTimestamp()));
                if (!previousSleepStages.isEmpty()) {
                    sleepProvider.getSampleDao().deleteInTx(previousSleepStages);
                }
            }
            persisted &= new GenericSpo2SampleProvider(getDevice(), session)
                    .persistSamples(spo2Samples, getContext());
            persisted &= new GenericStressSampleProvider(getDevice(), session)
                    .persistSamples(stressSamples, getContext());
            persisted &= new GenericTemperatureSampleProvider(
                    getDevice(),
                    session,
                    TemperatureSample.TYPE_SKIN,
                    TemperatureSample.LOCATION_FINGER
            ).persistSamples(temperatureSamples, getContext());
            persisted &= new GlucoseSampleProvider(getDevice(), session)
                    .persistSamples(glucoseSamples, getContext());
        } catch (final Exception e) {
            LOG.error("Could not persist YCBT {} history", historyType, e);
            return false;
        }
        if (!persisted) {
            LOG.error("Could not persist all YCBT {} history samples", historyType);
            return false;
        }

        diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                Locale.ROOT,
                "history persisted type=%s decoded=%d activity=%d",
                historyType,
                records.size(),
                activitySamples.size()
        ));
        return true;
    }

    static boolean historyRecordSupported(final YcbtHealthRecordParser.Record record,
                                          final YcbtProtocol.Capabilities capabilities) {
        if (record == null || capabilities == null) {
            return false;
        }
        if (record instanceof YcbtHealthRecordParser.ActivityRecord) {
            return true;
        }
        if (record instanceof YcbtHealthRecordParser.SleepRecord) {
            return true;
        }
        if (record instanceof YcbtHealthRecordParser.BloodPressureRecord) {
            return capabilities.hasBloodPressure();
        }
        if (!(record instanceof YcbtHealthRecordParser.MeasurementRecord)) {
            return false;
        }
        switch (((YcbtHealthRecordParser.MeasurementRecord) record).getKind()) {
            case HEART_RATE:
                return true;
            case SPO2:
                return true;
            case HRV:
                return capabilities.hasHrv();
            case TEMPERATURE:
                return capabilities.hasTemperature();
            case BLOOD_SUGAR:
                return capabilities.hasBloodSugar();
            case STRESS:
                return capabilities.hasStress();
            case FATIGUE:
                return true;
            case RESPIRATORY_RATE:
            case VO2_MAX:
                return false;
            default:
                return false;
        }
    }

    static boolean historyTimestampSupported(final long timestamp, final long now) {
        return timestamp <= now + HISTORY_MAX_FUTURE_MILLIS;
    }

    private static void appendSleepStageSamples(final List<GenericSleepStageSample> samples,
                                                final YcbtHealthRecordParser.SleepRecord record) {
        for (final YcbtHealthRecordParser.SleepSegment segment : record.getSegments()) {
            final GenericSleepStageSample sample = new GenericSleepStageSample();
            sample.setTimestamp(segment.getTimestamp().toEpochMilli());
            sample.setDuration(segment.getDurationMinutes());
            sample.setStage(toGenericSleepStage(segment.getStage()));
            samples.add(sample);
        }
    }

    private static List<GenericSleepStageSample> findOverlappingSleepSessionStages(
            final GenericSleepStageSampleProvider provider,
            final long packetStart,
            final long packetEnd) {
        final List<GenericSleepStageSample> candidates = new ArrayList<>(provider.getAllSamples(
                packetStart,
                packetStart + 24L * 60L * 60L * 1_000L
        ));
        final GenericSleepStageSample stageBefore = provider.getLastSampleBefore(packetStart);
        if (stageBefore != null
                && stageBefore.getTimestamp() < packetStart
                && stageBefore.getTimestamp() + stageBefore.getDuration() * 60_000L > packetStart) {
            candidates.add(stageBefore);
        }
        candidates.sort((left, right) -> Long.compare(left.getTimestamp(), right.getTimestamp()));

        final List<GenericSleepStageSample> overlappingSession = new ArrayList<>();
        long coveredEnd = packetEnd;
        for (final GenericSleepStageSample candidate : candidates) {
            final long candidateEnd = candidate.getTimestamp() + candidate.getDuration() * 60_000L;
            if (candidateEnd <= packetStart) {
                continue;
            }
            if (candidate.getTimestamp() > coveredEnd) {
                break;
            }
            overlappingSession.add(candidate);
            coveredEnd = Math.max(coveredEnd, candidateEnd);
        }
        return overlappingSession;
    }

    private static int toGenericSleepStage(final YcbtHealthRecordParser.SleepStage stage) {
        switch (stage) {
            case DEEP:
                return SLEEP_STAGE_DEEP;
            case LIGHT:
                return SLEEP_STAGE_LIGHT;
            case AWAKE:
                return SLEEP_STAGE_AWAKE;
            case REM:
                return SLEEP_STAGE_REM;
            case UNKNOWN:
            default:
                return SLEEP_STAGE_UNKNOWN;
        }
    }

    private void queueSessionRequest(final YcbtSessionNegotiation.Stage stage) {
        if (!isConnected()) {
            failSession("disconnected while requesting " + stage);
            return;
        }
        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        if (commandCharacteristic == null) {
            failSession("command/reply missing while requesting " + stage);
            return;
        }

        final byte[] request;
        final String transactionName;
        final String diagnosticMessage;
        switch (stage) {
            case MODEL:
                request = YcbtProtocol.buildModelRequest();
                transactionName = "YCBT model query";
                diagnosticMessage = "model probe write request=020308004750ef20";
                break;
            case BATTERY:
                request = YcbtProtocol.buildBatteryRequest();
                transactionName = "YCBT battery query";
                diagnosticMessage = "battery query write request=0200080047436fec";
                break;
            case CAPABILITIES:
                request = YcbtProtocol.buildCapabilityRequest();
                transactionName = "YCBT capability query";
                diagnosticMessage = "capability query write request=0201080047469b16";
                break;
            default:
                failSession("cannot request session stage " + stage);
                return;
        }

        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        final TransactionBuilder builder = createTransactionBuilder(transactionName);
        builder.run(() -> diagnostic(YcbtDiagnostics.TYPE_STAGE, diagnosticMessage));
        builder.run(this::scheduleSessionTimeout);
        builder.write(commandCharacteristic, request);
        builder.queue();
    }

    private void scheduleSessionTimeout() {
        synchronized (ConnectionMonitor) {
            final long generation = ++sessionTimeoutGeneration;
            sessionTimeoutHandler.removeCallbacksAndMessages(null);
            sessionTimeoutHandler.postDelayed(() -> onSessionTimeout(generation), SESSION_REPLY_TIMEOUT_MILLIS);
        }
    }

    private void cancelSessionTimeout() {
        synchronized (ConnectionMonitor) {
            sessionTimeoutGeneration++;
            sessionTimeoutHandler.removeCallbacksAndMessages(null);
        }
    }

    private void onSessionTimeout(final long generation) {
        synchronized (ConnectionMonitor) {
            if (generation != sessionTimeoutGeneration) {
                return;
            }
            final YcbtSessionNegotiation.Stage stage = sessionNegotiation.getStage();
            final YcbtSessionNegotiation.Timeout timeout = sessionNegotiation.onTimeout();
            if (timeout == YcbtSessionNegotiation.Timeout.RETRY) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "session retry stage=" + stage);
                queueSessionRequest(stage);
            } else if (timeout == YcbtSessionNegotiation.Timeout.FAILED) {
                failSession("session timeout stage=" + stage);
            }
        }
    }

    private void failSession(final String reason) {
        cancelSessionTimeout();
        diagnostic(YcbtDiagnostics.TYPE_FAILURE, reason);
        getDevice().setUpdateState(GBDevice.State.NOT_CONNECTED, getContext());
        disconnect();
    }

    private boolean queueCommand(final String transactionName, final byte[] request) {
        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        if (commandCharacteristic == null || !isConnected()) {
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, transactionName + " command/reply unavailable");
            return false;
        }
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        final TransactionBuilder builder = createTransactionBuilder(transactionName);
        builder.write(commandCharacteristic, request);
        builder.queue();
        return true;
    }

    private boolean measurementReplyQuarantined() {
        return SystemClock.elapsedRealtime() < measurementReplyQuarantineUntilMillis;
    }

    private void quarantineMeasurementReplies() {
        measurementReplyQuarantineUntilMillis = Math.max(
                measurementReplyQuarantineUntilMillis,
                SystemClock.elapsedRealtime() + MEASUREMENT_REPLY_QUARANTINE_MILLIS
        );
    }

    private boolean queueHeartRateControl(final HeartRateControl command,
                                          final String transactionName,
                                          final byte[] request) {
        if (pendingHeartRateControl != HeartRateControl.NONE) {
            return false;
        }
        pendingHeartRateControl = command;
        if (!queueCommand(transactionName, request)) {
            pendingHeartRateControl = HeartRateControl.NONE;
            return false;
        }
        final long generation = ++heartRateControlTimeoutGeneration;
        heartRateControlTimeoutHandler.removeCallbacksAndMessages(null);
        heartRateControlTimeoutHandler.postDelayed(
                () -> onHeartRateControlTimeout(generation),
                MEASUREMENT_CONTROL_REPLY_TIMEOUT_MILLIS
        );
        return true;
    }

    private void onHeartRateControlTimeout(final long generation) {
        synchronized (ConnectionMonitor) {
            if (generation != heartRateControlTimeoutGeneration
                    || pendingHeartRateControl == HeartRateControl.NONE) {
                return;
            }
            final HeartRateControl command = pendingHeartRateControl;
            clearHeartRateControlWait();
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, "heart rate control timeout command=" + command);
            if (command == HeartRateControl.MANUAL_START) {
                mayReceiveLateHeartRateStartReply = true;
                cancelHeartRateMeasurement(true);
            } else if (command == HeartRateControl.REALTIME_START) {
                realtimeHeartRateEnabled = false;
                mayReceiveLateHeartRateStartReply = true;
                queueHeartRateControl(
                        HeartRateControl.REALTIME_STOP,
                        "YCBT realtime heart rate timeout stop",
                        YcbtProtocol.buildHeartRateStopRequest()
                );
            } else if (command == HeartRateControl.REALTIME_STOP) {
                realtimeHeartRateEnabled = false;
                mayReceiveLateHeartRateStartReply = false;
                quarantineMeasurementReplies();
                scheduleDeferredRealtimeHeartRateState();
            } else if (command == HeartRateControl.MANUAL_STOP) {
                mayReceiveLateHeartRateStartReply = false;
                quarantineMeasurementReplies();
            }
        }
    }

    private void scheduleDeferredRealtimeHeartRateState() {
        if (deferredRealtimeHeartRateEnabled == null) {
            return;
        }
        final long delayMillis = Math.max(
                1L,
                measurementReplyQuarantineUntilMillis - SystemClock.elapsedRealtime()
        );
        heartRateControlTimeoutHandler.postDelayed(
                this::applyDeferredRealtimeHeartRateState,
                delayMillis
        );
    }

    private void clearHeartRateControlWait() {
        heartRateControlTimeoutGeneration++;
        heartRateControlTimeoutHandler.removeCallbacksAndMessages(null);
        pendingHeartRateControl = HeartRateControl.NONE;
    }

    private boolean queueSpo2Control(final Spo2Control command,
                                     final String transactionName,
                                     final byte[] request) {
        if (pendingSpo2Control != Spo2Control.NONE) {
            return false;
        }
        pendingSpo2Control = command;
        if (!queueCommand(transactionName, request)) {
            pendingSpo2Control = Spo2Control.NONE;
            return false;
        }
        final long generation = ++spo2ControlTimeoutGeneration;
        spo2ControlTimeoutHandler.removeCallbacksAndMessages(null);
        spo2ControlTimeoutHandler.postDelayed(
                () -> onSpo2ControlTimeout(generation),
                MEASUREMENT_CONTROL_REPLY_TIMEOUT_MILLIS
        );
        return true;
    }

    private void onSpo2ControlTimeout(final long generation) {
        synchronized (ConnectionMonitor) {
            if (generation != spo2ControlTimeoutGeneration || pendingSpo2Control == Spo2Control.NONE) {
                return;
            }
            final Spo2Control command = pendingSpo2Control;
            clearSpo2ControlWait();
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, "SpO2 control timeout command=" + command);
            if (command == Spo2Control.START) {
                mayReceiveLateSpo2StartReply = true;
                cancelSpo2Measurement(true);
            } else if (command == Spo2Control.STOP) {
                mayReceiveLateSpo2StartReply = false;
                quarantineMeasurementReplies();
            }
        }
    }

    private void clearSpo2ControlWait() {
        spo2ControlTimeoutGeneration++;
        spo2ControlTimeoutHandler.removeCallbacksAndMessages(null);
        pendingSpo2Control = Spo2Control.NONE;
    }

    private void scheduleHeartRateTimeout() {
        final long generation = ++heartRateTimeoutGeneration;
        heartRateTimeoutHandler.removeCallbacksAndMessages(null);
        heartRateTimeoutHandler.postDelayed(
                () -> onHeartRateTimeout(generation),
                HEART_RATE_RESULT_TIMEOUT_MILLIS
        );
    }

    private void onHeartRateTimeout(final long generation) {
        synchronized (ConnectionMonitor) {
            if (generation != heartRateTimeoutGeneration || !manualHeartRateActive) {
                return;
            }
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, "heart rate measurement timed out");
            cancelHeartRateMeasurement(true);
        }
    }

    private void cancelHeartRateMeasurement(final boolean sendStop) {
        heartRateTimeoutGeneration++;
        heartRateTimeoutHandler.removeCallbacksAndMessages(null);
        final boolean shouldStop = sendStop && manualHeartRateActive && isConnected();
        manualHeartRateActive = false;
        manualHeartRateResultReceived = false;
        if (shouldStop) {
            queueHeartRateControl(
                    HeartRateControl.MANUAL_STOP,
                    "YCBT heart rate stop",
                    YcbtProtocol.buildHeartRateStopRequest()
            );
        } else if (!sendStop) {
            mayReceiveLateHeartRateStartReply = false;
            clearHeartRateControlWait();
        }
    }

    private void scheduleSpo2Timeout() {
        final long generation = ++spo2TimeoutGeneration;
        spo2TimeoutHandler.removeCallbacksAndMessages(null);
        spo2TimeoutHandler.postDelayed(() -> onSpo2Timeout(generation), SPO2_RESULT_TIMEOUT_MILLIS);
    }

    private void onSpo2Timeout(final long generation) {
        synchronized (ConnectionMonitor) {
            if (generation != spo2TimeoutGeneration || !manualSpo2Active) {
                return;
            }
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, "SpO2 measurement timed out");
            cancelSpo2Measurement(true);
        }
    }

    private void cancelSpo2Measurement(final boolean sendStop) {
        spo2TimeoutGeneration++;
        spo2TimeoutHandler.removeCallbacksAndMessages(null);
        final boolean shouldStop = sendStop && manualSpo2Active && isConnected();
        manualSpo2Active = false;
        manualSpo2ResultReceived = false;
        if (shouldStop) {
            queueSpo2Control(Spo2Control.STOP, "YCBT SpO2 stop", YcbtProtocol.buildSpo2StopRequest());
        } else if (!sendStop) {
            mayReceiveLateSpo2StartReply = false;
            clearSpo2ControlWait();
        }
    }

    private boolean queueBloodPressureCommand(final String transactionName,
                                              final byte[] request,
                                              final String diagnosticMessage,
                                              final BloodPressureCommand command,
                                              final long replyTimeoutMillis) {
        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        if (commandCharacteristic == null) {
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, transactionName + " command/reply missing");
            return false;
        }
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        final TransactionBuilder builder = createTransactionBuilder(transactionName);
        builder.add(new YcbtBloodPressureWriteAction(
                commandCharacteristic,
                command,
                request,
                diagnosticMessage,
                replyTimeoutMillis
        ));
        builder.queue();
        return true;
    }

    private void scheduleBloodPressureTimeout(final long delayMillis) {
        synchronized (ConnectionMonitor) {
            final long generation = ++bloodPressureTimeoutGeneration;
            timeoutHandler.removeCallbacksAndMessages(null);
            timeoutHandler.postDelayed(() -> onBloodPressureTimeout(generation), delayMillis);
        }
    }

    private void cancelBloodPressureTimeout() {
        synchronized (ConnectionMonitor) {
            bloodPressureTimeoutGeneration++;
            timeoutHandler.removeCallbacksAndMessages(null);
        }
    }

    private void onBloodPressureTimeout(final long generation) {
        synchronized (ConnectionMonitor) {
            if (generation != bloodPressureTimeoutGeneration) {
                return;
            }
            bloodPressureTimeoutGeneration++;
            final YcbtBloodPressureOperation.State state = bloodPressureOperation.getState();
            if (state == YcbtBloodPressureOperation.State.IDLE) {
                return;
            }
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, "blood pressure timeout state=" + state);
            if (state == YcbtBloodPressureOperation.State.CLEANUP_STOP_SENT) {
                quarantineMeasurementReplies();
                bloodPressureOperation.finishCleanup();
                return;
            }
            if (state == YcbtBloodPressureOperation.State.CLEANUP_STOP_QUEUED) {
                return;
            }
            if (state == YcbtBloodPressureOperation.State.WAITING_STOP_REPLY || !isConnected()) {
                if (state == YcbtBloodPressureOperation.State.WAITING_STOP_REPLY) {
                    quarantineMeasurementReplies();
                }
                bloodPressureOperation.cancel();
                return;
            }
            if (bloodPressureOperation.requestCleanupStop() && !queueBloodPressureCommand(
                    "YCBT blood pressure timeout stop",
                    YcbtProtocol.buildBloodPressureStopRequest(),
                    "blood pressure timeout stop write request=032f080000015f38",
                    BloodPressureCommand.CLEANUP_STOP,
                    BLOOD_PRESSURE_STOP_REPLY_TIMEOUT_MILLIS
            )) {
                bloodPressureOperation.cancel();
            }
        }
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            synchronized (ConnectionMonitor) {
                cancelSessionTimeout();
                sessionNegotiation.reset();
                cancelHistoryFetch(true);
                cancelBloodPressureTimeout();
                bloodPressureOperation.cancel();
                cancelHeartRateMeasurement(false);
                cancelSpo2Measurement(false);
                realtimeHeartRateEnabled = false;
                deferredRealtimeHeartRateEnabled = null;
            }
            final String message = "disconnect status=" + status;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LOG.warn("YCBT GATT disconnected with status {}", status);
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, message);
            } else {
                LOG.debug("YCBT GATT disconnected successfully");
            }
            diagnostic(YcbtDiagnostics.TYPE_DISCONNECTED, message);
        }
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            cancelSessionTimeout();
            sessionNegotiation.reset();
            cancelHistoryFetch(false);
            cancelBloodPressureTimeout();
            bloodPressureOperation.cancel();
            cancelHeartRateMeasurement(false);
            cancelSpo2Measurement(false);
            realtimeHeartRateEnabled = false;
            deferredRealtimeHeartRateEnabled = null;
            super.dispose();
        }
    }

    private void diagnostic(final String eventType, final String message) {
        YcbtDiagnostics.emit(getContext(), getDevice().getAddress(), eventType, message);
    }

    private static String characteristicName(final UUID characteristicUuid) {
        if (YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(characteristicUuid)) {
            return "command/reply";
        }
        if (YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(characteristicUuid)) {
            return "stream/history";
        }
        return characteristicUuid.toString();
    }

    private static String serviceType(final int type) {
        if (type == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
            return "primary(" + type + ")";
        }
        if (type == BluetoothGattService.SERVICE_TYPE_SECONDARY) {
            return "secondary(" + type + ")";
        }
        return "unknown(" + type + ")";
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }
}
