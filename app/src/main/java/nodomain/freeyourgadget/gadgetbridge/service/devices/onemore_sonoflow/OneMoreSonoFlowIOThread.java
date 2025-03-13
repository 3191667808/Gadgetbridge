package nodomain.freeyourgadget.gadgetbridge.service.devices.onemore_sonoflow;

import static nodomain.freeyourgadget.gadgetbridge.util.GB.hexdump;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btclassic.BtClassicIoThread;
import nodomain.freeyourgadget.gadgetbridge.service.serial.AbstractSerialDeviceSupport;

public class OneMoreSonoFlowIOThread extends BtClassicIoThread  {
    private static final Logger LOG = LoggerFactory.getLogger(OneMoreSonoFlowIOThread.class);

    private final OneMoreSonoFlowProtocol oneMoreSonoFlowProtocol;

    public OneMoreSonoFlowIOThread(
            GBDevice gbDevice,
            Context context,
            OneMoreSonoFlowProtocol deviceProtocol,
            AbstractSerialDeviceSupport deviceSupport,
            BluetoothAdapter btAdapter
    ) {
        super(gbDevice, context, deviceProtocol, deviceSupport, btAdapter);

        oneMoreSonoFlowProtocol = deviceProtocol;
    }

    @Override
    protected void initialize() {
        super.initialize();

        // battery
        write(new byte[] { 0x11, 0x01, 0x00, 0x4e, 0x00, 0x00, 0x00, 0x1c, 0x42 });

        // noise control
        write(new byte[] { 0x11, 0x01, 0x00, 0x5f, 0x00, 0x00, 0x00, 0x0c, 0x43 });

        // ldac
        write(new byte[] { 0x11, 0x01, 0x00, 0x6c, 0x00, 0x00, 0x00, 0x0d, 0x71 });

        setUpdateState(GBDevice.State.INITIALIZED);
    }

    @Override
    protected byte[] parseIncoming(InputStream stream) throws IOException {
        byte[] buffer = new byte[1048576];
        int bytes = stream.read(buffer);
        LOG.debug("read " + bytes + " bytes. " + hexdump(buffer, 0, bytes));

        return Arrays.copyOf(buffer, bytes);
    }
}
