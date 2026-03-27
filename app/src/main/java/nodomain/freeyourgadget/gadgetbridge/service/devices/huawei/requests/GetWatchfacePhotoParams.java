package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Watchface;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiSupportProvider;

public class GetWatchfacePhotoParams extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(GetWatchfacePhotoParams.class);

    private final Callback callback;

    public interface Callback {
        void onPhotoParams(int backgroundImageType, int backgroundImageOption,
                           int positionIndex, int styleIndex, int valueTypeIndex);
    }

    public GetWatchfacePhotoParams(HuaweiSupportProvider support, Callback callback) {
        super(support);
        this.serviceId = Watchface.id;
        this.commandId = Watchface.WatchfacePhotoParams.id;
        this.callback = callback;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new Watchface.WatchfacePhotoParams.Request(this.paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (receivedPacket instanceof Watchface.WatchfacePhotoParams.Response) {
            Watchface.WatchfacePhotoParams.Response resp = (Watchface.WatchfacePhotoParams.Response) receivedPacket;
            LOG.info("Photo params: backgroundImageType={}, backgroundImageOption={}, maxBackgroundImages={}, " +
                            "canIntellectColor={}, positionIndex={}, styleIndex={}, valueTypeIndex={}",
                    resp.backgroundImageType, resp.backgroundImageOption, resp.maxBackgroundImages,
                    resp.canIntellectColor, resp.positionIndex, resp.styleIndex, resp.valueTypeIndex);
            if (callback != null) {
                callback.onPhotoParams(resp.backgroundImageType, resp.backgroundImageOption,
                        resp.positionIndex, resp.styleIndex, resp.valueTypeIndex);
            }
        }
    }
}
