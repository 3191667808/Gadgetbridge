package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Watchface;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiSupportProvider;

public class SendWatchfacePhotoInfo extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(SendWatchfacePhotoInfo.class);

    private final String backgroundName;
    private final int positionIndex;
    private final int styleIndex;
    private final int valueTypeIndex;
    private final Callback callback;

    public interface Callback {
        void onPhotoInfoResponse(int status, int transferCount);
    }

    public SendWatchfacePhotoInfo(HuaweiSupportProvider support, String backgroundName,
                                  int positionIndex, int styleIndex, int valueTypeIndex,
                                  Callback callback) {
        super(support);
        this.serviceId = Watchface.id;
        this.commandId = Watchface.WatchfacePhotoInfo.id;
        this.backgroundName = backgroundName;
        this.positionIndex = positionIndex;
        this.styleIndex = styleIndex;
        this.valueTypeIndex = valueTypeIndex;
        this.callback = callback;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new Watchface.WatchfacePhotoInfo.Request(this.paramsProvider, this.backgroundName,
                    this.positionIndex, this.styleIndex, this.valueTypeIndex).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (receivedPacket instanceof Watchface.WatchfacePhotoInfo.Response) {
            Watchface.WatchfacePhotoInfo.Response resp = (Watchface.WatchfacePhotoInfo.Response) receivedPacket;
            LOG.info("Photo info response: status={}, transferCount={}", resp.result, resp.transferCount);
            if (callback != null) {
                callback.onPhotoInfoResponse(resp.result, resp.transferCount);
            }
        }
    }
}
