package nodomain.freeyourgadget.gadgetbridge.service.devices.coros;

import android.content.Intent;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.coros.CorosConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;

/**
 * Read-only support for Coros Pace 3 / Pace 4. The watch's custom GATT
 * channels require a signed application-layer handshake we cannot
 * synthesize, so we only expose Device Information and Battery via the
 * standard GATT services.
 */
public abstract class AbstractCorosSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractCorosSupport.class);

    private final DeviceInfoProfile<AbstractCorosSupport> deviceInfoProfile;
    private final BatteryInfoProfile<AbstractCorosSupport> batteryInfoProfile;
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();

    protected AbstractCorosSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);

        IntentListener listener = new IntentListener() {
            @Override
            public void notify(Intent intent) {
                final String action = intent.getAction();
                if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(action)) {
                    handleDeviceInfo(Objects.requireNonNull(
                            intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO)));
                } else if (BatteryInfoProfile.ACTION_BATTERY_INFO.equals(action)) {
                    handleBatteryInfo(Objects.requireNonNull(
                            intent.getParcelableExtra(BatteryInfoProfile.EXTRA_BATTERY_INFO)));
                }
            }
        };

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(listener);
        addSupportedProfile(deviceInfoProfile);

        batteryInfoProfile = new BatteryInfoProfile<>(this);
        batteryInfoProfile.addListener(listener);
        addSupportedProfile(batteryInfoProfile);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @NonNull
    protected abstract String getHardwareName();

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));
        deviceInfoProfile.requestDeviceInfo(builder);
        batteryInfoProfile.requestBatteryInfo(builder);
        batteryInfoProfile.enableNotify(builder, true);
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        return builder;
    }

    private void handleDeviceInfo(@NonNull DeviceInfo info) {
        if (info.getFirmwareRevision() != null) {
            versionCmd.fwVersion = info.getFirmwareRevision().trim();
        }
        versionCmd.hwVersion = getHardwareName();
        if (info.getHardwareRevision() != null
                && !info.getHardwareRevision().trim().isEmpty()) {
            versionCmd.hwVersion = getHardwareName()
                    + " (" + info.getHardwareRevision().trim() + ")";
        }
        handleGBDeviceEvent(versionCmd);
    }

    private void handleBatteryInfo(@NonNull BatteryInfo info) {
        batteryCmd.level = info.getPercentCharged();
        handleGBDeviceEvent(batteryCmd);
    }
}
