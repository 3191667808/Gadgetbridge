package nodomain.freeyourgadget.gadgetbridge.devices.canon;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.canon.CanonEOS200DDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.unknown.UnknownDeviceSupport;

public class CanonEOS200DDeviceCoordinator extends AbstractDeviceCoordinator
{
    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_canon_eos200D;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_camera;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return null;
    }

    @Override
    public String getManufacturer() {
        return "Canon";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return CanonEOS200DDeviceSupport.class;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("EOS200D");
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_BOND;
    }

    @Override
    public int getBatteryCount(final GBDevice device) {
        return 0;
    } //not possible to read

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[]{R.xml.devicesettings_canon_location};
    }

}
