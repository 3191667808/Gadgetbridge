package nodomain.freeyourgadget.gadgetbridge.devices.magene;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.magene.MageneGpxRouteInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.magene.MageneSupport;

public class MageneCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    public String getManufacturer() {
        return "Magene";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return MageneSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_magene_c606;
    }

    @Override
    public int getBondingStyle(){
        return BONDING_STYLE_LAZY;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("C606.*|C706.*");
    }

    @Override
    public boolean supportsFlashing(@NonNull GBDevice device) {
        return true;
    }

    @Nullable
    @Override
    public InstallHandler findInstallHandler(Uri uri, Bundle options, Context context) {

        final MageneGpxRouteInstallHandler mageneGpxRouteInstallHandler = new MageneGpxRouteInstallHandler(uri, context);
        if (mageneGpxRouteInstallHandler.isValid())
            return mageneGpxRouteInstallHandler;
        return null;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.BIKE_COMPUTER;
    };
}
