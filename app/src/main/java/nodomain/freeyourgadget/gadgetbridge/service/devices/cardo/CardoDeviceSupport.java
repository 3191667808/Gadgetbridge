package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.cardo.Ls24xDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractBLEHeadphoneDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.ConfigMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.SetRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

import static nodomain.freeyourgadget.gadgetbridge.devices.cardo.Ls24xDeviceCoordinator.ACTION_DEVICE_STATUS_UPDATED;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioTuneRequest.SCAN_DOWN;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioTuneRequest.SCAN_UP;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioTuneRequest.SEEK_DOWN;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio.FmRadioTuneRequest.SEEK_UP;

public class CardoDeviceSupport extends AbstractBLEHeadphoneDeviceSupport {
    public static final String EXTRA_CONTROL_ID = "CONTROL_ID";
    public static final String EXTRA_VALUE = "EXTRA_VALUE";
    public static final String EXTRA_TOGGLE_STATE = "EXTRA_TOGGLE_STATE";
    public static final String EXTRA_SELECTED_INDEX = "EXTRA_SELECTED_INDEX";
    private static final String COMMAND_PREFIX = "nodomain.freeyourgadget.gadgetbridge.cardo.command";
    public static final String COMMAND_SET_VALUE = COMMAND_PREFIX + ".SET_VALUE";
    public static final String COMMAND_BUTTON_PRESSED = COMMAND_PREFIX + ".BUTTON_PRESSED";
    public static final String COMMAND_TOGGLE_CHANGED = COMMAND_PREFIX + ".TOGGLE_CHANGED";
    public static final String COMMAND_DROPDOWN_CHANGED = COMMAND_PREFIX + ".DROPDOWN_CHANGED";

    private static final Logger LOG = LoggerFactory.getLogger(CardoDeviceSupport.class);
    private static final UUID SERVICE_UUID = UUID.fromString("CD007F80-8B0B-11E6-AE22-56B6B6499611");
    private static final UUID UUID_READ_CHARACTERISTIC = UUID.fromString("cd007f82-8b0b-11e6-ae22-56b6b6499611");

    private final CardoBLEProfile<CardoDeviceSupport> cardoBLEProfile;
    BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String control = (String) intent.getSerializableExtra(EXTRA_CONTROL_ID);
            final Object value = intent.getSerializableExtra(EXTRA_VALUE);

            if (control == null) {
                LOG.warn("Received control was null");
                return;
            }

            if (value == null) {
                LOG.warn("Received control {} but value was null", control);
                return;
            }

            final Object oldValue = ((Ls24xDeviceCoordinator) getDevice().getDeviceCoordinator()).getDeviceStatus().getValueByName(control);

            switch (control) {
                case "fmState":
                    cardoBLEProfile.toggleFmRadioPower((boolean) value);
                    break;
                case "Tuner":
                    cardoBLEProfile.tuneFrequency(((Number) value).intValue());
                    break;
                case "seek_up":
                    cardoBLEProfile.tune(((Number) value).intValue() == 1 ? SCAN_UP : SEEK_UP);
                    break;
                case "seek_down":
                    cardoBLEProfile.tune(((Number) value).intValue() == 1 ? SCAN_DOWN : SEEK_DOWN);
                    break;
                default:
                    LOG.debug("No special handling for control: {}", control);
            }

            if (oldValue != null) {
                LOG.debug("Should update control {}: {} -> {}", control, oldValue, value);

                final CardoMap<ByteUtils.CardoField, Object> updatedValues = new CardoMap<>();
                updatedValues.put(((Ls24xDeviceCoordinator) getDevice().getDeviceCoordinator()).getDeviceStatus().getFieldByName(control), value);

                //these volumes are stored twice (possibly left/right ear?) but should set to the same value otherwise update does not apply
                if (control.equals("ag1Volume"))
                    updatedValues.put(((Ls24xDeviceCoordinator) getDevice().getDeviceCoordinator()).getDeviceStatus().getFieldByName("ag2Volume"), value);
                if (control.equals("a2dp1Volume"))
                    updatedValues.put(((Ls24xDeviceCoordinator) getDevice().getDeviceCoordinator()).getDeviceStatus().getFieldByName("a2dp2Volume"), value);

                List<ConfigMessage.InfoType> filteredList = Arrays.stream(ConfigMessage.InfoType.values()).filter(infoType -> infoType.containsFieldWithName(control)).collect(Collectors.toList());
                LOG.debug("Messages to send: {}", filteredList);
                filteredList.forEach(infoType -> updateField(infoType, ((Ls24xDeviceCoordinator) getDevice().getDeviceCoordinator()).getDeviceStatus(), updatedValues));
            }


        }
    };

    public CardoDeviceSupport() {
        super(LOG);
        addSupportedService(SERVICE_UUID);
        cardoBLEProfile = new CardoBLEProfile<>(this);
        addSupportedProfile(cardoBLEProfile);
    }

    @Override
    public void onReset(final int flags) {
        if ((flags & GBDeviceProtocol.RESET_FLAGS_FACTORY_RESET) != 0) {
            cardoBLEProfile.factoryReset();
        } else {
            LOG.warn("Unknown reset flags: {}", String.format("0x%x", flags));
        }
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(getContext());
        IntentFilter filter = new IntentFilter();
        filter.addAction(COMMAND_SET_VALUE);
        filter.addAction(COMMAND_BUTTON_PRESSED);
        filter.addAction(COMMAND_DROPDOWN_CHANGED);
        filter.addAction(COMMAND_TOGGLE_CHANGED);
        broadcastManager.registerReceiver(commandReceiver, filter);
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");
        builder.requestMtu(64);
        builder.notify(getCharacteristic(UUID_READ_CHARACTERISTIC), true);
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));

        cardoBLEProfile.initialize(builder);
        cardoBLEProfile.getFirmwareVersion(builder);
        cardoBLEProfile.subscribe(builder);
        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        super.onCharacteristicChanged(gatt, characteristic, value);

        UUID characteristicUUID = characteristic.getUuid();

        if (UUID_READ_CHARACTERISTIC.equals(characteristicUUID)) {
            CardoResponse response = new CardoResponse(value);
            for (GBDeviceEvent ev : response.getDeviceEvents()) {
                evaluateGBDeviceEvent(ev);
            }
            ((Ls24xDeviceCoordinator) getDevice().getDeviceCoordinator()).getDeviceStatus().putAll(response.getDeviceStatus());
            LOG.debug("INCOMING msg: {}", GB.hexdump(value));
//            LOG.debug("DEVICE STATUS: {}", response.getDeviceStatus());

            Intent intent = new Intent(ACTION_DEVICE_STATUS_UPDATED);
            LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
            return true;
        }
        LOG.warn("Unhandled read {} -> {}", characteristicUUID, GB.hexdump(value));
        return false;
    }

    @Override
    public void onTestNewFunction() {
        cardoBLEProfile.tuneFrequency(9150);
    }

    public void updateField(ConfigMessage.InfoType infoType, CardoMap<ByteUtils.CardoField, Object> deviceStatus, CardoMap<ByteUtils.CardoField, Object> updateValues) {
        final CardoMap<ByteUtils.CardoField, Object> values = new CardoMap<>();

        infoType.getFields().forEach(cardoField -> {
            values.put(cardoField, deviceStatus.get(cardoField));
            if (updateValues.containsKey(cardoField))
                values.put(cardoField, updateValues.get(cardoField));
        });

        final byte[] payload = ByteUtils.constructStructure(values, infoType);

        SetRequest setRequest = new SetRequest(infoType, payload);

        cardoBLEProfile.sendOutgoingRequest("AAA", setRequest);
        LOG.debug("RECONSTRUCTED: {}", GB.hexdump(setRequest.getBtMessage()));

    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public void dispose() {
        super.dispose();
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(commandReceiver);
    }
}
