package nodomain.freeyourgadget.gadgetbridge.service.devices._1more_sonoflow;

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

public class _1MoreSonoFlowIOThread extends BtClassicIoThread  {
    private static final Logger LOG = LoggerFactory.getLogger(_1MoreSonoFlowIOThread.class);

    private final _1MoreSonoFlowProtocol _1MoreSonoFlowProtocol;

    public _1MoreSonoFlowIOThread(
            GBDevice gbDevice,
            Context context,
            _1MoreSonoFlowProtocol deviceProtocol,
            AbstractSerialDeviceSupport deviceSupport,
            BluetoothAdapter btAdapter
    ) {
        super(gbDevice, context, deviceProtocol, deviceSupport, btAdapter);

        _1MoreSonoFlowProtocol = deviceProtocol;
    }

    @Override
    protected void initialize() {
        super.initialize();

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
