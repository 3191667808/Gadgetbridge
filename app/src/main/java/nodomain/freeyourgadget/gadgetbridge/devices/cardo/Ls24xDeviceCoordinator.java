package nodomain.freeyourgadget.gadgetbridge.devices.cardo;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
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
    protected void deleteDevice(@NonNull GBDevice gbDevice, @NonNull Device device, @NonNull DaoSession session) throws GBException {

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
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return CardoDeviceSupport.class;
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
        return Collections.singletonList(new Ls24xDeviceCoordinator.ControlDeviceCardAction());
    }

    private static final class ControlDeviceCardAction implements DeviceCardAction {
        @Override
        public int getIcon(GBDevice device) {
            return R.drawable.ic_steering_wheel;
        } //TODO Changeme

        @Override
        public String getDescription(final GBDevice device, final Context context) {
            return context.getString(R.string.remote_control);
        }

        @Override
        public void onClick(final GBDevice device, final Context context) {
            final Intent startIntent = new Intent(context, DynamicActivity.class);
            startIntent.putExtra(GBDevice.EXTRA_DEVICE, device);
            context.startActivity(startIntent);
        }
    }
}
