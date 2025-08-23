package nodomain.freeyourgadget.gadgetbridge.devices.protocentral;

import java.util.regex.Pattern;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.UnknownDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.protocentral.HealthyPiMoveSupport;

public class HealthyPiMoveCoordinator extends AbstractBLEDeviceCoordinator {

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_protocentral_healthypi_move;
    }

    @Override
    public String getManufacturer() {
        return "Protocentral";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return HealthyPiMoveSupport.class;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("healthypi move");
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_BOND;
    }

    @Nullable
    @Override
    public Class<? extends Activity> getPairingActivity() {
        return null;
    }

    @Override
    protected void deleteDevice(@NonNull GBDevice gbDevice,
                                @NonNull Device device,
                                @NonNull DaoSession session) throws GBException {
    }

    @Override
    public boolean supportsWeather(final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsFlashing(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsHeartRateMeasurement(GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStepCounter(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsTemperatureMeasurement(final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsMusicInfo(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsNavigation(@NonNull final GBDevice device) {
        return false;
    }
}
