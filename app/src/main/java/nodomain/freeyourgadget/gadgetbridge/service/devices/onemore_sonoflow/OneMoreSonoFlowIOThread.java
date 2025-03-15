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

        // get some device information
        write(OneMorePacket.createGetDeviceInfoPacket());
        write(OneMorePacket.createGetNoiseControlModePacket());
        write(OneMorePacket.createGetLdacModePacket());
        write(OneMorePacket.createGetDualDeviceModePacket());

        setUpdateState(GBDevice.State.INITIALIZED);
    }

    @Override
    protected byte[] parseIncoming(InputStream stream) throws IOException {
        byte[] buffer = new byte[1048576];      // big value
        int bytes = stream.read(buffer);
        LOG.debug("read " + bytes + " bytes. " + hexdump(buffer, 0, bytes));

        return Arrays.copyOf(buffer, bytes);
    }
}
