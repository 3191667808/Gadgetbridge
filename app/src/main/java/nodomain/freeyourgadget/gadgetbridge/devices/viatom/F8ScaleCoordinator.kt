package nodomain.freeyourgadget.gadgetbridge.devices.viatom;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.viatom.F8Support;

public class F8ScaleCoordinator  extends AbstractBLEDeviceCoordinator  {

    @Nullable
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("F8");
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public String getManufacturer() {
        return "viatom";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return F8Support.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_f8;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.SCALE;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_miscale;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(GBDevice device) {
        final DeviceSpecificSettings deviceSpecificSettings = new DeviceSpecificSettings();

        final List<Integer> generic = deviceSpecificSettings.addRootScreen(DeviceSpecificSettingsScreen.GENERIC);
        generic.add(R.xml.devicesettings_scale_user1);
        generic.add(R.xml.devicesettings_scale_user2);

        return deviceSpecificSettings;
    }

    @Override
    public boolean supportsWeightMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsCharts(@NonNull GBDevice device) {
        return true;
    }
}
