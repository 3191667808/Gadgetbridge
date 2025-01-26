package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo;

import org.apache.commons.lang3.EnumUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.cardo.Ls24xDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.AbstractBleProfile;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmRegion;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.ConfigMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.SubscribeMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioToggleRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioTuneRequest;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class CardoBLEProfile<T extends AbstractBTLESingleDeviceSupport> extends AbstractBleProfile<T> {
    private static final Logger LOG = LoggerFactory.getLogger(CardoBLEProfile.class);

    private static final UUID UUID_WRITE_CHARACTERISTIC = UUID.fromString("cd007f81-8b0b-11e6-ae22-56b6b6499611");

    private final CardoDeviceSupport cardoDeviceSupport;

    public CardoBLEProfile(final T support) {
        super(support);
        this.cardoDeviceSupport = (CardoDeviceSupport) support;
    }

    public void initialize(final TransactionBuilder builder) {
        CardoRequest req = new CardoRequest(CardoMessage.INIT, new byte[]{0, 0});

        builder.write(getCharacteristic(UUID_WRITE_CHARACTERISTIC), req.getBtMessage());
    }

    public void getFirmwareVersion(final TransactionBuilder builder) {
        CardoRequest req = new CardoRequest(CardoMessage.GET, new byte[]{
                (byte) ConfigMessage.InfoType.FIRMWARE.getTypeId(),
                (byte) ConfigMessage.InfoType.LANGUAGE_LIST.getTypeId(),
                (byte) ConfigMessage.InfoType.VOLUMES.getTypeId(),
                (byte) ConfigMessage.InfoType.CONFIG.getTypeId()});

        builder.write(getCharacteristic(UUID_WRITE_CHARACTERISTIC), req.getBtMessage());
    }

    public void subscribe(final TransactionBuilder builder) {
        CardoRequest req = new CardoRequest(CardoMessage.SUBSCRIBE);
        req.appendPayload(EnumUtils.generateBitVector(SubscribeMessage.Services.class, SubscribeMessage.Services.knownValues()), 1);
        req.appendPayload(EnumUtils.generateBitVector(SubscribeMessage.SpecificServices.class, SubscribeMessage.SpecificServices.knownValues()), 2);

        builder.write(getCharacteristic(UUID_WRITE_CHARACTERISTIC), req.getBtMessage());
    }

    public void toggleFmRadioPower(boolean on) {
        sendOutgoingRequest("toggle Fm Radio", new FmRadioToggleRequest(on));
    }

    public void tuneFrequency(int frequency) {
        sendOutgoingRequest("tune freq", new FmRadioTuneRequest(FmRadioTuneRequest.TUNE_FREQ, frequency, ((CardoFmRegion) ((Ls24xDeviceCoordinator) this.cardoDeviceSupport.getDevice().getDeviceCoordinator()).getDeviceStatus().getValueByName("fmRegion"))));
    }

    public void tune(int request) {
        sendOutgoingRequest("tune operation", new FmRadioTuneRequest(request));
    }

    public void factoryReset() {
        sendOutgoingRequest("factory reset", new CardoRequest(CardoMessage.CONTROL, new byte[]{0x05, 0x55}));
    }

    public void sendOutgoingRequest(String taskName, CardoRequest cardoRequest) {
        final TransactionBuilder builder = new TransactionBuilder(taskName);
        LOG.debug("SENDING {}: {}", taskName, GB.hexdump(cardoRequest.getBtMessage()));

        builder.write(getCharacteristic(UUID_WRITE_CHARACTERISTIC), cardoRequest.getBtMessage());

        builder.queue(this.cardoDeviceSupport.getQueue());

    }


}
