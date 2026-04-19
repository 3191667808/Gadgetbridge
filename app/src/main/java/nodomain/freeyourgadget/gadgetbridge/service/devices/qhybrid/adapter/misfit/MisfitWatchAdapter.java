/*  Copyright (C) 2019-2024 Andreas Shimokawa, Carsten Pfeiffer, Daniel Dakhno

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.misfit;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport.ITEM_ACTIVITY_POINT;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport.ITEM_STEP_COUNT;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport.QHYBRID_EVENT_BUTTON_PRESS;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport.QHYBRID_EVENT_FILE_UPLOADED;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.CRC32;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.devices.qhybrid.HybridHRActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.qhybrid.NotificationConfiguration;
import nodomain.freeyourgadget.gadgetbridge.entities.HybridHRActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.WatchAdapter;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.Request;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.ActivityPointGetRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.AnimationRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.BatteryLevelRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.DownloadFileRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.EraseFileRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.FileRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetCountdownSettingsRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetCurrentStepCountRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetStepGoalRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetTimezoneOffsetRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetVibrationStrengthRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GoalTrackingGetRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.ListFilesRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.MoveHandsRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.OTAEnterRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.OTAEraseRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.PlayNotificationRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.ReleaseHandsControlRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.RequestHandControlRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SaveCalibrationRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetCurrentStepCountRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetStepGoalRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetTimeRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetVibrationStrengthRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.UploadFileRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.VibrateRequest;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MisfitWatchAdapter extends WatchAdapter {
    private static final byte WEAR_TYPE_WEARING = 0;
    private static final byte WEAR_TYPE_NOT_WEARING = 1;

    private int lastButtonIndex = -1;
    private final SparseArray<Request> responseFilters = new SparseArray<>();

    private UploadFileRequest uploadFileRequest;
    private Request fileRequest = null;

    private final Queue<Request> requestQueue = new ArrayDeque<>();

    private static final Logger logger = LoggerFactory.getLogger(MisfitWatchAdapter.class);

    public MisfitWatchAdapter(QHybridSupport deviceSupport) {
        super(deviceSupport);

        fillResponseList();
    }

    @Override
    public void initialize() {
        requestQueue.add(new GetStepGoalRequest());
        requestQueue.add(new GetVibrationStrengthRequest());
        requestQueue.add(new GetTimezoneOffsetRequest());
        requestQueue.add(new ActivityPointGetRequest());
        requestQueue.add(prepareSetTimeRequest());
        requestQueue.add(new AnimationRequest());
        if (supportsActivityHand()) {
            requestQueue.add(new SetCurrentStepCountRequest((int) (999999 * getDeviceSupport().calculateNotificationProgress())));
        }

        queueWrite(new GetCurrentStepCountRequest());

        getDeviceSupport().getDevice().setUpdateState(GBDevice.State.INITIALIZED, getContext());
    }


    private SetTimeRequest prepareSetTimeRequest() {
        long millis = System.currentTimeMillis();
        TimeZone zone = new GregorianCalendar().getTimeZone();
        return new SetTimeRequest(
                (int) (millis / 1000 + getDeviceSupport().getTimeOffset() * 60),
                (short) (millis % 1000),
                (short) ((zone.getRawOffset() + zone.getDSTSavings()) / 60000));
    }


    @Override
    public void playPairingAnimation() {
        queueWrite(new AnimationRequest());
    }

    @Override
    public void playNotification(NotificationConfiguration config) {
        queueWrite(new PlayNotificationRequest(
                config.getVibration(),
                config.getHour(),
                config.getMin(),
                config.getSubEye()
        ));
    }

    @Override
    public void setTime() {
        queueWrite(prepareSetTimeRequest());
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, final byte[] value) {
        GBDevice gbDevice = getDeviceSupport().getDevice();
        switch (characteristic.getUuid().toString()) {
            case "3dda0004-957f-7d4a-34a6-74696673696d":
            case "3dda0003-957f-7d4a-34a6-74696673696d": {
                return handleFileDownloadCharacteristic(characteristic, value);
            }
            case "3dda0007-957f-7d4a-34a6-74696673696d": {
                return handleFileUploadCharacteristic(characteristic, value);
            }
            case "3dda0002-957f-7d4a-34a6-74696673696d": {
                return handleBasicCharacteristic(characteristic, value);
            }
            case "3dda0006-957f-7d4a-34a6-74696673696d": {
                return handleButtonCharacteristic(characteristic, value);
            }
            case "00002a19-0000-1000-8000-00805f9b34fb": {
                short level = value[0];
                gbDevice.setBatteryLevel(level);

                GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
                batteryInfo.level = gbDevice.getBatteryLevel();
                batteryInfo.state = BatteryState.BATTERY_NORMAL;
                getDeviceSupport().handleGBDeviceEvent(batteryInfo);
                break;
            }
            default: {
                log("unknown shit on " + characteristic.getUuid().toString() + ":  " + arrayToString(value));
                try {
                    File charLog = FileUtils.getExternalFile("qFiles/charLog.txt");
                    try (FileOutputStream fos = new FileOutputStream(charLog, true)) {
                        fos.write((new Date().toString() + ": " + characteristic.getUuid().toString() + ": " + arrayToString(value)).getBytes());
                    }
                } catch (IOException e) {
                    logger.error("error", e);
                }
                break;
            }
        }
        return getDeviceSupport().onCharacteristicChanged(gatt, characteristic, value);
    }

    private void fillResponseList() {
        Class<? extends Request>[] classes = new Class[]{
                BatteryLevelRequest.class,
                GetStepGoalRequest.class,
                GetVibrationStrengthRequest.class,
                GetTimezoneOffsetRequest.class,
                GetCurrentStepCountRequest.class,
                OTAEnterRequest.class,
                GoalTrackingGetRequest.class,
                ActivityPointGetRequest.class,
                GetCountdownSettingsRequest.class
        };
        for (Class<? extends Request> c : classes) {
            try {
                c.getDeclaredMethod("handleResponse", BluetoothGattCharacteristic.class, byte[].class);
                Request object = c.newInstance();
                byte[] sequence = object.getStartSequence();
                if (sequence.length > 1) {
                    responseFilters.put((int) object.getStartSequence()[1], object);
                    log("response filter " + object.getStartSequence()[1] + ": " + c.getSimpleName());
                }
            } catch (NoSuchMethodException | IllegalAccessException | InstantiationException e) {
                log("skipping class " + c.getName());
            }
        }
    }

    private boolean handleBasicCharacteristic(BluetoothGattCharacteristic characteristic, byte[] values) {
        Request request = resolveAnswer(characteristic, values);
        GBDevice gbDevice = getDeviceSupport().getDevice();

        if (request == null) {
            StringBuilder valueString = new StringBuilder(String.valueOf(values[0]));
            for (int i = 1; i < values.length; i++) {
                valueString.append(", ").append(values[i]);
            }
            log("unable to resolve " + characteristic.getUuid().toString() + ": " + valueString);
            return true;
        }
        log("response: " + request.getClass().getSimpleName());
        request.handleResponse(characteristic, values);

        if (request instanceof GetStepGoalRequest) {
            int goal = ((GetStepGoalRequest) request).stepGoal;
            logger.info("Step goal on watch: {}", goal);
            int userGoal = new ActivityUser().getStepsGoal();
            if (userGoal != goal) {
                logger.info("Syncing step goal from Gadgetbridge ({}) to watch ({})", userGoal, goal);
                setStepGoal(userGoal);
            }
        } else if (request instanceof GetVibrationStrengthRequest) {
            int strength = ((GetVibrationStrengthRequest) request).strength;
            // Convert raw value (50/75/100) to seekbar value (1/2/3)
            int seekBarValue = strength <= 50 ? 1 : strength <= 75 ? 2 : 3;
            logger.info("Vibration strength from watch: {} (seekbar={})", strength, seekBarValue);
            getDeviceSpecificPreferences().edit()
                    .putInt(DeviceSettingsPreferenceConst.PREF_VIBRATION_STRENGH_PERCENTAGE, seekBarValue)
                    .apply();
        } else if (request instanceof GetTimezoneOffsetRequest) {
            short offsetMinutes = ((GetTimezoneOffsetRequest) request).offsetMinutes;
            logger.info("Second timezone offset from watch: {} min", offsetMinutes);
            GBApplication.getDeviceSpecificSharedPrefs(
                    getDeviceSupport().getDevice().getAddress())
                    .edit()
                    .putInt("QHYBRID_TIMEZONE_OFFSET", offsetMinutes)
                    .apply();
        } else if (request instanceof GetCurrentStepCountRequest) {
            int steps = ((GetCurrentStepCountRequest) request).steps;
            logger.debug("get current steps: {}", steps);
            try {
                File file = FileUtils.getExternalFile("qFiles/steps");
                logger.debug("Writing file {}", file.getPath());
                try (FileOutputStream fos = new FileOutputStream(file, true)) {
                    fos.write((System.currentTimeMillis() + ": " + steps + "\n").getBytes());
                }
                logger.debug("file written.");
            } catch (Exception e) {
                logger.error("error", e);
            }
            gbDevice.addDeviceInfo(new GenericItem(ITEM_STEP_COUNT, String.valueOf(((GetCurrentStepCountRequest) request).steps)));
        } else if (request instanceof OTAEnterRequest) {
            if (((OTAEnterRequest) request).success) {
                fileRequest = new OTAEraseRequest(1024 << 16);
                queueWrite(fileRequest);
            }
        } else if (request instanceof ActivityPointGetRequest) {
            gbDevice.addDeviceInfo(new GenericItem(ITEM_ACTIVITY_POINT, String.valueOf(((ActivityPointGetRequest) request).activityPoint)));
        }
        try {
            queueWrite(requestQueue.remove());
        } catch (NoSuchElementException e) {
        }
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(new Intent(DeviceManager.ACTION_DEVICES_CHANGED));
        return true;
    }


    private Request resolveAnswer(BluetoothGattCharacteristic characteristic, byte[] values) {
        if (values[0] != 3) return null;
        return responseFilters.get(values[1]);
    }

    private int pendingFileCount = 0;

    private boolean handleFileDownloadCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        // Handle alarm upload responses
        if (alarmUploadState != AlarmUploadState.NONE && characteristic.getUuid().toString().equals("3dda0003-957f-7d4a-34a6-74696673696d")) {
            if (value == null || value.length == 0) {
                logger.warn("Empty alarm response in state {}, resetting upload state", alarmUploadState);
                alarmUploadState = AlarmUploadState.NONE;
                pendingAlarmUpload = null;
                return true;
            }
            int responseType = value[0] & 0xFF;
            if (responseType == 0x0C && alarmUploadState == AlarmUploadState.INIT) {
                // Upload confirmed, send data
                logger.info("Alarm upload confirmed, sending data");
                byte[] packet = new byte[1 + pendingAlarmUpload.length];
                packet[0] = 0x00; // seq byte
                System.arraycopy(pendingAlarmUpload, 0, packet, 1, pendingAlarmUpload.length);
                getDeviceSupport().createTransactionBuilder("AlarmData")
                        .write(UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d"), packet)
                        .queue();
                alarmUploadState = AlarmUploadState.DATA_SENT;
                return true;
            } else if (responseType == 0x0F && alarmUploadState == AlarmUploadState.DATA_SENT) {
                // Upload complete, send apply command
                logger.info("Alarm upload complete, applying config");
                byte[] applyCmd = new byte[]{0x02, 0x07, 0x0F, 0x0A, 0x50, 0x08, 0x05, 0x02, 0x00, 0x00};
                getDeviceSupport().createTransactionBuilder("AlarmApply")
                        .write(UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d"), applyCmd)
                        .queue();
                alarmUploadState = AlarmUploadState.NONE;
                pendingAlarmUpload = null;
                logger.info("Alarm config applied");
                return true;
            }
            logger.warn("Unexpected alarm response 0x{} in state {}, resetting upload state",
                    String.format("%02X", responseType), alarmUploadState);
            alarmUploadState = AlarmUploadState.NONE;
            pendingAlarmUpload = null;
            return true;
        }

        Request request = fileRequest;
        if (request == null) {
            logger.warn("File characteristic data received but no pending file request");
            return true;
        }
        request.handleResponse(characteristic, value);
        if (request instanceof ListFilesRequest) {
            if (((ListFilesRequest) request).completed) {
                pendingFileCount = ((ListFilesRequest) request).fileCount;
                int totalSize = ((ListFilesRequest) request).size;
                logger.info("File listing complete: {} files, {} bytes total", pendingFileCount, totalSize);
                if (pendingFileCount == 0) {
                    finishActivitySync();
                    return true;
                }
                queueWrite(new DownloadFileRequest((short) (256 + pendingFileCount)));
            }
        } else if (request instanceof DownloadFileRequest) {
            if (((FileRequest) request).completed) {
                DownloadFileRequest downloadRequest = (DownloadFileRequest) request;
                byte[] data = downloadRequest.file;
                int handle = downloadRequest.fileHandle;
                logger.info("File handle {} downloaded: {} bytes", handle, data != null ? data.length : 0);
                if (!downloadRequest.isValid) {
                    fileRequest = null;
                    failActivitySync(downloadRequest.validationError != null
                            ? downloadRequest.validationError
                            : "Downloaded activity file was invalid");
                    return true;
                }

                if (data == null || data.length == 0) {
                    fileRequest = null;
                    failActivitySync("Downloaded activity file was empty");
                    return true;
                }

                saveRawFile(data, handle);
                try {
                    parseAndStoreActivityData(data);
                } catch (final Exception e) {
                    logger.error("Failed to import activity data from file {}", handle, e);
                    fileRequest = null;
                    failActivitySync("Failed to import downloaded activity data");
                    return true;
                }
                fileRequest = new EraseFileRequest((short) handle);
                queueWrite(fileRequest);
            }
        } else if (request instanceof EraseFileRequest) {
            if (((FileRequest) request).completed) {
                int erasedHandle = ((EraseFileRequest) request).fileHandle;
                logger.info("File handle {} erased", erasedHandle);
                if (erasedHandle > 257) {
                    queueWrite(new DownloadFileRequest((short) (erasedHandle - 1)));
                } else {
                    finishActivitySync();
                }
            }
        }
        return true;
    }

    private void finishActivitySync() {
        getDeviceSupport().getDevice().unsetBusyTask();
        GB.updateTransferNotification(null, "", false, 100, getContext());
        getDeviceSupport().getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDeviceSupport().getDevice());
    }

    private void failActivitySync(final String message) {
        logger.error("Activity sync failed: {}", message);
        GB.toast(getContext(), message, Toast.LENGTH_LONG, GB.ERROR);
        getDeviceSupport().getDevice().unsetBusyTask();
        GB.updateTransferNotification(null, "Data transfer failed", false, 0, getContext());
        getDeviceSupport().getDevice().sendDeviceUpdateIntent(getContext());
    }

    private void saveRawFile(byte[] data, int handle) {
        try {
            File dir = new File(getContext().getExternalFilesDir(null), "misfit_raw");
            dir.mkdirs();
            String name = "file_" + handle + "_" + System.currentTimeMillis() + ".bin";
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            logger.info("Raw file saved: {}", f.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to save raw file", e);
        }
    }

    private int parseAndStoreActivityData(byte[] data) throws Exception {
        // Misfit activity file v20 format:
        //   0-1: file sequence
        //   2-3: version (uint16 LE, should be 20)
        //   4-7: file size (uint32 LE)
        //   8-11: base timestamp (uint32 LE, unix epoch)
        //  12-13: unknown metadata field (not an interval count)
        //  14-15: tz offset minutes (uint16 LE)
        //  16-43: fixed metadata (28 bytes)
        //  44+: step data (uint16 LE per 1-min interval)
        //  last 4: CRC32

        if (data.length < 48) {
            throw new IllegalArgumentException("Activity data too short: " + data.length + " bytes");
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int version = buf.getShort(2) & 0xFFFF;
        int fileSize = buf.getInt(4);
        int baseTimestamp = buf.getInt(8);
        int metadataField = buf.getShort(12) & 0xFFFF;
        int tzOffset = buf.getShort(14) & 0xFFFF;

        if (version != 20) {
            throw new IllegalArgumentException("Unsupported activity file version: " + version);
        }
        if (fileSize != data.length) {
            throw new IllegalArgumentException("Activity file size mismatch: header="
                    + fileSize + ", actual=" + data.length);
        }

        logger.info("Activity file v{}: size={}, timestamp={} ({}), metadataField={}, tz=UTC+{}",
                version, fileSize, baseTimestamp, new Date((long) baseTimestamp * 1000),
                metadataField, tzOffset / 60);

        int dataStart = 44;
        int payloadBytes = data.length - dataStart - 4;
        if ((payloadBytes & 1) != 0) {
            throw new IllegalArgumentException("Activity payload length is not aligned to 16-bit intervals");
        }
        int count = payloadBytes / 2;

        if (count <= 0) {
            logger.info("No activity intervals to parse");
            return 0;
        }

        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            HybridHRActivitySampleProvider provider = new HybridHRActivitySampleProvider(
                    getDeviceSupport().getDevice(), dbHandler.getDaoSession());
            Long userId = DBHelper.getUser(dbHandler.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(getDeviceSupport().getDevice(), dbHandler.getDaoSession()).getId();

            ArrayList<HybridHRActivitySample> samples = new ArrayList<>();
            int totalSteps = 0;

            for (int i = 0; i < count; i++) {
                int raw = buf.getShort(dataStart + i * 2) & 0xFFFF;
                int lo = raw & 0xFF;
                int hi = (raw >> 8) & 0xFF;
                int steps;
                byte wearType = WEAR_TYPE_WEARING;

                if (raw == 0) {
                    steps = 0;
                    wearType = WEAR_TYPE_NOT_WEARING;
                } else if (raw == 1) {
                    steps = 0; // wearing but idle
                } else if ((lo & 1) == 1) {
                    steps = lo & 0x0E; // low-activity: bits 1-3 (max 14)
                } else {
                    steps = lo & 0xFE; // active: bits 1-7 (max 254)
                }

                int sampleTimestamp = baseTimestamp + i * 60;
                totalSteps += steps;

                HybridHRActivitySample sample = new HybridHRActivitySample(
                        sampleTimestamp,
                        deviceId,
                        userId,
                        steps,
                        0,    // calories
                        0,    // variability
                        0,    // max_variability
                        0,    // heartrate_quality
                        wearType == WEAR_TYPE_WEARING && steps > 0,
                        wearType,
                        -1         // heartRate: not measured
                );
                samples.add(sample);
            }

            if (!samples.isEmpty()) {
                HybridHRActivitySample[] sampleArray = samples.toArray(new HybridHRActivitySample[0]);
                provider.addGBActivitySamples(sampleArray);
                logger.info("Stored {} activity samples, total steps: {}", sampleArray.length, totalSteps);
            }
            return samples.size();
        }
    }

    private boolean handleFileUploadCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (uploadFileRequest == null) {
            logger.debug("no uploadFileRequest to handle response");
            return true;
        }

        uploadFileRequest.handleResponse(characteristic, value);

        switch (uploadFileRequest.state) {
            case ERROR:
                Intent fileIntent = new Intent(QHYBRID_EVENT_FILE_UPLOADED);
                fileIntent.putExtra("EXTRA_ERROR", true);
                LocalBroadcastManager.getInstance(getContext()).sendBroadcast(fileIntent);
                uploadFileRequest = null;
                break;
            case UPLOAD:
                for (byte[] packet : this.uploadFileRequest.packets) {
                    getDeviceSupport().createTransactionBuilder("File upload").write(characteristic, packet).queue();
                }
                break;
            case UPLOADED:
                fileIntent = new Intent(QHYBRID_EVENT_FILE_UPLOADED);
                LocalBroadcastManager.getInstance(getContext()).sendBroadcast(fileIntent);
                uploadFileRequest = null;
                break;
        }
        return true;
    }

    private boolean handleButtonCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value.length != 11) {
            logger.debug("wrong button message");
            return true;
        }
        int index = value[6] & 0xFF;
        int button = value[8] >> 4 & 0xFF;

        if (index != this.lastButtonIndex) {
            lastButtonIndex = index;
            logger.info("Button press on button {}", button);

            String funcKey = button == 1 ? "top_button_function"
                    : button == 3 ? "bottom_button_function"
                    : "middle_button_function";
            String function = getDeviceSpecificPreferences().getString(funcKey, "");

            Intent i = new Intent(QHYBRID_EVENT_BUTTON_PRESS);
            i.setPackage(BuildConfig.APPLICATION_ID);
            i.putExtra("BUTTON", button);

            //ByteBuffer buffer = ByteBuffer.allocate(16);
            //buffer.put(new byte[]{0x01, 0x00, 0x08});
            //buffer.put(value, 2, 8);
            //buffer.put(new byte[]{(byte)0xFF, 0x05, 0x00, 0x01, 0x00});

            //FilePutRequest request = new FilePutRequest((short)0, buffer.array());
            //for(byte[] packet : request.packets){
            //    new TransactionBuilder("File upload").write(getCharacteristic(UUID.fromString("3dda0007-957f-7d4a-34a6-74696673696d")), packet).queue(getQueue());
            //}

            getContext().sendBroadcast(i);
        }
        return true;
    }

    private void log(String message){
        logger.debug(message);
    }

    @Override
    public void setActivityHand(double progress) {
        queueWrite(new SetCurrentStepCountRequest(Math.min((int) (1000000 * progress), 999999)));
    }

    @Override
    public void vibrate(PlayNotificationRequest.VibrationType vibration) {
        queueWrite(new PlayNotificationRequest(vibration, -1, -1));
    }

    public void vibrateFindMyDevicePattern() {
        queueWrite(new VibrateRequest(false, (short) 4, (short) 1));
    }

    @Override
    public void onFindDevice(boolean start) {
        if (start) {
            vibrateFindMyDevicePattern();
        }
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        if (callSpec.command == CallSpec.CALL_INCOMING) {
            queueWrite(new VibrateRequest(false, (short) 4, (short) 1));
        }
    }

    @Override
    public void requestHandsControl() {
        queueWrite(new RequestHandControlRequest());
    }

    @Override
    public void releaseHandsControl() {
        queueWrite(new ReleaseHandsControlRequest());
    }

    @Override
    public void setHands(MoveHandsRequest.MovementConfiguration movement) {
        queueWrite(new MoveHandsRequest(movement, false));
    }

    @Override
    public void saveCalibration() {
        queueWrite(new SaveCalibrationRequest());
    }

    @Override
    public void setStepGoal(int stepGoal) {
        queueWrite(new SetStepGoalRequest(stepGoal));
    }

    @Override
    public void setVibrationStrength(short strength) {
        queueWrite(new SetVibrationStrengthRequest(strength));
    }

    @Override
    public void syncNotificationSettings() {

    }

    @Override
    public void onTestNewFunction() {

    }

    @Override
    public void setTimezoneOffsetMinutes(short offset) {
        // Protocol: 02 12 01 <offset_minutes_LE16>
        // 16-bit signed little-endian UTC offset in minutes.
        // Verified from btsnoop: London BST=3C00(+60), Washington EDT=10FF(-240), Tokyo=1C02(+540).
        ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{0x02, 0x12, 0x01});
        buf.putShort(offset);
        UUID cmdUuid = UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d");
        getDeviceSupport().createTransactionBuilder("SetSecondTimezone")
                .write(cmdUuid, buf.array())
                .queue();
    }

    @Override
    public void onInstallApp(Uri uri) {

    }

    @Override
    public boolean supportsExtendedVibration() {
        String modelNumber = getDeviceSupport().getDevice().getModel();
        switch (modelNumber) {
            case "HL.0.0":
                return false;
            default:
                return true;
        }
    }

    @Override
    public boolean supportsActivityHand() {
        String modelNumber = getDeviceSupport().getDevice().getModel();
        switch (modelNumber) {
            case "HW.0.0":
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onFetchActivityData() {
        logger.info("Fetching activity data via file download");
        queueWrite(new ListFilesRequest());
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        // These watches still expose one-shot alarms through Gadgetbridge widgets. Preserve
        // them in the per-device alarm list instead of silently turning them into daily alarms.
        if (alarms.size() == 1 && alarms.get(0).getRepetition() == 0) {
            Alarm oneshot = alarms.get(0);
            alarms = (ArrayList<? extends Alarm>) AlarmUtils.mergeOneshotToDeviceAlarms(
                    getDeviceSupport().getDevice(),
                    (nodomain.freeyourgadget.gadgetbridge.entities.Alarm) oneshot,
                    5
            );
        }

        byte[] config = buildAlarmConfig(alarms);
        pendingAlarmUpload = config;
        alarmUploadState = AlarmUploadState.INIT;

        // Send upload init command to FILE characteristic (3dda0003)
        short handle = (short) 0xA1A0;
        ByteBuffer buf = ByteBuffer.allocate(15);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x0B);
        buf.putShort(handle);
        buf.putInt(0);           // offset
        buf.putInt(config.length);  // file size
        buf.putInt(config.length);  // total size

        getDeviceSupport().createTransactionBuilder("AlarmUpload")
                .write(UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d"), buf.array())
                .queue();
    }

    private byte[] pendingAlarmUpload;

    private enum AlarmUploadState { NONE, INIT, DATA_SENT }
    private AlarmUploadState alarmUploadState = AlarmUploadState.NONE;

    private byte[] buildAlarmConfig(ArrayList<? extends Alarm> alarms) {
        int count = 0;
        for (Alarm a : alarms) {
            if (a.getEnabled() && !a.getUnused()) count++;
        }

        int totalSize = 2 + count * 3 + 4; // header + alarms + CRC32
        byte[] config = new byte[totalSize];
        config[0] = 0x00;
        config[1] = (byte) totalSize;

        int pos = 2;
        for (Alarm a : alarms) {
            if (!a.getEnabled() || a.getUnused()) continue;

            int gbDays = a.getRepetition();
            // Convert: GB Mon=0x01..Sun=0x40 → Watch Su=0x01..Sa=0x40
            int watchDays = ((gbDays << 1) & 0x7E) | ((gbDays >> 6) & 0x01);
            if (watchDays == 0) watchDays = 0x7F; // one-shot → all days (firmware needs at least one day set)
            int minute = a.getMinute() | 0x80; // enabled bit

            logger.info("Alarm: {}:{} days=0x{} enabled={}",
                    a.getHour(), a.getMinute(), Integer.toHexString(watchDays), a.getEnabled());

            config[pos++] = (byte) watchDays;
            config[pos++] = (byte) minute;
            config[pos++] = (byte) a.getHour();
        }

        CRC32 crc = new CRC32();
        crc.update(config, 0, pos);
        long crcValue = crc.getValue();
        config[pos++] = (byte) (crcValue & 0xFF);
        config[pos++] = (byte) ((crcValue >> 8) & 0xFF);
        config[pos++] = (byte) ((crcValue >> 16) & 0xFF);
        config[pos++] = (byte) ((crcValue >> 24) & 0xFF);

        return config;
    }

    @Override
    public void onSendConfiguration(String config) {
        logger.info("onSendConfiguration: {}", config);
        switch (config) {
            case ActivityUser.PREF_USER_STEPS_GOAL:
                int goal = new ActivityUser().getStepsGoal();
                logger.info("Sending step goal to watch: {}", goal);
                setStepGoal(goal);
                break;
            case DeviceSettingsPreferenceConst.PREF_VIBRATION_STRENGH_PERCENTAGE:
                int vibPref = getDeviceSpecificPreferences()
                        .getInt(DeviceSettingsPreferenceConst.PREF_VIBRATION_STRENGH_PERCENTAGE, 2);
                int strength = vibPref > 0 ? (vibPref + 1) * 25 : 0;
                logger.info("Sending vibration strength to watch: {}", strength);
                setVibrationStrength((short) strength);
                break;
        }
    }

    private SharedPreferences getDeviceSpecificPreferences() {
        return GBApplication.getDeviceSpecificSharedPrefs(
                getDeviceSupport().getDevice().getAddress()
        );
    }

    // Button config protocol (from btsnoop analysis of official Skagen app):
    //
    // Each button has its own sub-dial action code and handle base:
    //   Button 1 (top):    dial=0x2a, handle=0x30
    //   Button 2 (middle): dial=0x2b, handle=0x38
    //   Button 3 (bottom): dial=0x2c, handle=0x40
    //
    // Type 72: analog hand display — 72 <dial> <param> <param>
    //   00 00 = Date
    //   02 02 = Second Time Zone
    //   03 03 = Notifications
    //   04 04 = Alarm
    //
    // Type 41: goal animation — 41 <handle> ff
    //   = Goal Tracking
    //
    // Type 17: phone interaction — 17 <handle> 00 00
    //   = Ring Phone
    private static final byte[] DIAL_CODES = {0x2a, 0x2b, 0x2c};   // btn 1,2,3
    private static final byte[] HANDLE_BASES = {0x30, 0x38, 0x40};  // btn 1,2,3

    private byte[] getButtonConfigPayload(int buttonId, String function) {
        int idx = buttonId - 1;
        switch (function) {
            case "STEP_GOAL_COMPLETION":
                // Goal Tracking: type 41
                return new byte[]{0x02, 0x0b, 0x32, (byte) buttonId, 0x41,
                        HANDLE_BASES[idx], (byte) 0xff};
            case "TAKE_PHOTO":
            case "VOLUME_UP":
                // HID Consumer Control: Volume Up (0xE9) — key down + key up
                return new byte[]{0x02, 0x0b, 0x32, (byte) buttonId, 0x51,
                        HANDLE_BASES[idx], 0x14, (byte) 0xe9, 0x00, 0x01,
                        0x51, (byte) (HANDLE_BASES[idx] + 1), 0x15, (byte) 0xe9, 0x00, 0x00};
            case "VOLUME_DOWN":
                // HID Consumer Control: Volume Down (0xEA) — key down + key up
                return new byte[]{0x02, 0x0b, 0x32, (byte) buttonId, 0x51,
                        HANDLE_BASES[idx], 0x14, (byte) 0xea, 0x00, 0x01,
                        0x51, (byte) (HANDLE_BASES[idx] + 1), 0x15, (byte) 0xea, 0x00, 0x00};
            case "MUSIC_CONTROL":
                // HID Consumer Control: Play/Pause (0xCD) — key down + key up
                return new byte[]{0x02, 0x0b, 0x32, (byte) buttonId, 0x51,
                        HANDLE_BASES[idx], 0x14, (byte) 0xcd, 0x00, 0x01,
                        0x51, (byte) (HANDLE_BASES[idx] + 1), 0x15, (byte) 0xcd, 0x00, 0x00};
            case "RING_PHONE":
            case "FORWARD_TO_PHONE":
            case "FORWARD_TO_PHONE_MULTI":
                // Phone interaction: type 17
                return new byte[]{0x02, 0x0b, 0x32, (byte) buttonId, 0x17,
                        HANDLE_BASES[idx], 0x00, 0x00};
            default: {
                // Analog hand display: type 72
                byte param;
                switch (function) {
                    case "SECOND_TIMEZONE":
                        param = 0x02;
                        break;
                    case "LAST_NOTIFICATION":
                        param = 0x03; // Notifications
                        break;
                    case "ALARM":
                        param = 0x04;
                        break;
                    case "DATE":
                    default:
                        param = 0x00; // Date
                        break;
                }
                return new byte[]{0x02, 0x0b, 0x32, (byte) buttonId, 0x72,
                        DIAL_CODES[idx], param, param};
            }
        }
    }

    @Override
    public void overwriteButtons(String jsonConfigString) {
        SharedPreferences prefs = getDeviceSpecificPreferences();
        String topFunc = prefs.getString("top_button_function", "STEP_GOAL_COMPLETION");
        String middleFunc = prefs.getString("middle_button_function", "DATE");
        String bottomFunc = prefs.getString("bottom_button_function", "ALARM");

        logger.info("overwriteButtons: top={}, middle={}, bottom={}", topFunc, middleFunc, bottomFunc);

        UUID cmdUuid = UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d");

        // Send config for ALL 3 buttons (order from btsnoop: 3, 2, 1 — bottom first)
        // Watch applies config immediately after receiving all 3, no enable/apply needed.
        getDeviceSupport().createTransactionBuilder("ButtonConfig3")
                .write(cmdUuid, getButtonConfigPayload(3, bottomFunc)).queue();
        getDeviceSupport().createTransactionBuilder("ButtonConfig2")
                .write(cmdUuid, getButtonConfigPayload(2, middleFunc)).queue();
        getDeviceSupport().createTransactionBuilder("ButtonConfig1")
                .write(cmdUuid, getButtonConfigPayload(1, topFunc)).queue();
    }

    private void queueWrite(Request request) {
        getDeviceSupport().createTransactionBuilder(request.getClass().getSimpleName()).write(request.getRequestUUID(), request.getRequestData()).queue();
        if (request instanceof FileRequest) this.fileRequest = request;

        if (!request.expectsResponse()) {
            try {
                queueWrite(requestQueue.remove());
            } catch (NoSuchElementException e) {
            }
        }
    }
}
