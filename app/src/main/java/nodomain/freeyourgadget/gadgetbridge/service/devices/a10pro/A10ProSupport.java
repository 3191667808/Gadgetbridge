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
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.Contact;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro.jl_rcsp.JieliRcspAuthSession;
import nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro.jl_rcsp.RcspFrame;
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
    private final JieliRcspAuthSession authSession = new JieliRcspAuthSession();

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
        if (prefRcspAuthEnabled()) {
            authSession.reset();
            LOG.info("FreeFit V2: attempting JieLi RCSP authentication");
            write(builder, authSession.start());
            write(builder, authSession.sendChallenge());
        }
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
        final ActivityUser user = new ActivityUser();
        if (user != null) {
            write(builder, protocol.encodeUserInfo(user.getWeightKg(), user.getAge(), user.getHeightCm(),
                    user.getStepLengthCm(), user.getGender(), user.getStepsGoal()));
        }
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] data) {
        super.onCharacteristicChanged(gatt, characteristic, data);
        if (data == null || data.length == 0) return false;
        // INFO-level hex trace so end-users debugging via logcat see every frame
        LOG.info("FreeFit V2 RX op=0x{} len={} hex={}",
                String.format("%02x", data[0] & 0xFF), data.length, GB.hexdump(data));
        if (prefRcspAuthEnabled() && !authSession.isComplete() && !authSession.isFailed()) {
            final byte[] reply = authSession.onInbound(data);
            if (reply != null) {
                LOG.info("FreeFit V2 RCSP auth: replying state={} replyLen={}",
                        authSession.getState(), reply.length);
                send("rcsp auth reply", reply);
            }
            // Auth packets are never RCSP/FreeFit responses, so don't double-parse.
            if (data.length >= 1 && (data[0] == 0x00 || data[0] == 0x01 || data[0] == 0x02)) {
                return true;
            }
        }
        final GBDeviceEvent[] events = protocol.decodeResponse(data);
        if (events.length == 0) {
            LOG.debug("FreeFit V2: no event from op=0x{}", String.format("%02x", data[0] & 0xFF));
        } else {
            for (final GBDeviceEvent event : events) {
                if (event != null) {
                    LOG.info("FreeFit V2: dispatching {}", event.getClass().getSimpleName());
                    handleGBDeviceEvent(event);
                }
            }
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
        send("find earbuds", protocol.encodeFindHeadphones(start ? 1 : 0));
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
        if (notificationSpec == null) {
            LOG.warn("FreeFit V2 onNotification: null spec");
            return;
        }
        final StringBuilder text = new StringBuilder();
        if (notificationSpec.sourceName != null) text.append(notificationSpec.sourceName).append(": ");
        if (notificationSpec.title != null) text.append(notificationSpec.title);
        if (notificationSpec.body != null && !notificationSpec.body.isEmpty()) {
            if (text.length() > 0) text.append(" - ");
            text.append(notificationSpec.body);
        }
        final int appId = notificationSpec.type != null ? notificationSpec.type.ordinal() & 0xFF : 0;
        LOG.info("FreeFit V2 onNotification: family={} appId={} textLen={} text='{}'",
                protocol.getFamily(), appId, text.length(),
                text.length() > 40 ? text.substring(0, 40) + "..." : text);
        final List<byte[]> frames = protocol.encodeNotification(appId, text.toString(), protocol.getFamily());
        LOG.info("FreeFit V2 onNotification: encoded {} frame(s)", frames == null ? 0 : frames.size());
        sendAll("notification", frames);
    }

    @Override
    public void onSetMusicInfo(final MusicSpec musicSpec) {
        if (musicSpec == null) return;
        final List<byte[]> frames = new ArrayList<>();
        frames.addAll(protocol.encodeMusicText(musicSpec.artist, 1));
        frames.addAll(protocol.encodeMusicText(musicSpec.track, 2));
        sendAll("music info", frames);
    }

    @Override
    public void onSetMusicState(final MusicStateSpec stateSpec) {
        if (stateSpec == null) return;
        if (stateSpec.state == MusicStateSpec.STATE_PLAYING) {
            send("music play", protocol.encodeMusicControl(0));
        } else if (stateSpec.state == MusicStateSpec.STATE_PAUSED || stateSpec.state == MusicStateSpec.STATE_STOPPED) {
            send("music pause", protocol.encodeMusicControl(1));
        }
    }

    @Override
    public void onSetCallState(final CallSpec callSpec) {
        if (callSpec == null) return;
        final String display = callSpec.name != null && !callSpec.name.isEmpty() ? callSpec.name
                : (callSpec.number != null ? callSpec.number : "Unknown");
        switch (callSpec.command) {
            case CallSpec.CALL_INCOMING:
                send("incoming call (caller state)", protocol.encodeCallerStateNotification(display, "In"));
                send("incoming call", protocol.encodeIncomingCall(callSpec.name, callSpec.number));
                break;
            case CallSpec.CALL_ACCEPT:
                send("ongoing call", protocol.encodeCallerStateNotification(display, "On"));
                send("answer call", protocol.encodeCallAnswer());
                break;
            case CallSpec.CALL_REJECT:
                send("ended call", protocol.encodeCallerStateNotification(display, "Of"));
                send("decline call", protocol.encodeCallDecline());
                break;
            case CallSpec.CALL_END:
                send("ended call", protocol.encodeCallerStateNotification(display, "Of"));
                send("end call", protocol.encodeCallEnd());
                break;
            default:
                break;
        }
    }

    @Override
    public void onSetAlarms(final ArrayList<? extends Alarm> alarms) {
        if (alarms == null) return;
        send("alarms", protocol.encodeAlarmClock(alarms.toArray(new Alarm[0])));
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
        final int[] icons = new int[5];
        final int[] highs = new int[5];
        final int[] lows = new int[5];
        icons[0] = mapWeatherCode(weather.getCurrentConditionCode());
        highs[0] = max;
        lows[0] = min;
        for (int i = 1; i < icons.length; i++) {
            final int forecastIndex = i - 1;
            if (weather.getForecasts().size() > forecastIndex) {
                final WeatherSpec.Daily day = weather.getForecasts().get(forecastIndex);
                icons[i] = mapWeatherCode(day.getConditionCode());
                highs[i] = kelvinToCelsius(day.getMaxTemp());
                lows[i] = kelvinToCelsius(day.getMinTemp());
            } else {
                icons[i] = icons[i - 1];
                highs[i] = highs[i - 1];
                lows[i] = lows[i - 1];
            }
        }
        final int windDirection = mapWindDirection(weather.getWindDirection());
        final String city = weather.getLocation() == null || weather.getLocation().isEmpty() ? "Weather" : weather.getLocation();
        sendAll("weather", protocol.encodeWeatherForFamily(protocol.getFamily(), icons, highs, lows,
                current, Math.round(weather.getUvIndex()), windDirection, weather.getCurrentHumidity(), city));
    }

    @Override
    public void onSendConfiguration(final String config) {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        switch (config) {
            case "pref_a10pro_anc_mode":
                send("set ANC", protocol.encodeSetAnc(parseInt(prefs.getString(config, "0"), 0)));
                break;
            case "pref_a10pro_audio_model":
                send("set audio model", protocol.encodeSetAudioModel(parseInt(prefs.getString(config, "0"), 0)));
                break;
            case "pref_a10pro_find_earphones":
                send("find earbuds", protocol.encodeFindHeadphones(1));
                break;
            case "pref_a10pro_anti_lost":
                send("anti lost", protocol.encodeAntiLost(prefs.getBoolean(config, false)));
                break;
            case "pref_a10pro_find_band":
                send("find band", protocol.encodeFindBandSwitch(prefs.getBoolean(config, false)));
                break;
            case "pref_a10pro_metric_units":
                send("unit", protocol.encodeUnit(prefs.getBoolean(config, true), true));
                break;
            case "pref_a10pro_volume_cap":
                send("volume cap", protocol.encodeVolumeMaxValue(prefs.getInt(config, 100)));
                break;
            case "pref_a10pro_marquee":
                sendAll("marquee", protocol.encodeBarrage(prefs.getString(config, ""), 1, 20));
                break;
            case "pref_a10pro_factory_reset":
                send("factory reset", protocol.encodeReset());
                break;
            case "pref_a10pro_power_off":
                send("power off", protocol.encodeTurnOff(2));
                break;
            default:
                super.onSendConfiguration(config);
        }
    }

    private void send(final String label, final byte[] frame) {
        if (frame == null) {
            LOG.warn("FreeFit V2 [{}]: null frame, skipping", label);
            return;
        }
        try {
            final TransactionBuilder builder = performInitialized(label);
            write(builder, frame);
            builder.queue();
        } catch (final IOException e) {
            LOG.warn("FreeFit V2 [{}]: queue failed", label, e);
        }
    }

    private void sendAll(final String label, final List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) {
            LOG.debug("FreeFit V2 [{}]: no frames to send", label);
            return;
        }
        try {
            final TransactionBuilder builder = performInitialized(label);
            for (final byte[] frame : frames) {
                write(builder, frame);
                builder.sleep(100);
            }
            builder.queue();
        } catch (final IOException e) {
            LOG.warn("FreeFit V2 [{}]: queue failed ({} frames)", label, frames.size(), e);
        }
    }

    private void write(final TransactionBuilder builder, final byte[] frame) {
        if (frame == null) return;
        LOG.info("FreeFit V2 TX op=0x{} len={} hex={}",
                String.format("%02x", frame[0] & 0xFF), frame.length, GB.hexdump(frame));
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

    private boolean prefRcspAuthEnabled() {
        try {
            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
            return prefs.getBoolean("pref_a10pro_rcsp_auth", false);
        } catch (final Exception ignored) {
            return false;
        }
    }

    private int kelvinToCelsius(final int kelvin) {
        if (kelvin == 0) return 0;
        return Math.round((float) (kelvin - 273.15));
    }

    private int mapWeatherCode(final int openWeatherCode) {
        return A10ProProtocol.mapOpenWeatherToJlIcon(openWeatherCode);
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
