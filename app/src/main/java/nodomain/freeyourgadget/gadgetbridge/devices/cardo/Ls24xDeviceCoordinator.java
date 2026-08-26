package nodomain.freeyourgadget.gadgetbridge.devices.cardo;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.CardoDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;

public class Ls24xDeviceCoordinator extends AbstractBLEDeviceCoordinator {
    public static final String ACTION_DEVICE_STATUS_UPDATED = "nodomain.freeyourgadget.gadgetbridge.cardo.DEVICE_STATUS_UPDATED";
    private final CardoMap<ByteUtils.CardoField, Object> deviceStatus = new CardoMap<>();

    public CardoMap<ByteUtils.CardoField, Object> getDeviceStatus() {
        return deviceStatus;
    }

    @Override
    public String getManufacturer() {
        return "Cardo";
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("UCS LS2");
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(@NonNull GBDevice device) {
        return CardoDeviceSupport.class;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings deviceSpecificSettings = new DeviceSpecificSettings();
        deviceSpecificSettings.addRootScreen(R.xml.devicesettings_headphones);
        return deviceSpecificSettings;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_ls2_4x;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_supercars;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public List<DeviceCardAction> getCustomActions() {
        return Collections.singletonList(
                DeviceCardAction.forActivity(
                        R.drawable.ic_settings_applications,
                        R.string.remote_control,
                        DynamicActivity.class
                )
        );
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.INTERCOM;
    }
}
