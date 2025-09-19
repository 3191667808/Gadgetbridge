package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import android.content.Context;
import android.net.Uri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GpxRouteInstallHandler;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.MageneCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class MageneGpxRouteInstallHandler extends GpxRouteInstallHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MageneGpxRouteInstallHandler.class);

    public MageneGpxRouteInstallHandler(final Uri uri, final Context context) {
        super(uri, context);
    }

    @Override
    protected boolean isCompatible(GBDevice device) {
        final DeviceCoordinator coordinator = device.getDeviceCoordinator();
        if (!(coordinator instanceof MageneCoordinator mageneCoordinator)) {
            LOG.warn("Coordinator is not a MageneCoordinator: {}", coordinator.getClass());
            return false;
        }
        return true;
    }
}
