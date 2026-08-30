/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.oneplus.OnePlusBuds4Preferences;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.serial.AbstractHeadphoneSerialDeviceSupportV2;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4AncConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4Feature;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4SubscriptionType;

public class OnePlusBuds4Support extends AbstractHeadphoneSerialDeviceSupportV2<OnePlusBuds4Protocol> {

    public OnePlusBuds4Support() {
        addSupportedService(UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5"));
    }

    @Override
    protected OnePlusBuds4Protocol createDeviceProtocol() {
        return new OnePlusBuds4Protocol(getDevice());
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        final List<OnePlusBuds4Feature> supportedFeatures = List.of(
                OnePlusBuds4Feature.GAME_MODE,
                OnePlusBuds4Feature.DUAL_CONNECTION,
                OnePlusBuds4Feature.SPATIAL_AUDIO,
                OnePlusBuds4Feature.WEAR_DETECTION,
                OnePlusBuds4Feature.AUTO_PLAY_PAUSE,
                OnePlusBuds4Feature.THREE_D_AUDIO,
                OnePlusBuds4Feature.UNKNOWN_1D,
                OnePlusBuds4Feature.UNKNOWN_1C
        );
        final EnumSet<OnePlusBuds4SubscriptionType> supportedSubscriptions = EnumSet.of(
                OnePlusBuds4SubscriptionType.BATTERY,
                OnePlusBuds4SubscriptionType.ANC_SELECTOR,
                OnePlusBuds4SubscriptionType.STATUS,
                OnePlusBuds4SubscriptionType.UNKNOWN_04,
                OnePlusBuds4SubscriptionType.UNKNOWN_08,
                OnePlusBuds4SubscriptionType.EQUALIZER,
                OnePlusBuds4SubscriptionType.ALARM_VOLUME,
                OnePlusBuds4SubscriptionType.ONEPLUS_SETTINGS_1,
                OnePlusBuds4SubscriptionType.ONEPLUS_SETTINGS_2,
                OnePlusBuds4SubscriptionType.ONEPLUS_SETTINGS_3
        );

        builder.write(mDeviceProtocol.encodeBatteryReq());
        builder.write(mDeviceProtocol.encodeSubscriptionQuery());
        builder.write(mDeviceProtocol.encodeSubscriptionSet(supportedSubscriptions));
        builder.write(mDeviceProtocol.encodeSettingsQuery());
        builder.write(mDeviceProtocol.encodeMiscConfigReq(supportedFeatures));
        builder.write(mDeviceProtocol.encodeTouchConfigReq());
        builder.write(mDeviceProtocol.encodeAncConfigReq(OnePlusBuds4AncConfigType.MODE));
        builder.write(mDeviceProtocol.encodeAncConfigReq(OnePlusBuds4AncConfigType.TOUCH_CYCLE_MODES));
        builder.write(mDeviceProtocol.encodeEqQuery());
        builder.write(mDeviceProtocol.encodeBassBoostReq());
        builder.write(mDeviceProtocol.encodeAlarmVolumeReq2());
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public void onFindDevice(final boolean start) {
        final TransactionBuilder builder = createTransactionBuilder("find device");
        builder.write(mDeviceProtocol.encodeFindDevice(start));
        builder.queue();
    }

    @Override
    public void onReadConfiguration(final String config) {
        if (OnePlusBuds4Preferences.EQ_ACTIVE_STATE_QUERY.equals(config)) {
            final TransactionBuilder builder = createTransactionBuilder("eq active state query");
            builder.write(mDeviceProtocol.encodeEqActiveStateReq());
            builder.queue();
        } else if (OnePlusBuds4Preferences.ALARM_VOLUME_QUERY.equals(config)) {
            final TransactionBuilder builder = createTransactionBuilder("alarm volume query");
            builder.write(mDeviceProtocol.encodeAlarmVolumeReq2());
            builder.queue();
        } else {
            super.onReadConfiguration(config);
        }
    }
}
