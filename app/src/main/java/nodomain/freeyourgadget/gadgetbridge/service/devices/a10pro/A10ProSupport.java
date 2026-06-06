/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Contact;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** BLE support for the FreeFit iEnjoy V2 / G2-ADV family. */
public class A10ProSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(A10ProSupport.class);

    static final UUID SERVICE_UUID = UUID.fromString("6e40fc00-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID WRITE_UUID = UUID.fromString("6e40fc20-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NOTIFY_UUID = UUID.fromString("6e40fc21-b5a3-f393-e0a9-e50e24dcca9e");

    private final A10ProProtocol protocol;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    public A10ProSupport() {
        super(LOG);
        addSupportedService(SERVICE_UUID);
        protocol = new A10ProProtocol(null);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @NonNull
    @Override
    protected TransactionBuilder initializeDevice(@NonNull final TransactionBuilder builder) {
        getDevice().setFirmwareVersion("?");
        writeCharacteristic = getCharacteristic(WRITE_UUID);
        notifyCharacteristic = getCharacteristic(NOTIFY_UUID);

        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(notifyCharacteristic, true);
        write(builder, protocol.encodeSyncTime(prefIs24Hour(), 0));
        write(builder, protocol.encodeGetFunction());
        write(builder, protocol.encodeGetFirmwareVersion());
        write(builder, protocol.encodeClassicBatteryQuery());
        write(builder, protocol.encodeQueryBattery());
        write(builder, protocol.encodeQueryAnc());
        write(builder, protocol.encodeQueryAudio());
        write(builder, protocol.encodeQueryEq());
        write(builder, protocol.encodeQueryKeyCode());
        write(builder, protocol.encodeQueryBlueName());
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] data) {
        super.onCharacteristicChanged(gatt, characteristic, data);
        if (data == null || data.length == 0) return false;
        LOG.debug("FreeFit V2 RX {}", GB.hexdump(data));
        final GBDeviceEvent[] events = protocol.decodeResponse(data);
        for (final GBDeviceEvent event : events) {
            if (event != null) handleGBDeviceEvent(event);
        }
        if ((data[0] & 0xFF) == (A10ProProtocol.CMD_DEVICE_REQUEST_SYNC_TIME & 0xFF)) {
            send("sync time on request", protocol.encodeSyncTime(prefIs24Hour(), 0));
        }
        return true;
    }

    @Override
    public void onSetTime() {
        send("sync time", protocol.encodeSyncTime(prefIs24Hour(), 0));
    }

    @Override
    public void onFindDevice(final boolean start) {
        send("find earbuds", protocol.encodeFindHeadphones(start ? 3 : 0));
    }

    @Override
    public void onPowerOff() {
        send("power off", protocol.encodeTurnOff(2));
    }

    @Override
    public void onReset(final int flags) {
        send("factory reset", protocol.encodeReset());
    }

    @Override
    public void onNotification(final NotificationSpec notificationSpec) {
        if (notificationSpec == null) return;
        final StringBuilder text = new StringBuilder();
        if (notificationSpec.sourceName != null) text.append(notificationSpec.sourceName).append(": ");
        if (notificationSpec.title != null) text.append(notificationSpec.title);
        if (notificationSpec.body != null && !notificationSpec.body.isEmpty()) {
            if (text.length() > 0) text.append(" - ");
            text.append(notificationSpec.body);
        }
        final int appId = notificationSpec.type != null ? notificationSpec.type.ordinal() & 0xFF : 0;
        sendAll("notification", protocol.encodeNotification(appId, text.toString(), protocol.getFamily()));
    }

    @Override
    public void onSetContacts(final ArrayList<? extends Contact> contacts) {
        if (contacts == null || contacts.isEmpty()) return;
        sendAll("contacts", protocol.encodeContacts(contacts));
    }

    @Override
    public void onSendWeather() {
        final WeatherSpec weather = Weather.getWeatherSpec();
        if (weather == null) return;
        final int current = kelvinToCelsius(weather.getCurrentTemp());
        final int max = kelvinToCelsius(weather.getTodayMaxTemp() == 0 ? weather.getCurrentTemp() : weather.getTodayMaxTemp());
        final int min = kelvinToCelsius(weather.getTodayMinTemp() == 0 ? weather.getCurrentTemp() : weather.getTodayMinTemp());
        final int code = mapWeatherCode(weather.getCurrentConditionCode());
        final int windLevel = Math.max(0, Math.min(12, weather.windSpeedAsBeaufort()));
        final int windDirection = mapWindDirection(weather.getWindDirection());
        final String city = weather.getLocation() == null || weather.getLocation().isEmpty() ? "Weather" : weather.getLocation();
        sendAll("weather", protocol.encodeWeatherForFamily(protocol.getFamily(), code, current, max, min,
                weather.getCurrentHumidity(), windLevel, windDirection, city));
    }

    @Override
    public void onSendConfiguration(final String config) {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if ("pref_a10pro_anc_mode".equals(config)) {
            send("set ANC", protocol.encodeSetAnc(parseInt(prefs.getString(config, "0"), 0)));
        } else if ("pref_a10pro_audio_model".equals(config)) {
            send("set audio model", protocol.encodeSetAudioModel(parseInt(prefs.getString(config, "0"), 0)));
        } else if ("pref_a10pro_find_earphones".equals(config)) {
            send("find earbuds", protocol.encodeFindHeadphones(3));
        } else {
            super.onSendConfiguration(config);
        }
    }

    private void send(final String label, final byte[] frame) {
        if (frame == null) return;
        try {
            final TransactionBuilder builder = performInitialized(label);
            write(builder, frame);
            builder.queue();
        } catch (final IOException e) {
            LOG.warn("Unable to send FreeFit V2 {}", label, e);
        }
    }

    private void sendAll(final String label, final List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) return;
        try {
            final TransactionBuilder builder = performInitialized(label);
            for (final byte[] frame : frames) {
                write(builder, frame);
                builder.sleep(100);
            }
            builder.queue();
        } catch (final IOException e) {
            LOG.warn("Unable to send FreeFit V2 {}", label, e);
        }
    }

    private void write(final TransactionBuilder builder, final byte[] frame) {
        if (frame == null) return;
        LOG.debug("FreeFit V2 TX {}", GB.hexdump(frame));
        builder.write(writeCharacteristic != null ? writeCharacteristic : getCharacteristic(WRITE_UUID), frame);
    }

    private boolean prefIs24Hour() {
        try {
            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
            final String fmt = prefs.getString(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT, "auto");
            if (DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_12H.equals(fmt)) return false;
        } catch (final Exception ignored) {
        }
        return true;
    }

    private int kelvinToCelsius(final int kelvin) {
        if (kelvin == 0) return 0;
        return Math.round((float) (kelvin - 273.15));
    }

    private int mapWeatherCode(final int openWeatherCode) {
        if (openWeatherCode >= 200 && openWeatherCode < 300) return 12;
        if (openWeatherCode >= 300 && openWeatherCode < 600) return 15;
        if (openWeatherCode >= 600 && openWeatherCode < 700) return 24;
        if (openWeatherCode >= 700 && openWeatherCode < 800) return 10;
        if (openWeatherCode == 800) return 0;
        if (openWeatherCode == 801) return 1;
        if (openWeatherCode == 802) return 2;
        if (openWeatherCode == 803) return 3;
        if (openWeatherCode == 804) return 4;
        return 0;
    }

    private int mapWindDirection(final int degrees) {
        if (degrees < 0) return 0;
        return ((degrees + 22) / 45) % 8;
    }

    private int parseInt(final String value, final int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (final Exception e) {
            return fallback;
        }
    }
}
