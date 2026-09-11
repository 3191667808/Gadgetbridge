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
package nodomain.freeyourgadget.gadgetbridge.service.devices.gloryfitpro

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo
import nodomain.freeyourgadget.gadgetbridge.devices.GloryFitStepsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import nodomain.freeyourgadget.gadgetbridge.export.GPXExporter
import java.io.File
import java.util.Date
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample
import nodomain.freeyourgadget.gadgetbridge.entities.GloryFitStepsSample
import nodomain.freeyourgadget.gadgetbridge.entities.GloryFitStepsSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.Alarm
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService
import nodomain.freeyourgadget.gadgetbridge.model.Contact
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec
import nodomain.freeyourgadget.gadgetbridge.util.MediaManager
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.service.devices.gloryfit.GloryFitNotificationType
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.UUID

/**
 * Support for the "GloryFit Pro" BLE dialect (com.yc.gloryfitpro), used by watches such as the
 * DM58 (Actions ATS3085L). Unlike the classic GloryFit protocol, this variant frames every packet
 * as `01 | CMD | MODE | FIELD | LEN | VALUE...` (MODE aa=get, ab=set, ac=report; doubled=all) and
 * runs almost entirely over the "DATA" service (0x56ff, write 0x34f1 / notify 0x34f2). MVP scope:
 * connect + device info (firmware) + set time.
 */
class GloryFitProSupport : AbstractBTLESingleDeviceSupport(LOG) {
    init {
        addSupportedService(UUID_SERVICE_CMD)
        addSupportedService(UUID_SERVICE_DATA)
    }

    override fun useAutoConnect(): Boolean {
        return true
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        builder.setDeviceState(GBDevice.State.INITIALIZING)

        builder.requestMtu(247)

        // Subscribe to both notify characteristics (CMD 0x35f2 and DATA 0x34f2).
        builder.notify(UUID_CHARACTERISTIC_CMD_READ, true)
        builder.notify(UUID_CHARACTERISTIC_DATA_READ, true)

        // Ask the watch for its device info (firmware etc.).
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *cmdGetAll(CMD_DEVICE_INFO))

        // Enable the watch's "find phone" feature (otherwise the watch button is greyed out).
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_DEVICE_CONTROL, MODE_SET, 0x0a, 0x01, 0x01))

        // Read the watch's alarms so ones edited on the watch appear in Gadgetbridge (read-only sync).
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *cmdGetAll(CMD_ALARM))

        if (GBApplication.getPrefs().syncTime()) {
            setTime(builder)
        }

        // Ask for today's step total.
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *cmdGetAll(CMD_ACTIVITY_DAY))

        // Push the currently-playing media so the watch's music screen is populated on connect
        // (otherwise it shows nothing until the next play/pause event).
        val mediaManager = MediaManager(context)
        mediaManager.refresh()
        mediaManager.bufferMusicSpec?.let { spec ->
            lastMusicSpec = spec
            lastMusicPlaying = mediaManager.bufferMusicStateSpec?.state?.toInt() == MusicStateSpec.STATE_PLAYING
            writeMusicInfo(builder, spec.artist, spec.track)
        }

        // FIXME: likely too early, refine once the init handshake is fully understood.
        builder.setDeviceState(GBDevice.State.INITIALIZED)

        return builder
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        when (characteristic.uuid) {
            UUID_CHARACTERISTIC_DATA_READ, UUID_CHARACTERISTIC_CMD_READ -> {
                handlePacket(value)
                return true
            }
        }
        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    private fun handlePacket(value: ByteArray) {
        if (value.size < 3 || value[0] != PKT_HEADER) {
            LOG.debug("Ignoring unexpected packet: {}", value.toHex())
            return
        }
        val cmd = value[1]
        // Continuation/terminator packet: 01 CMD MODE fd <cksum>
        if (value.size >= 4 && value[3] == PKT_TERMINATOR) {
            return
        }
        when (cmd) {
            CMD_DEVICE_INFO -> handleDeviceInfo(value)
            CMD_ACTIVITY_DAY -> handleDaySummary(value)
            CMD_MUSIC_CONTROL -> handleMusicControl(value)
            CMD_DEVICE_CONTROL -> handleDeviceControl(value)
            CMD_ALARM -> handleAlarmData(value)
            CMD_HEALTH -> handleHealthPage(value)
            CMD_HEALTH_COUNT -> handleHealthCount(value)
            CMD_WORKOUT -> handleWorkout(value)
            else -> LOG.debug("Unhandled cmd 0x{}: {}", Integer.toHexString(cmd.toInt() and 0xff), value.toHex())
        }
    }


    /**
     * Live health push: "01 c6 ac 01 00 <len> <ts:4> <type> …". Several record types carry a
     * heart rate, at a different offset each:
     *
     *   08 <bpm>                                       a single reading
     *   b8 <sub> <cur> <max> <min> [spo2] <avg>        a window summary, current first
     *   f5 08 <steps> <dist:2> <4 x bpm>               activity plus heart rate
     *   f7 08 <steps> <kcal> <dist:2> <4 x bpm>        the same with calories
     *
     * Only the first value is stored - it is the reading for this timestamp, the rest summarise
     * the window it belongs to.
     */
    private fun handleLiveHealthRecord(value: ByteArray) {
        if (value.size < 12 || value[4] != 0x00.toByte()) return
        val length = value[5].toInt() and 0xff
        if (value.size < 6 + length) return
        val timestamp = be32At(value, 6) * 1000L
        val type = value[10].toInt() and 0xff
        // 80 01 <spo2> is a blood oxygen reading on its own; b8 09 splices one in as the fourth
        // of five values, before the average.
        if (type == 0x80 && value.size > 12) {
            storeSpo2(timestamp, value[12].toInt() and 0xff)
            return
        }
        if (type == 0xb8 && (value.getOrNull(11)?.toInt()?.and(0xff)) == 0x09 && value.size > 15) {
            storeSpo2(timestamp, value[15].toInt() and 0xff)
        }
        val heartRate = when (type) {
            0x08 -> value.getOrNull(11)
            0xb8 -> value.getOrNull(12)
            0xf5 -> value.getOrNull(15)
            0xf7 -> value.getOrNull(16)
            else -> null
        }?.toInt()?.and(0xff) ?: return
        if (heartRate !in 25..250) return
        storeHeartRate(timestamp, heartRate)
    }

    private fun storeHeartRate(timestampMillis: Long, heartRate: Int) {
        try {
            GBApplication.acquireDB().use { handler ->
                val sample = GenericHeartRateSample()
                sample.timestamp = timestampMillis
                sample.heartRate = heartRate
                GenericHeartRateSampleProvider(device, handler.daoSession)
                    .persistSamples(listOf(sample), context)
                LOG.debug("Stored heart rate {} at {}", heartRate, timestampMillis)
            }
        } catch (e: Exception) {
            LOG.error("Failed to store heart rate", e)
        }
    }


    private fun storeSpo2(timestampMillis: Long, spo2: Int) {
        if (spo2 !in 50..100) return
        try {
            GBApplication.acquireDB().use { handler ->
                val sample = GenericSpo2Sample()
                sample.timestamp = timestampMillis
                sample.spo2 = spo2
                GenericSpo2SampleProvider(device, handler.daoSession)
                    .persistSamples(listOf(sample), context)
                LOG.debug("Stored SpO2 {} at {}", spo2, timestampMillis)
            }
        } catch (e: Exception) {
            LOG.error("Failed to store SpO2", e)
        }
    }

    /** Music remote button: "01 e2 ac 01 02 <code> 00" (device -> phone). */
    private fun handleMusicControl(value: ByteArray) {
        if (value.size < 6 || value[3] != 0x01.toByte() || value[4] != 0x02.toByte()) {
            return
        }
        val event = when (value[5].toInt() and 0xff) {
            0x01 -> GBDeviceEventMusicControl.Event.PLAY
            0x02 -> GBDeviceEventMusicControl.Event.PAUSE
            0x03 -> GBDeviceEventMusicControl.Event.PREVIOUS
            0x04 -> GBDeviceEventMusicControl.Event.NEXT
            0x05 -> GBDeviceEventMusicControl.Event.VOLUMEUP
            0x06 -> GBDeviceEventMusicControl.Event.VOLUMEDOWN
            else -> return
        }
        evaluateGBDeviceEvent(GBDeviceEventMusicControl(event))
    }

    override fun onSetPhoneVolume(volume: Float) {
        // The watch shows the volume in the music-info frame's field05; re-send the current track
        // so its volume bar reflects the phone volume.
        if (lastMusicSpec != null) {
            sendMusicInfo(lastMusicSpec?.artist, lastMusicSpec?.track)
        }
    }

    private var lastMusicSpec: MusicSpec? = null
    private var lastMusicPlaying = false

    override fun onSetMusicInfo(musicSpec: MusicSpec) {
        lastMusicSpec = musicSpec
        sendMusicInfo(musicSpec.artist, musicSpec.track)
    }

    override fun onSetMusicState(stateSpec: MusicStateSpec) {
        // Re-send full music info so field03 (play/pause icon) reflects the new state immediately.
        lastMusicPlaying = stateSpec.state.toInt() == MusicStateSpec.STATE_PLAYING
        sendMusicInfo(lastMusicSpec?.artist, lastMusicSpec?.track)
    }

    /** Music info to the watch: "01 e2 abab 00 ab 01 <artist> ab 02 <title> ab 03/04/05 <state>". */
    private fun sendMusicInfo(artistRaw: String?, titleRaw: String?) {
        val builder = createTransactionBuilder("set music info")
        writeMusicInfo(builder, artistRaw, titleRaw)
        builder.queue()
    }

    private fun writeMusicInfo(builder: TransactionBuilder, artistRaw: String?, titleRaw: String?) {
        val artist = (artistRaw ?: "").take(32).toByteArray(Charsets.UTF_16BE)
        val title = (titleRaw ?: "").take(48).toByteArray(Charsets.UTF_16BE)
        val data = ByteArrayOutputStream()
        data.write(byteArrayOf(PKT_HEADER, CMD_MUSIC_CONTROL, MODE_SET, MODE_SET, 0x00))
        data.write(byteArrayOf(MODE_SET, 0x01, artist.size.toByte())); data.write(artist)
        data.write(byteArrayOf(MODE_SET, 0x02, title.size.toByte())); data.write(title)
        // Watch icon flag: 0x03 -> pause icon (=playing), 0x02 -> play icon (=paused). Verified on hardware.
        data.write(byteArrayOf(MODE_SET, 0x03, 0x01, if (lastMusicPlaying) 0x03 else 0x02))
        // Volume bar: field04 = max steps, field05 = current step. Use the phone's own media-volume
        // scale so each volume key press moves the bar by exactly one segment.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceIn(1, 63)
        val volCur = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, volMax)
        data.write(byteArrayOf(MODE_SET, 0x04, 0x01, volMax.toByte()))
        data.write(byteArrayOf(MODE_SET, 0x05, 0x01, volCur.toByte()))
        val dataPkt = data.toByteArray()
        var xor = 0
        for (b in dataPkt) xor = xor xor (b.toInt() and 0xff)
        val terminator = byteArrayOf(PKT_HEADER, CMD_MUSIC_CONTROL, MODE_SET, MODE_SET, PKT_TERMINATOR, xor.toByte())
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *dataPkt)
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *terminator)
    }

    override fun onFetchRecordedData(dataTypes: Int) {
        // A sync should bring in everything the watch has: the per-minute history, the workouts
        // recorded since, the stored sleep, and today's totals. The totals go last on purpose -
        // they are stored as the remainder on top of the per-minute records, and the watch keeps
        // the last three quarters of an hour to itself until they age into the history.
        fetchHistory(minDays = 7)
        fetchWorkouts(minDays = 7)
        // Sleep rides the same c6 command as the history pages, so it waits until those are
        // done - asking for both at once makes the watch answer only the last request.
    }

    /**
     * Probe hook, wired to the "test new function" debug action.
     *
     * The watch pushes `c6` health records unsolicited, but nothing ever asks it for stored
     * ones, which is why a saved workout never reaches Gadgetbridge. Send a read-mode "give me
     * everything" on that same command and log whatever comes back; replies surface as
     * "Unhandled cmd 0xc6" until we know their shape.
     */
    // --- Device settings ------------------------------------------------------------------------

    override fun onSendConfiguration(config: String) {
        val prefs = devicePrefs
        when (config) {
            DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED ->
                writeSetting("lift wrist", CMD_DEVICE_CONTROL, 0x01,
                    byteArrayOf(if (prefs.getBoolean(config, false)) 1 else 0))

            DeviceSettingsPreferenceConst.PREF_SCREEN_TIMEOUT ->
                writeSetting("screen timeout", CMD_DEVICE_CONTROL, 0x08,
                    byteArrayOf(prefs.getString(config, "15")!!.toInt().coerceIn(1, 255).toByte()))

            DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE ->
                writeSetting("24h heart rate", CMD_FEATURE, 0x02,
                    byteArrayOf(if (prefs.getBoolean(config, false)) 1 else 0))

            DeviceSettingsPreferenceConst.PREF_HEARTRATE_ALERT_HIGH_THRESHOLD ->
                writeHeartRateAlert(0x03, config)

            DeviceSettingsPreferenceConst.PREF_HEARTRATE_ALERT_LOW_THRESHOLD ->
                writeHeartRateAlert(0x04, config)

            DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING ->
                writeSetting("spo2 monitoring", CMD_HEALTH_MONITOR, 0x03,
                    byteArrayOf(if (prefs.getBoolean(config, false)) 1 else 0))

            DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_START,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_END,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_MO,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_TU,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_WE,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_TH,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_FR,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_SA,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_SU -> sendSedentaryReminder()

            DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO,
            DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START,
            DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END -> sendDoNotDisturb()

            DeviceSettingsPreferenceConst.PREF_LANGUAGE -> sendLanguage()

            else -> LOG.debug("Unhandled setting {}", config)
        }
    }


    /**
     * Do not disturb: "<all day> <scheduled> <start h> <start m> <end h> <end m> 00 00". The first
     * two bytes are separate flags rather than one mode, which is why "off" clears both.
     */
    private fun sendDoNotDisturb() {
        val prefs = devicePrefs
        val mode = prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO, "off")
        val scheduled = mode != null && mode != "off"
        val start = prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START, "23:00")!!
        val end = prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END, "07:00")!!
        writeSetting(
            "do not disturb", CMD_DND, 0x01,
            byteArrayOf(
                0, if (scheduled) 1 else 0,
                hourOf(start).toByte(), minuteOf(start).toByte(),
                hourOf(end).toByte(), minuteOf(end).toByte(), 0, 0
            )
        )
    }

    /** Device language. Only codes verified on hardware are offered by the coordinator. */
    private fun sendLanguage() {
        val language = devicePrefs.getString(DeviceSettingsPreferenceConst.PREF_LANGUAGE, "en_US")
        val code = LANGUAGE_CODES[language]
        if (code == null) {
            LOG.warn("No known device code for language {}", language)
            return
        }
        writeSetting("language", CMD_DEVICE_CONTROL, 0x06, byteArrayOf(code))
    }

    /** Heart rate alert: "<enabled> <bpm>" - the watch keeps the limit even when it is off. */
    private fun writeHeartRateAlert(field: Byte, pref: String) {
        val value = devicePrefs.getString(pref, "0")!!.toIntOrNull() ?: 0
        val enabled = value > 0
        writeSetting(
            "heart rate alert", CMD_FEATURE, field,
            byteArrayOf(if (enabled) 1 else 0, value.coerceIn(0, 255).toByte())
        )
    }

    /**
     * Sedentary reminder, written as one block:
     * "<on> 00 <interval min> <start h> <start m> <end h> <end m> <weekday mask>".
     */
    private fun sendSedentaryReminder() {
        val prefs = devicePrefs
        val enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE, false)
        val interval = prefs.getString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD, "60")!!
            .toIntOrNull() ?: 60
        val start = prefs.getString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_START, "08:00")!!
        val end = prefs.getString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_END, "20:00")!!
        var mask = 0
        val days = listOf(
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_SU to 0x01,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_MO to 0x02,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_TU to 0x04,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_WE to 0x08,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_TH to 0x10,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_FR to 0x20,
            DeviceSettingsPreferenceConst.PREF_INACTIVITY_SA to 0x40
        )
        for ((key, bit) in days) if (prefs.getBoolean(key, false)) mask = mask or bit
        if (mask == 0) {
            // Every weekday defaults to off in the shared settings screen, so an otherwise
            // enabled reminder would be sent with no days and quietly never fire. The official
            // app has no day picker at all and always sends every day; match that.
            LOG.debug("No weekdays selected for the sedentary reminder, sending every day")
            mask = 0x7f
        }
        writeSetting(
            "sedentary reminder", CMD_HEALTH_MONITOR, 0x01,
            byteArrayOf(
                if (enabled) 1 else 0, 0, interval.coerceIn(1, 255).toByte(),
                hourOf(start).toByte(), minuteOf(start).toByte(),
                hourOf(end).toByte(), minuteOf(end).toByte(), mask.toByte()
            )
        )
    }

    private fun hourOf(hhmm: String): Int = hhmm.substringBefore(':').toIntOrNull() ?: 0
    private fun minuteOf(hhmm: String): Int = hhmm.substringAfter(':', "0").toIntOrNull() ?: 0

    private fun writeSetting(label: String, cmd: Byte, field: Byte, value: ByteArray) {
        LOG.debug("Setting {}: cmd 0x{} field 0x{} = {}", label,
            Integer.toHexString(cmd.toInt() and 0xff), Integer.toHexString(field.toInt() and 0xff),
            value.toHex())
        val builder = createTransactionBuilder(label)
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE,
            *(byteArrayOf(PKT_HEADER, cmd, MODE_SET, field, value.size.toByte()) + value))
        builder.queue()
    }

    // --- Stored workouts (e8) -------------------------------------------------------------------

    private var workoutIndex = 0
    private var workoutDetailPage = 0
    private var workoutDetailBytes = 0
    private val workoutSizes = LinkedHashMap<Int, Int>()
    private val workoutTrack = ByteArrayOutputStream()
    private var workoutSummary: Map<Int, Long> = emptyMap()
    private var workoutTrackXor = 0
    private var workoutTrackFrames = 0

    /**
     * Ask for stored workouts over a time range. Three steps, mirroring the official app:
     * `aa 01` lists what is there, `aa 02` gives a workout's summary, `aa 03` streams its detail
     * pages.
     */
    private fun fetchWorkouts(minDays: Int) {
        val newest = newestSampleMillis { session, deviceId ->
            session.baseActivitySummaryDao.queryBuilder()
                .where(BaseActivitySummaryDao.Properties.DeviceId.eq(deviceId))
                .orderDesc(BaseActivitySummaryDao.Properties.StartTime)
                .limit(1)
                .unique()?.startTime?.time ?: 0L
        }
        val days = lookbackDays(newest, minDays)
        val to = (System.currentTimeMillis() / 1000L).toInt()
        val from = to - days * 24 * 3600
        workoutIndex = 0
        workoutDetailPage = 0
        workoutDetailBytes = 0
        workoutSizes.clear()
        LOG.info("Workouts: listing over the last {} days", days)
        val builder = createTransactionBuilder("workout list")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_WORKOUT, MODE_GET, 0x01, 0x08)
            + be32(from) + be32(to))
        builder.queue()
    }

    private fun requestWorkoutSummary(index: Int) {
        val builder = createTransactionBuilder("workout summary $index")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_WORKOUT, MODE_GET, 0x02, 0x02,
            (index ushr 8).toByte(), index.toByte()))
        builder.queue()
    }

    private fun requestWorkoutDetail(index: Int, page: Int) {
        val builder = createTransactionBuilder("workout detail $index/$page")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_WORKOUT, MODE_GET, 0x03, 0x05,
            (index ushr 8).toByte(), index.toByte(), (page ushr 8).toByte(), page.toByte(), 0x00))
        builder.queue()
    }

    private fun handleWorkout(value: ByteArray) {
        if (value.size >= 5 && value[2] == MODE_REPORT && value[3] == 0x01.toByte() &&
            value[4] != PKT_TERMINATOR
        ) {
            // The watch announces a finished workout unprompted - fetch it rather than waiting
            // for someone to press sync.
            LOG.info("Workout finished on the watch, fetching it")
            fetchWorkouts(minDays = 1)
            return
        }
        if (value.size < 5 || value[2] != MODE_GET) return
        val field = value[3].toInt() and 0xff
        val terminator = value[4] == PKT_TERMINATOR
        when (field) {
            0x01 -> if (!terminator) {
                parseWorkoutList(value)
                val first = workoutSizes.keys.firstOrNull()
                if (first == null) {
                    LOG.info("Workouts: none stored in this range")
                } else {
                    workoutIndex = first
                    requestWorkoutSummary(first)
                }
            }
            0x02 -> if (!terminator) {
                logWorkoutSummary(value)
                workoutTrack.reset()
                workoutTrackXor = 0
                workoutTrackFrames = 0
                requestWorkoutTrack(workoutIndex)
            }
            0x09 -> if (terminator) {
                // The terminator's checksum covers every byte of every frame of the reply, so a
                // notification lost on the way silently shortens the track unless it is checked.
                val expected = if (value.size > 6) value[6].toInt() and 0xff else -1
                if (expected >= 0 && expected != workoutTrackXor) {
                    LOG.warn(
                        "Workout {}: track checksum mismatch (got 0x{}, expected 0x{}) over {} frames" +
                                " - the track is incomplete, discarding",
                        workoutIndex, Integer.toHexString(workoutTrackXor),
                        Integer.toHexString(expected), workoutTrackFrames
                    )
                } else {
                    parseWorkoutTrack(workoutTrack.toByteArray())
                }
                workoutDetailPage = 0
                workoutDetailBytes = 0
                requestWorkoutDetail(workoutIndex, 0)
            } else {
                for (b in value) workoutTrackXor = workoutTrackXor xor (b.toInt() and 0xff)
                workoutTrackFrames++
                // Chunk 0 carries only the header <start ts:4> <end ts:4> <value:4>; points start
                // at chunk 1.
                val chunk = ((value[4].toInt() and 0xff) shl 8) or (value[5].toInt() and 0xff)
                if (chunk != 0 && value.size > 6) {
                    workoutTrack.write(value, 6, value.size - 6)
                }
            }
            0x03 -> if (terminator) {
                // Stop at the byte count the list advertised - asking past the end makes the
                // watch repeat data instead of reporting that there is no more.
                val expected = workoutSizes[workoutIndex] ?: 0
                if (workoutDetailBytes < expected && workoutDetailPage + 1 < MAX_WORKOUT_PAGES) {
                    workoutDetailPage++
                    requestWorkoutDetail(workoutIndex, workoutDetailPage)
                } else {
                    LOG.debug("Workout {} detail done: {} of {} bytes over {} pages",
                        workoutIndex, workoutDetailBytes, expected, workoutDetailPage + 1)
                    val next = workoutSizes.keys.firstOrNull { it > workoutIndex }
                    if (next != null) {
                        workoutIndex = next
                        requestWorkoutSummary(next)
                    } else {
                        LOG.info("Workouts: done")
                    }
                }
            } else {
                // chunk 0 of a page carries <index:2><page:2><05><start ts:4>; later chunks are raw
                val chunk = ((value[4].toInt() and 0xff) shl 8) or (value[5].toInt() and 0xff)
                val dataFrom = if (chunk == 0) 15 else 6
                if (value.size > dataFrom) workoutDetailBytes += value.size - dataFrom
            }
            else -> LOG.debug("Workout field 0x{}: {}", Integer.toHexString(field), value.toHex())
        }
    }



    private fun requestWorkoutTrack(index: Int) {
        val builder = createTransactionBuilder("workout track $index")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_WORKOUT, MODE_GET, 0x09, 0x00, 0x02,
            (index ushr 8).toByte(), index.toByte()))
        builder.queue()
    }

    /**
     * GPS track of a workout: a run of "01 01 <n> 07 04 <lat> 08 04 <lon> 0b 02 .. 0c 02 .. 0d 02 .."
     * records, latitude and longitude scaled by 1e7 big-endian - the same field ids the phone uses
     * when it sends a position the other way.
     *
     * Latitude and longitude are separated by the 08 04 field header, so they are not eight
     * consecutive bytes.
     */
    private fun parseWorkoutTrack(track: ByteArray) {
        val points = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i + 12 <= track.size) {
            if (track[i] == 0x07.toByte() && track[i + 1] == 0x04.toByte() &&
                track[i + 6] == 0x08.toByte() && track[i + 7] == 0x04.toByte()
            ) {
                points.add(Pair(signed32At(track, i + 2) / 1e7, signed32At(track, i + 8) / 1e7))
                i += 12
            } else {
                i++
            }
        }
        if (points.isEmpty()) {
            LOG.info("Workout {}: no GPS track ({} bytes)", workoutIndex, track.size)
        } else {
            LOG.info(
                "Workout {}: GPS track of {} points, lat {}..{}, lon {}..{}",
                workoutIndex, points.size,
                points.minOf { it.first }, points.maxOf { it.first },
                points.minOf { it.second }, points.maxOf { it.second }
            )
        }
        storeWorkout(points)
    }

    /**
     * Store one workout as an activity summary, with its route as a GPX track when there is one.
     *
     * The track records carry no timestamps of their own - only the summary has a start and an
     * end - so point times are spread evenly across the workout. That is an approximation: it is
     * right at both ends and drifts in between if the pace varied.
     */
    private fun storeWorkout(points: List<Pair<Double, Double>>) {
        val start = workoutSummary[0x03] ?: return
        val end = workoutSummary[0x04] ?: return
        if (start <= 0 || end <= start) return
        try {
            GBApplication.acquireDB().use { handler ->
                val session = handler.daoSession
                val dbDevice = DBHelper.getDevice(device, session)
                val dbUser = DBHelper.getUser(session)
                // Every sync re-reads the workouts still on the watch, so look for the one we
                // already have and update it in place - insertOrReplace only replaces on a
                // matching row id, and a fresh entity has none, so it would insert a copy.
                val startDate = Date(start * 1000L)
                val matches = session.baseActivitySummaryDao.queryBuilder()
                    .where(
                        BaseActivitySummaryDao.Properties.StartTime.eq(startDate),
                        BaseActivitySummaryDao.Properties.DeviceId.eq(dbDevice.id),
                        BaseActivitySummaryDao.Properties.UserId.eq(dbUser.id)
                    )
                    .build().list()
                if (matches.size > 1) {
                    // Copies left behind before this was fixed. They are identical, so keeping
                    // the first and dropping the rest loses nothing.
                    session.baseActivitySummaryDao.deleteInTx(matches.drop(1))
                    LOG.info("Removed {} duplicate copies of the workout starting {}",
                        matches.size - 1, startDate)
                }
                val summary = matches.firstOrNull() ?: BaseActivitySummary()
                summary.startTime = startDate
                summary.endTime = Date(end * 1000L)
                summary.activityKind = workoutKind(workoutSummary[0x09]?.toInt() ?: -1).code
                summary.name = "Workout"
                summary.device = dbDevice
                summary.user = dbUser

                if (points.isNotEmpty()) {
                    try {
                        summary.gpxTrack = writeGpx(dbDevice, dbUser, start, end, points)
                    } catch (e: Exception) {
                        LOG.warn("Failed to export the GPX track", e)
                    }
                }

                val data = ActivitySummaryData()
                workoutSummary[0x08]?.let {
                    data.add(null, ActivitySummaryEntries.ACTIVE_SECONDS, it.toFloat(), ActivitySummaryEntries.UNIT_SECONDS)
                }
                workoutSummary[0x06]?.let {
                    data.add(null, ActivitySummaryEntries.DISTANCE_METERS, it.toFloat(), ActivitySummaryEntries.UNIT_METERS)
                }
                workoutSummary[0x05]?.let {
                    data.add(null, ActivitySummaryEntries.CALORIES_BURNT, it.toFloat(), ActivitySummaryEntries.UNIT_KCAL)
                }
                workoutSummary[0x07]?.takeIf { it > 0 }?.let {
                    data.add(null, ActivitySummaryEntries.STEPS, it.toFloat(), ActivitySummaryEntries.UNIT_STEPS)
                }
                workoutSummary[0x0b]?.takeIf { it > 0 }?.let {
                    data.add(null, ActivitySummaryEntries.HR_MIN, it.toFloat(), ActivitySummaryEntries.UNIT_BPM)
                }
                workoutSummary[0x0c]?.takeIf { it > 0 }?.let {
                    data.add(null, ActivitySummaryEntries.HR_MAX, it.toFloat(), ActivitySummaryEntries.UNIT_BPM)
                }
                data.setHasGps(summary.gpxTrack != null)
                summary.summaryData = data.toJson()

                session.baseActivitySummaryDao.insertOrReplace(summary)
                LOG.info("Stored workout {} ({} - {}), kind {}, {} track points",
                    workoutIndex, summary.startTime, summary.endTime, summary.activityKind,
                    points.size)
            }
        } catch (e: Exception) {
            LOG.error("Failed to store workout {}", workoutIndex, e)
        }
    }

    private fun writeGpx(
        dbDevice: nodomain.freeyourgadget.gadgetbridge.entities.Device,
        dbUser: nodomain.freeyourgadget.gadgetbridge.entities.User,
        start: Long, end: Long, points: List<Pair<Double, Double>>
    ): String {
        val dir = device.deviceCoordinator.getWritableExportDirectory(device, true)
        val file = File(dir, "gloryfitpro_" + start + ".gpx")
        val activityTrack = ActivityTrack()
        activityTrack.baseTime = Date(start * 1000L)
        activityTrack.name = "Workout"
        activityTrack.device = dbDevice
        activityTrack.user = dbUser
        val span = (end - start).toDouble()
        for ((index, p) in points.withIndex()) {
            val fraction = if (points.size > 1) index.toDouble() / (points.size - 1) else 0.0
            val at = Date(((start + span * fraction) * 1000L).toLong())
            val point = ActivityPoint(at)
            point.location = GPSCoordinate(p.second, p.first, GPSCoordinate.UNKNOWN_ALTITUDE)
            activityTrack.addTrackPoint(point)
        }
        GPXExporter().performExport(activityTrack, file, null)
        return file.absolutePath
    }

    /** Workout type codes seen on this watch; everything else falls back to a generic activity. */
    private fun workoutKind(type: Int): ActivityKind = when (type) {
        2, 6 -> ActivityKind.OUTDOOR_WALKING
        8 -> ActivityKind.INDOOR_WALKING
        9 -> ActivityKind.WALKING
        58 -> ActivityKind.OUTDOOR_CYCLING
        else -> ActivityKind.ACTIVITY
    }

    private fun be32At(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
                ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    /**
     * The same four bytes read as a signed value, for coordinates: latitude south of the equator
     * and longitude west of Greenwich are negative, and reading them unsigned turns -1e-7 into
     * 429.4967295.
     */
    private fun signed32At(b: ByteArray, i: Int): Int = be32At(b, i).toInt()

    /** List reply: "01 e8 aa 01 <chunk:2> <count:2> [<index:2> <detail size:3> <pad:3>]*". */
    private fun parseWorkoutList(value: ByteArray) {
        LOG.debug("Workout list: {}", value.toHex())
        if (value.size < 8) return
        val count = ((value[6].toInt() and 0xff) shl 8) or (value[7].toInt() and 0xff)
        var i = 8
        var n = 0
        while (n < count && i + 8 <= value.size) {
            val index = ((value[i].toInt() and 0xff) shl 8) or (value[i + 1].toInt() and 0xff)
            val size = ((value[i + 2].toInt() and 0xff) shl 16) or
                    ((value[i + 3].toInt() and 0xff) shl 8) or (value[i + 4].toInt() and 0xff)
            workoutSizes[index] = size
            LOG.debug("  workout {} holds {} bytes of detail", index, size)
            i += 8
            n++
        }
    }

    /** Summary fields: 03 start, 04 end, 05 kcal, 06 distance (m), 07 steps, 08 active s, 09 type. */
    private fun logWorkoutSummary(value: ByteArray) {
        LOG.debug("Workout {} summary raw: {}", workoutIndex, value.toHex())
        val fields = HashMap<Int, Long>()
        var i = 6
        while (i + 2 <= value.size) {
            val id = value[i].toInt() and 0xff
            val len = value[i + 1].toInt() and 0xff
            if (len == 0 || len > 8 || i + 2 + len > value.size) { i++; continue }
            var v = 0L
            for (k in 0 until len) v = (v shl 8) or (value[i + 2 + k].toLong() and 0xff)
            val name = when (id) {
                0x03 -> "start"; 0x04 -> "end"; 0x05 -> "kcal"; 0x06 -> "distance_m"
                0x07 -> "steps"; 0x08 -> "active_s"; 0x09 -> "type"
                0x0b -> "hr_min"; 0x0c -> "hr_max"; 0x4c -> "cadence_spm"; 0x4d -> "stride_cm"
                else -> "field_0x" + Integer.toHexString(id)
            }
            LOG.debug("  workout {} = {}", name, v)
            fields[id] = v
            i += 2 + len
        }
        workoutSummary = fields
    }

    // --- Stored sleep (c6 field 04) ------------------------------------------------------------

    private val sleepBuffer = ByteArrayOutputStream()
    private var sleepXor = 0

    /**
     * Ask the watch for its stored sleep.
     *
     * Unlike the activity history this is not paged - the watch answers with a single chunked
     * message covering the whole range - so the window can be generous. The official app asks
     * for 168 days and gets a payload under a kilobyte.
     */
    private fun fetchDayTotals() {
        val builder = createTransactionBuilder("day totals")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *cmdGetAll(CMD_ACTIVITY_DAY))
        builder.queue()
    }

    private fun fetchSleep(days: Int) {
        val to = (System.currentTimeMillis() / 1000L).toInt()
        val from = to - days * 24 * 3600
        sleepBuffer.reset()
        sleepXor = 0
        LOG.info("Sleep: asking for the last {} days", days)
        val builder = createTransactionBuilder("sleep")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_HEALTH, MODE_GET, FIELD_SLEEP, 0x08)
            + be32(from) + be32(to))
        builder.queue()
    }

    /**
     * Sleep reply, chunked as "01 c6 aa 04 <chunk:2> <slice>" - a two-byte counter here, where
     * the paged history uses one - and closed by "01 c6 aa 04 fd fd <xor>". The xor covers every
     * byte of every data frame, so a dropped notification is caught instead of being parsed into
     * plausible nonsense.
     */
    private fun handleSleepChunk(value: ByteArray) {
        if (value.size < 6) return
        if (value[4] == PKT_TERMINATOR) {
            val expected = value[value.size - 1].toInt() and 0xff
            if (expected != sleepXor) {
                LOG.warn("Sleep: checksum {} does not match the expected {}, discarding {} bytes",
                    sleepXor, expected, sleepBuffer.size())
            } else {
                parseSleep(sleepBuffer.toByteArray())
            }
            sleepBuffer.reset()
            sleepXor = 0
            return
        }
        val chunk = ((value[4].toInt() and 0xff) shl 8) or (value[5].toInt() and 0xff)
        if (chunk == 0) {
            sleepBuffer.reset()
            sleepXor = 0
        }
        for (b in value) sleepXor = sleepXor xor (b.toInt() and 0xff)
        sleepBuffer.write(value, 6, value.size - 6)
    }

    /**
     * Payload: a two-byte length, then 7-byte records "<start:4> <minutes:2> <type>".
     *
     * Type 07 and 08 are zero-length markers bracketing each session; 1-4 are the stages
     * themselves and carry the same meaning as in the classic GloryFit dialect, so they go into
     * the shared sleep stage table unchanged. Segments are contiguous - each one starts where
     * the previous ended - and the gaps between sessions are simply not slept.
     */
    private fun parseSleep(payload: ByteArray) {
        if (payload.size < 9) {
            LOG.info("Sleep: nothing stored")
            return
        }
        val stageBytes = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val samples = ArrayList<GenericSleepStageSample>()
        var sessions = 0
        var minutes = 0
        var i = 2
        while (i + 7 <= payload.size) {
            val start = ((payload[i].toInt() and 0xff).toLong() shl 24) or
                    ((payload[i + 1].toInt() and 0xff).toLong() shl 16) or
                    ((payload[i + 2].toInt() and 0xff).toLong() shl 8) or
                    (payload[i + 3].toInt() and 0xff).toLong()
            val duration = ((payload[i + 4].toInt() and 0xff) shl 8) or (payload[i + 5].toInt() and 0xff)
            when (val type = payload[i + 6].toInt() and 0xff) {
                SLEEP_SESSION_START -> sessions++
                SLEEP_SESSION_END -> {}
                in SLEEP_STAGE_DEEP..SLEEP_STAGE_REM -> {
                    val sample = GenericSleepStageSample()
                    sample.timestamp = start * 1000L
                    sample.duration = duration
                    sample.stage = type
                    samples.add(sample)
                    minutes += duration
                }
                else -> LOG.warn("Sleep: unknown stage type 0x{}", Integer.toHexString(type))
            }
            i += 7
        }
        if (samples.isNotEmpty()) {
            try {
                GBApplication.acquireDB().use { handler ->
                    GenericSleepStageSampleProvider(device, handler.daoSession)
                        .persistSamples(samples, context)
                }
            } catch (e: Exception) {
                LOG.error("Failed to store {} sleep stage samples", samples.size, e)
            }
        }
        LOG.info("Sleep: {} sessions, {} stages, {} minutes (header {}, expected {})",
            sessions, samples.size, minutes, stageBytes, samples.size * 7)
    }

    // --- Stored activity history (c5 count + c6 paged read) ------------------------------------

    private val historyBuffer = ByteArrayOutputStream()
    private val historySteps = ArrayList<GloryFitStepsSample>()
    private var historyFrom = 0
    private var historyTo = 0
    private var historyPage = 0
    private var historyPages = 0

    /**
     * Ask the watch for its stored per-minute activity history.
     *
     * `c6` ignores a bare "get all" - it wants a time range and a page number. The official app
     * first asks `c5` how many pages exist for the range, then walks them one by one.
     */
    /**
     * How far back to ask, given the newest sample of this kind we already hold.
     *
     * A fixed window loses data when the phone has been away for longer than the window, so the
     * ask is stretched to cover everything since that sample, with a day of slack because the
     * watch writes the tail of a day out late. It is bounded at both ends: never narrower than
     * [minDays], so a routine sync still re-reads the recent past and heals any gaps in it, and
     * never wider than [MAX_LOOKBACK_DAYS], because asking for more than the watch keeps only
     * costs round trips. Measured on a DM58: a 120-day request returns 44 pages, about nine
     * days' worth, which is all it retains.
     */
    private fun lookbackDays(newestKnownMillis: Long, minDays: Int): Int {
        if (newestKnownMillis <= 0) return minDays
        val elapsed = System.currentTimeMillis() - newestKnownMillis
        if (elapsed <= 0) return minDays
        val days = (elapsed / (24 * 3600 * 1000L)).toInt() + 1
        return days.coerceIn(minDays, MAX_LOOKBACK_DAYS)
    }

    /** Newest timestamp in a table for this device, or 0 when we have never stored one. */
    private fun newestSampleMillis(pick: (DaoSession, Long) -> Long): Long {
        return try {
            GBApplication.acquireDB().use { handler ->
                val session = handler.daoSession
                pick(session, DBHelper.getDevice(device, session).id ?: return@use 0L)
            }
        } catch (e: Exception) {
            LOG.error("Failed to look up the newest stored sample", e)
            0L
        }
    }

    private fun fetchHistory(minDays: Int) {
        val newest = newestSampleMillis { session, deviceId ->
            session.gloryFitStepsSampleDao.queryBuilder()
                .where(GloryFitStepsSampleDao.Properties.DeviceId.eq(deviceId))
                .orderDesc(GloryFitStepsSampleDao.Properties.Timestamp)
                .limit(1)
                .unique()?.timestamp ?: 0L
        }
        val days = lookbackDays(newest, minDays)
        historyTo = (System.currentTimeMillis() / 1000L).toInt()
        historyFrom = historyTo - days * 24 * 3600
        historyPage = 0
        historyPages = 0
        historyBuffer.reset()
        historySteps.clear()
        LOG.info("History: asking for page count over the last {} days", days)
        val builder = createTransactionBuilder("history count")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_HEALTH_COUNT, MODE_GET, 0x04, 0x08)
            + be32(historyFrom) + be32(historyTo))
        builder.queue()
    }

    private fun requestHistoryPage(page: Int) {
        historyBuffer.reset()
        val builder = createTransactionBuilder("history page $page")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *byteArrayOf(PKT_HEADER, CMD_HEALTH, MODE_GET, 0x01, 0x0a)
            + be32(historyFrom) + be32(historyTo) + byteArrayOf((page ushr 8).toByte(), page.toByte()))
        builder.queue()
    }

    /**
     * Write the fetched history away, replacing the window in one go.
     *
     * The per-minute records are the authoritative version of the window they cover, and the
     * coarse day-total remainders written alongside them would otherwise be counted twice, so
     * the window has to be cleared. That only happens here, once the fetch has actually
     * delivered: a fetch that dies halfway leaves what was already stored untouched rather than
     * replacing it with a hole.
     */
    private fun commitHistorySteps() {
        if (historySteps.isEmpty()) return
        try {
            GBApplication.acquireDB().use { handler ->
                val session = handler.daoSession
                val deviceId = DBHelper.getDevice(device, session).id
                session.gloryFitStepsSampleDao.queryBuilder()
                    .where(
                        GloryFitStepsSampleDao.Properties.DeviceId.eq(deviceId),
                        GloryFitStepsSampleDao.Properties.Timestamp.ge(historyFrom * 1000L),
                        GloryFitStepsSampleDao.Properties.Timestamp.le(historyTo * 1000L)
                    )
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities()
                session.clear()
                GloryFitStepsSampleProvider(device, session).persistSamples(historySteps, context)
                LOG.info("History: stored {} per-minute step records", historySteps.size)
            }
        } catch (e: Exception) {
            LOG.error("Failed to store {} historic step samples", historySteps.size, e)
        } finally {
            historySteps.clear()
        }
    }

    /** Page-count reply: "01 c5 aa 04 01 <count>". */
    private fun handleHealthCount(value: ByteArray) {
        if (value.size < 6 || value[2] != MODE_GET || value[3] != 0x04.toByte()) return
        historyPages = value[5].toInt() and 0xff
        LOG.info("History: {} pages available", historyPages)
        if (historyPages > 0) {
            requestHistoryPage(0)
        } else {
            fetchSleep(days = SLEEP_DAYS)
            fetchDayTotals()
        }
    }

    /**
     * Page reply, chunked as "01 c6 aa 01 <chunk> <slice>" with a 1-byte chunk counter (00, 01,
     * ...) and "01 c6 aa 01 fd <xor>" to close. Only the first chunk carries the page header.
     */
    private fun handleHealthPage(value: ByteArray) {
        if (value.size < 5 || value[2] != MODE_GET) {
            LOG.debug("Live health record: {}", value.toHex())
            handleLiveHealthRecord(value)
            return
        }
        if (value[3] == FIELD_SLEEP) {
            handleSleepChunk(value)
            return
        }
        if (value[4] == PKT_TERMINATOR) {
            parseHistoryPage(historyBuffer.toByteArray())
            if (historyPage + 1 < historyPages && historyPage < MAX_HISTORY_PAGES) {
                historyPage++
                requestHistoryPage(historyPage)
            } else {
                LOG.info("History: done after {} pages", historyPage + 1)
                commitHistorySteps()
                fetchSleep(days = SLEEP_DAYS)
                fetchDayTotals()
            }
            return
        }
        historyBuffer.write(value, 5, value.size - 5)
    }

    /** Page payload: "<page:2> <day midnight:4>" then "<minute:2> <type> <payload>" records. */
    private fun parseHistoryPage(page: ByteArray) {
        if (page.size < 6) return
        val day = ((page[2].toInt() and 0xff) shl 24) or ((page[3].toInt() and 0xff) shl 16) or
                ((page[4].toInt() and 0xff) shl 8) or (page[5].toInt() and 0xff)
        var i = 6
        var steps = 0
        var distance = 0
        var kcal = 0
        var records = 0
        var firstMinute = -1
        var lastMinute = -1
        val heartRates = ArrayList<GenericHeartRateSample>()
        val spo2Samples = ArrayList<GenericSpo2Sample>()
        val stepSamples = ArrayList<GloryFitStepsSample>()
        while (i + 3 <= page.size) {
            val minute = ((page[i].toInt() and 0xff) shl 8) or (page[i + 1].toInt() and 0xff)
            val type = page[i + 2].toInt() and 0xff
            val len = recordLength(page, i)
            if (len < 0 || minute >= 1440 || i + 3 + len > page.size) {
                // Dump the bytes around the record we cannot measure. The rest of the page is
                // lost when this fires, so the hex is the only way to work out what these types
                // are - see the open questions in the protocol notes.
                val before = (i - 16).coerceAtLeast(0)
                val after = (i + 64).coerceAtMost(page.size)
                LOG.warn("History: stopped at byte {} of {}, type 0x{}, day {} minute {}; before {} at {}",
                    i, page.size, Integer.toHexString(type), day, minute,
                    page.copyOfRange(before, i).toHex(), page.copyOfRange(i, after).toHex())
                break
            }
            // 0xf7 and 0xf5 are the same activity payloads as 0x07 / 0x05 with a heart rate
            // quad appended, after a leading subfield byte. Leaving them out under-counts a day
            // by roughly a third.
            val activity = when (type) {
                0x07 -> i + 3
                0x05 -> i + 3
                0xf7 -> i + 4
                0xf5 -> i + 4
                else -> -1
            }
            if (activity >= 0) {
                val withKcal = type == 0x07 || type == 0xf7
                steps += page[activity].toInt() and 0xff
                if (withKcal) {
                    kcal += page[activity + 1].toInt() and 0xff
                    distance += ((page[activity + 2].toInt() and 0xff) shl 8) or (page[activity + 3].toInt() and 0xff)
                } else {
                    distance += ((page[activity + 1].toInt() and 0xff) shl 8) or (page[activity + 2].toInt() and 0xff)
                }
                if (firstMinute < 0) firstMinute = minute
                lastMinute = minute
                val minuteSteps = page[activity].toInt() and 0xff
                if (minuteSteps > 0) {
                    val sample = GloryFitStepsSample()
                    sample.timestamp = (day.toLong() + minute * 60L) * 1000L
                    sample.totalSteps = minuteSteps
                    sample.runningStart = 0
                    sample.runningEnd = 0
                    sample.runningSteps = 0
                    sample.walkingStart = 0
                    sample.walkingEnd = 0
                    sample.walkingSteps = 0
                    stepSamples.add(sample)
                }
            }
            // Heart rate sits at a different offset per record type, exactly as in the live
            // pushes: a lone reading, the first value of a b8 window summary, or the first of the
            // quad appended to an f5 / f7 activity record.
            // The values start after the subfield and the counter group, so the first of them -
            // the current heart rate - sits at a position the mask itself tells us, and only
            // when the mask says a quad is there at all.
            val group = recordGroup(type)
            val sub = if (group >= 0) page.getOrNull(i + 3)?.toInt()?.and(0xff) ?: 0 else 0
            val valuesAt = if (group >= 0) i + 4 + group + if (type == 0xbc) 2 else 0 else -1
            val hrAt = when {
                type == 0x08 -> i + 3
                group >= 0 && sub and 0x08 != 0 -> valuesAt
                else -> -1
            }
            if (hrAt in 0 until page.size) {
                val bpm = page[hrAt].toInt() and 0xff
                if (bpm in 25..250) {
                    val sample = GenericHeartRateSample()
                    sample.timestamp = (day.toLong() + minute * 60L) * 1000L
                    sample.heartRate = bpm
                    heartRates.add(sample)
                }
            }
            // A b8 record with subfield 09 is the same window summary as 08 with the SpO2
            // reading spliced in before the average, so it is the only historic record type
            // that carries one.
            // 0x87 is the activity group with an SpO2 reading appended, the same shape the live
            // stream pushes under that mask.
            if (type == 0x87 && i + 8 < page.size) {
                val spo2 = page[i + 8].toInt() and 0xff
                if (spo2 in 50..100) {
                    val sample = GenericSpo2Sample()
                    sample.timestamp = (day.toLong() + minute * 60L) * 1000L
                    sample.spo2 = spo2
                    spo2Samples.add(sample)
                }
            }
            // 0x82 is the 0x80 SpO2 record with one extra byte in front of the reading.
            if (type == 0x82 && i + 5 < page.size) {
                val spo2 = page[i + 5].toInt() and 0xff
                if (spo2 in 50..100) {
                    val sample = GenericSpo2Sample()
                    sample.timestamp = (day.toLong() + minute * 60L) * 1000L
                    sample.spo2 = spo2
                    spo2Samples.add(sample)
                }
            }
            if (type == 0x80 && i + 4 < page.size) {
                val spo2 = page[i + 4].toInt() and 0xff
                if (spo2 in 50..100) {
                    val sample = GenericSpo2Sample()
                    sample.timestamp = (day.toLong() + minute * 60L) * 1000L
                    sample.spo2 = spo2
                    spo2Samples.add(sample)
                }
            }
            // With mask bit 0x01 next to the quad the reading is spliced in as the fourth
            // value, before the average - the shape the live stream uses for b8 09.
            if (group >= 0 && sub and 0x09 == 0x09 && valuesAt + 3 < page.size) {
                val spo2 = page[valuesAt + 3].toInt() and 0xff
                if (spo2 in 50..100) {
                    val sample = GenericSpo2Sample()
                    sample.timestamp = (day.toLong() + minute * 60L) * 1000L
                    sample.spo2 = spo2
                    spo2Samples.add(sample)
                }
            }
            records++
            i += 3 + len
        }
        if (heartRates.isNotEmpty()) {
            try {
                GBApplication.acquireDB().use { handler ->
                    GenericHeartRateSampleProvider(device, handler.daoSession)
                        .persistSamples(heartRates, context)
                }
            } catch (e: Exception) {
                LOG.error("Failed to store {} historic heart rate samples", heartRates.size, e)
            }
        }
        // Held until the whole fetch is in - see commitHistorySteps.
        historySteps.addAll(stepSamples)
        if (spo2Samples.isNotEmpty()) {
            try {
                GBApplication.acquireDB().use { handler ->
                    GenericSpo2SampleProvider(device, handler.daoSession)
                        .persistSamples(spo2Samples, context)
                }
            } catch (e: Exception) {
                LOG.error("Failed to store {} historic SpO2 samples", spo2Samples.size, e)
            }
        }
        LOG.info(
            "History page {}: day={} records={} steps={} in {} minutes, distance={}m kcal={} hr={} spo2={} minutes {}..{}",
            historyPage, day, records, steps, stepSamples.size, distance, kcal, heartRates.size,
            spo2Samples.size, firstMinute, lastMinute
        )
    }

    /**
     * Size of the fixed counter group a subfielded record carries between its subfield byte and
     * its values, or -1 for the types that have no subfield at all.
     *
     * `b8` and `bc` carry none; `f5` and `f6` carry the three-byte group of an `05` record and
     * `f7` and `87` the four-byte group of an `07`.
     */
    private fun recordGroup(type: Int): Int {
        return when (type) {
            0xb8, 0xbc -> 0
            0xf5, 0xf6 -> 3
            0xf7, 0x87 -> 4
            else -> -1
        }
    }

    /**
     * Payload length of the record at [i].
     *
     * The subfielded types all follow one rule: the subfield is a mask in which `0x08` means a
     * four-value heart rate quad follows and every other set bit adds one more value. That is
     * why `b8 08` runs to four values and `b8 1a` to six, why `f7 09` is a byte longer than
     * `f7 08`, and why `87 01` carries a single value and no quad at all. `bc` is the `b8`
     * shape with two extra bytes in front.
     */
    private fun recordLength(page: ByteArray, i: Int): Int {
        val type = page[i + 2].toInt() and 0xff
        val group = recordGroup(type)
        if (group >= 0) {
            if (i + 3 >= page.size) return -1
            val sub = page[i + 3].toInt() and 0xff
            val quad = if (sub and 0x08 != 0) 4 else 0
            val extra = Integer.bitCount(sub and 0x08.inv() and 0xff)
            return 1 + group + quad + extra + if (type == 0xbc) 2 else 0
        }
        return when (type) {
            0x02, 0x08 -> 1
            0x04, 0x80 -> 2
            0x05, 0x82 -> 3
            0x07 -> 4
            0x85 -> 5
            0xf1, 0xf2 -> 6
            else -> -1
        }
    }

    /** Day summary reply "01 c3 aaaa 00 ... aa 0c <len> <inner sub-TLV>"; steps = inner subfield 0x05. */
    private fun handleDaySummary(value: ByteArray) {
        if (value.size < 5 || value[2] != MODE_GET) return
        val metrics = parseTlv(value, 5)[FIELD_DAY_METRICS] ?: return
        val steps = parseInnerInt(metrics, SUBFIELD_STEPS) ?: return
        storeDailySteps(steps)
    }


    /** Parse a variable-length big-endian integer [subfield] from an inner "<field><len><value>" TLV blob. */
    private fun parseInnerInt(blob: ByteArray, subfield: Int): Int? {
        var i = 0
        while (i + 2 <= blob.size) {
            val field = blob[i].toInt() and 0xff
            val len = blob[i + 1].toInt() and 0xff
            if (i + 2 + len > blob.size) break
            if (field == subfield && len >= 1) {
                var v = 0
                for (k in 0 until len) v = (v shl 8) or (blob[i + 2 + k].toInt() and 0xff)
                return v
            }
            i += 2 + len
        }
        return null
    }

    /**
     * Store today's step total as a delta relative to what was already recorded today, so
     * Gadgetbridge's per-interval sum equals the watch's daily total.
     */
    private fun storeDailySteps(total: Int) {
        try {
            GBApplication.acquireDB().use { handler ->
                val session = handler.daoSession
                val provider = GloryFitStepsSampleProvider(device, session)
                val cal = GregorianCalendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val now = System.currentTimeMillis()
                // The watch keeps the most recent minutes to itself until they age into the
                // history, so the day total is stored as whatever the per-minute records do not
                // account for yet. It goes in one fixed slot at the end of the day: re-syncing
                // then replaces it instead of stacking another remainder on top.
                val remainderSlot = cal.timeInMillis + 86399_000L
                val recorded = provider.getAllSamples(cal.timeInMillis, now)
                    .filter { it.timestamp != remainderSlot }
                    .sumOf { it.totalSteps }
                val delta = total - recorded
                LOG.info("DM58 daily steps total={} recorded={} delta={}", total, recorded, delta)
                if (delta <= 0) return
                val sample = GloryFitStepsSample()
                sample.timestamp = remainderSlot
                sample.totalSteps = delta
                sample.runningStart = 0
                sample.runningEnd = 0
                sample.runningSteps = 0
                sample.walkingStart = 0
                sample.walkingEnd = 0
                sample.walkingSteps = 0
                provider.persistSamples(listOf(sample), context)
            }
        } catch (e: Exception) {
            LOG.error("Failed to store steps", e)
        }
    }

    private fun handleDeviceInfo(value: ByteArray) {
        if (value.size >= 5 && value[2] == MODE_REPORT) {
            // Pushed status update (e.g. on charger connect): 01 a4 ac <field> <len> <battery-blob>
            val len = value[4].toInt() and 0xff
            if (value.size >= 5 + len) {
                parseBattery(value.copyOfRange(5, 5 + len))
            }
            return
        }
        // Full device info reply: 01 a4 aa aa 00 [ aa <field> <len> <val...> ]*
        val fields = parseTlv(value, 5)
        fields[FIELD_FIRMWARE]?.let { fw ->
            val info = GBDeviceEventVersionInfo()
            info.fwVersion = String(fw)
            fields[FIELD_MODEL]?.let { info.hwVersion = String(it) }
            evaluateGBDeviceEvent(info)
            LOG.info("DM58 firmware={}", String(fw))
        }
        fields[FIELD_BATTERY]?.let { parseBattery(it) }
    }

    /** Parse the 6-byte battery blob "01 01 <level> 02 01 <charging>" (sub-TLV). */
    private fun parseBattery(blob: ByteArray) {
        var i = 0
        var level = -1
        var charging = 0
        while (i + 2 <= blob.size) {
            val field = blob[i].toInt() and 0xff
            val len = blob[i + 1].toInt() and 0xff
            if (i + 2 + len > blob.size) break
            when (field) {
                0x01 -> if (len >= 1) level = blob[i + 2].toInt() and 0xff
                0x02 -> if (len >= 1) charging = blob[i + 2].toInt() and 0xff
            }
            i += 2 + len
        }
        if (level in 0..100) {
            val event = GBDeviceEventBatteryInfo()
            event.level = level
            event.state = if (charging != 0) BatteryState.BATTERY_CHARGING else BatteryState.BATTERY_NORMAL
            evaluateGBDeviceEvent(event)
            LOG.info("DM58 battery={}% charging={}", level, charging != 0)
        }
    }

    override fun onNotification(notificationSpec: NotificationSpec) {
        val title = notificationSpec.title ?: notificationSpec.sender ?: notificationSpec.sourceName ?: ""
        val body = notificationSpec.body ?: ""
        // Pick the watch's built-in app icon/name for this app type (unknown -> "Other").
        val appCode = notificationAppCode(notificationSpec.type)
        LOG.debug(
            "Notification from {} (type {}) -> app icon code {}",
            notificationSpec.sourceAppId, notificationSpec.type, appCode
        )
        sendNotification(title, body, appCode)
    }

    /**
     * Resolve the watch's built-in app icon/name code for a notification type.
     *
     * Starts from the shared JieLi mapping and overrides the cases where the classic dialect's
     * choice is wrong on this watch:
     * - Signal (and its Molly fork) bucket into [GloryFitNotificationType.WECHAT] upstream, so a
     *   Signal message shows up as "WeChat". The DM58 has no Signal icon anywhere in the swept
     *   0-63 range, so the neutral SMS ("text") icon is the closest honest match.
     * - Plain SMS buckets into WeChat as well, even though the watch does have a real SMS icon.
     *
     * Code 0 is reserved for an active call: the watch silently drops plain notifications sent
     * with it, so it is never emitted here - anything resolving to it falls back to "Other".
     */
    private fun notificationAppCode(type: NotificationType?): Byte {
        val mapped = when (type) {
            NotificationType.SIGNAL,
            NotificationType.MOLLY,
            NotificationType.GENERIC_SMS -> GloryFitNotificationType.SMS

            NotificationType.WHATSAPP -> GloryFitNotificationType.WHATSAPP

            else -> GloryFitNotificationType.fromNotificationType(type)
        }
        if (mapped == GloryFitNotificationType.CALL) {
            // Never happens with the current mapping, but a future upstream change must not
            // silently make notifications disappear.
            LOG.warn("Type {} mapped to the call-only icon code 0, falling back to 'Other'", type)
            return GloryFitNotificationType.UNKNOWN_APP.code
        }
        return mapped.code
    }

    /**
     * Notification: 01 b0 abab 00 | ab 03 <len> <body UTF-16BE> | ab 07 <len> <title UTF-16BE> |
     * ab 08 01 <appIcon> | ab 09 01 01, followed by a terminator 01 b0 abab fd <xor-of-data-packet>.
     * Text is truncated to fit a single MTU-247 packet (chunking is a TODO).
     */
    private fun sendNotification(titleRaw: String, bodyRaw: String, appCode: Byte = GloryFitNotificationType.UNKNOWN_APP.code) {
        val title = titleRaw.truncateChars(32).toByteArray(Charsets.UTF_16BE)
        val body = bodyRaw.truncateChars(60).toByteArray(Charsets.UTF_16BE)

        val data = ByteArrayOutputStream()
        data.write(byteArrayOf(PKT_HEADER, CMD_NOTIFICATION, MODE_SET, MODE_SET, 0x00))
        data.write(byteArrayOf(MODE_SET, 0x03, body.size.toByte())); data.write(body)
        data.write(byteArrayOf(MODE_SET, 0x07, title.size.toByte())); data.write(title)
        data.write(byteArrayOf(MODE_SET, 0x08, 0x01, appCode))  // app icon/name (watch's built-in set)
        data.write(byteArrayOf(MODE_SET, 0x09, 0x01, 0x01))
        val dataPkt = data.toByteArray()

        var xor = 0
        for (b in dataPkt) xor = xor xor (b.toInt() and 0xff)
        val terminator = byteArrayOf(PKT_HEADER, CMD_NOTIFICATION, MODE_SET, MODE_SET, PKT_TERMINATOR, xor.toByte())

        LOG.debug("Sending notification, {} bytes, app icon code {}", dataPkt.size, appCode)

        val builder = createTransactionBuilder("send notification")
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *dataPkt)
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *terminator)
        builder.queue()
    }

    /**
     * Truncate to at most [max] UTF-16 code units without splitting a surrogate pair, so an
     * emoji on the boundary is dropped whole instead of leaving a lone surrogate in the
     * UTF-16BE payload (which the watch rejects, dropping the notification entirely).
     */
    private fun String.truncateChars(max: Int): String {
        if (length <= max) return this
        val end = if (Character.isHighSurrogate(this[max - 1])) max - 1 else max
        return substring(0, end)
    }

    override fun onFindDevice(start: Boolean) {
        val builder = createTransactionBuilder("find device $start")
        builder.write(
            UUID_CHARACTERISTIC_DATA_WRITE,
            *byteArrayOf(PKT_HEADER, CMD_DEVICE_CONTROL, MODE_SET, 0x04, 0x01, if (start) 0x01 else 0x00)
        )
        builder.queue()
    }

    /** Find-phone: the watch pushes "01 a5 ac 02 01 <01 start / 00 stop>". */
    private fun handleDeviceControl(value: ByteArray) {
        // Find-watch state: the watch reports 01 when it starts ringing and 02 when the wearer
        // dismisses it, so the phone-side session can end with it instead of hanging.
        if (value.size >= 6 && value[2] == MODE_REPORT && value[3] == 0x05.toByte()) {
            if (value[5].toInt() == 0x02) {
                LOG.info("Find watch dismissed on the device")
                val event = GBDeviceEventFindPhone()
                event.event = GBDeviceEventFindPhone.Event.STOP
                evaluateGBDeviceEvent(event)
            }
            return
        }
        if (value.size >= 6 && value[2] == MODE_REPORT && value[3] == 0x02.toByte()) {
            val event = GBDeviceEventFindPhone()
            event.event = if (value[5].toInt() != 0) {
                GBDeviceEventFindPhone.Event.START
            } else {
                GBDeviceEventFindPhone.Event.STOP
            }
            evaluateGBDeviceEvent(event)
        }
    }

    override fun onSetAlarms(alarms: ArrayList<out Alarm>) {
        // 01 c7 abab 00 [per slot: ab 01 02 <idx><idx> | ab 02 02 <idx><weekdayMask> |
        //   ab 03 02 <idx><enabled> | ab 04 03 <idx><HH><MM> | ab 05 01 <idx>] + terminator.
        val data = ByteArrayOutputStream()
        data.write(byteArrayOf(PKT_HEADER, CMD_ALARM, MODE_SET, MODE_SET, 0x00))
        for ((i, alarm) in alarms.withIndex()) {
            val idx = (i + 1).toByte()
            val rep = alarm.repetition
            var mask = 0  // bit0=Sun, bit1=Mon .. bit6=Sat
            if (rep and Alarm.ALARM_SUN.toInt() != 0) mask = mask or 0x01
            if (rep and Alarm.ALARM_MON.toInt() != 0) mask = mask or 0x02
            if (rep and Alarm.ALARM_TUE.toInt() != 0) mask = mask or 0x04
            if (rep and Alarm.ALARM_WED.toInt() != 0) mask = mask or 0x08
            if (rep and Alarm.ALARM_THU.toInt() != 0) mask = mask or 0x10
            if (rep and Alarm.ALARM_FRI.toInt() != 0) mask = mask or 0x20
            if (rep and Alarm.ALARM_SAT.toInt() != 0) mask = mask or 0x40
            data.write(byteArrayOf(MODE_SET, 0x01, 0x02, idx, idx))
            data.write(byteArrayOf(MODE_SET, 0x02, 0x02, idx, mask.toByte()))
            data.write(byteArrayOf(MODE_SET, 0x03, 0x02, idx, if (alarm.enabled) 0x01 else 0x00))
            data.write(byteArrayOf(MODE_SET, 0x04, 0x03, idx, alarm.hour.toByte(), alarm.minute.toByte()))
            data.write(byteArrayOf(MODE_SET, 0x05, 0x01, idx))
        }
        queueTlvWithTerminator("set alarms", CMD_ALARM, data.toByteArray())
    }

    /** Read reply "01 c7 aaaa 00 [0f aa 01 01 <idx> aa 02 01 <mask> aa 03 01 <en> aa 04 02 <HH><MM>]*". */
    private fun handleAlarmData(value: ByteArray) {
        if (value.size < 7 || value[2] != MODE_GET || value[4] == PKT_TERMINATOR) return
        val parsed = HashMap<Int, IntArray>() // position -> [hour, minute, mask, enabled]
        var cur: IntArray? = null
        var i = 4 // after "01 c7 aaaa"; status(00) and per-alarm separators(0f) are skipped below
        while (i < value.size) {
            if (value[i] != MODE_GET) { i += 1; continue }
            if (i + 3 > value.size) break
            val field = value[i + 1].toInt() and 0xff
            val len = value[i + 2].toInt() and 0xff
            if (i + 3 + len > value.size) break
            when (field) {
                0x01 -> {
                    val pos = (value[i + 3].toInt() and 0xff) - 1
                    cur = intArrayOf(0, 0, 0, 0)
                    if (pos >= 0) parsed[pos] = cur
                }
                0x02 -> cur?.set(2, value[i + 3].toInt() and 0xff)
                0x03 -> cur?.set(3, value[i + 3].toInt() and 0xff)
                0x04 -> {
                    cur?.set(0, value[i + 3].toInt() and 0xff)
                    if (len >= 2) cur?.set(1, value[i + 4].toInt() and 0xff)
                }
            }
            i += 3 + len
        }
        if (parsed.isNotEmpty()) storeAlarmsFromWatch(parsed)
    }

    private fun storeAlarmsFromWatch(parsed: Map<Int, IntArray>) {
        // Update Gadgetbridge's alarm DB only; do NOT re-send to the watch (avoids a sync loop that
        // could overwrite alarms just edited on the watch).
        val dbAlarms = DBHelper.getAlarms(device)
        for (dbAlarm in dbAlarms) {
            val a = parsed[dbAlarm.position] ?: continue
            dbAlarm.unused = false
            dbAlarm.enabled = a[3] != 0
            dbAlarm.hour = a[0]
            dbAlarm.minute = a[1]
            dbAlarm.repetition = maskToRepetition(a[2])
            DBHelper.store(dbAlarm)
        }
        LOG.info("DM58 loaded {} alarms from watch", parsed.size)
        // Refresh the alarm UI from the DB (does not re-send to the watch).
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(DeviceService.ACTION_SAVE_ALARMS))
    }

    private fun maskToRepetition(mask: Int): Int {
        var rep = 0
        if (mask and 0x01 != 0) rep = rep or Alarm.ALARM_SUN.toInt()
        if (mask and 0x02 != 0) rep = rep or Alarm.ALARM_MON.toInt()
        if (mask and 0x04 != 0) rep = rep or Alarm.ALARM_TUE.toInt()
        if (mask and 0x08 != 0) rep = rep or Alarm.ALARM_WED.toInt()
        if (mask and 0x10 != 0) rep = rep or Alarm.ALARM_THU.toInt()
        if (mask and 0x20 != 0) rep = rep or Alarm.ALARM_FRI.toInt()
        if (mask and 0x40 != 0) rep = rep or Alarm.ALARM_SAT.toInt()
        return rep
    }

    override fun onSendWeather() {
        val weather: WeatherSpec = Weather.getWeatherSpec() ?: return
        val city = (weather.location ?: "").take(24).toByteArray(Charsets.UTF_16BE)
        val cur = (weather.currentTemp - 273)
        val high = (weather.todayMaxTemp - 273)
        val low = (weather.todayMinTemp - 273)
        val humidity = weather.currentHumidity.coerceIn(0, 100)
        val uv = weather.uvIndex.toInt().coerceIn(0, 15)
        val ts = weather.timestamp

        // Chunk 0 = current weather (forecast chunks are a TODO).
        val data = ByteArrayOutputStream()
        data.write(byteArrayOf(PKT_HEADER, CMD_WEATHER, MODE_SET, MODE_SET, 0x00))
        data.write(byteArrayOf(MODE_SET, 0x05, city.size.toByte())); data.write(city)
        data.write(byteArrayOf(MODE_SET, 0x06, 0x02, hi(cur), lo(cur)))
        data.write(byteArrayOf(MODE_SET, 0x07, 0x02, hi(high), lo(high)))
        data.write(byteArrayOf(MODE_SET, 0x08, 0x02, hi(low), lo(low)))
        data.write(byteArrayOf(MODE_SET, 0x0a, 0x01, uv.toByte()))
        data.write(byteArrayOf(MODE_SET, 0x0b, 0x04, (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte()))
        // f0f = [humidity][condition].
        data.write(byteArrayOf(MODE_SET, 0x0f, 0x02, humidity.toByte(), mapConditionToWatch(weather.currentConditionCode).toByte()))
        queueTlvWithTerminator("send weather", CMD_WEATHER, data.toByteArray())

        sendForecast(weather)
    }

    /** Big-endian 4-byte unix timestamp. */
    private fun be32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /**
     * Forecast is a separate e0 message: "ab0b <update ts>" then a run of hourly entries
     * (ab0d) and daily entries (ab0e), split into <=239-byte chunks "01 e0 abab <idx> ..."
     * ending with "01 e0 abab fd <xor over every transmitted byte>".
     * Hourly ab0d = {01: ts(4), 02: flag(1), 03: temp C(2)}.
     * Daily  ab0e = {01: high C(2), 02: low C(2), 03:(1)=0, 04: moonrise(4), 05: moonset(4),
     *               06:(1)=0, 07: sunrise(4), 08: sunset(4), 09: day midnight(4), 0a: humidity(1),
     *               0b: condition(1)}.
     */
    private fun sendForecast(weather: WeatherSpec) {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(MODE_SET, 0x0b, 0x04)); body.write(be32(weather.timestamp))

        // Hourly entries (may be empty depending on the weather source).
        for (h in weather.hourly.take(24)) {
            val temp = h.temp - 273
            val entry = ByteArrayOutputStream()
            entry.write(byteArrayOf(0x01, 0x04)); entry.write(be32(h.timestamp))
            entry.write(byteArrayOf(0x02, 0x01, mapConditionToWatch(h.conditionCode).toByte()))
            entry.write(byteArrayOf(0x03, 0x02, hi(temp), lo(temp)))
            val e = entry.toByteArray()
            body.write(byteArrayOf(MODE_SET, 0x0d, e.size.toByte())); body.write(e)
        }

        // Daily entries: today first, then the per-day forecasts.
        val days = ArrayList<WeatherSpec.Daily>()
        days.add(weather.todayAsDaily())
        days.addAll(weather.forecasts)
        val midnight = Calendar.getInstance()
        midnight.timeInMillis = weather.timestamp * 1000L
        midnight.set(Calendar.HOUR_OF_DAY, 0)
        midnight.set(Calendar.MINUTE, 0)
        midnight.set(Calendar.SECOND, 0)
        midnight.set(Calendar.MILLISECOND, 0)
        for ((i, d) in days.take(7).withIndex()) {
            val dayCal = midnight.clone() as Calendar
            dayCal.add(Calendar.DAY_OF_MONTH, i)
            val dayStart = (dayCal.timeInMillis / 1000L).toInt()
            val high = d.maxTemp - 273
            val low = d.minTemp - 273
            val humidity = (if (d.humidity in 1..100) d.humidity else weather.currentHumidity).coerceIn(0, 100)
            val sunrise = if (d.sunRise > 0) d.sunRise else dayStart + 6 * 3600
            val sunset = if (d.sunSet > 0) d.sunSet else dayStart + 21 * 3600
            val moonrise = if (d.moonRise > 0) d.moonRise else dayStart + 8 * 3600
            val moonset = if (d.moonSet > 0) d.moonSet else dayStart + 23 * 3600
            val cond = mapConditionToWatch(d.conditionCode)
            val entry = ByteArrayOutputStream()
            entry.write(byteArrayOf(0x01, 0x02, hi(high), lo(high)))
            entry.write(byteArrayOf(0x02, 0x02, hi(low), lo(low)))
            entry.write(byteArrayOf(0x03, 0x01, 0x00))
            entry.write(byteArrayOf(0x04, 0x04)); entry.write(be32(moonrise))
            entry.write(byteArrayOf(0x05, 0x04)); entry.write(be32(moonset))
            entry.write(byteArrayOf(0x06, 0x01, cond.toByte()))
            entry.write(byteArrayOf(0x07, 0x04)); entry.write(be32(sunrise))
            entry.write(byteArrayOf(0x08, 0x04)); entry.write(be32(sunset))
            entry.write(byteArrayOf(0x09, 0x04)); entry.write(be32(dayStart))
            entry.write(byteArrayOf(0x0a, 0x01, humidity.toByte()))
            entry.write(byteArrayOf(0x0b, 0x01, cond.toByte()))
            val e = entry.toByteArray()
            body.write(byteArrayOf(MODE_SET, 0x0e, e.size.toByte())); body.write(e)
        }

        queueChunkedTlv("send weather forecast", CMD_WEATHER, body.toByteArray())
    }

    /**
     * Map an OpenWeatherMap condition code to the watch's icon code. Best-effort (the watch's
     * exact code table is not documented); refine by comparing icons on hardware.
     */
    /**
     * Map an OpenWeatherMap condition code to the watch icon code. Icon codes 0..31 were
     * catalogued on hardware (34+ are placeholders/other resources; see the protocol doc):
     * 0 clear, 1 partly cloudy, 2 cloudy, 3 rain, 4 thunderstorm, 5 thunderstorm+hail,
     * 6 hail/ice, 7 rain, 8 heavy rain, 9 extreme rain, 10 showers, 11 heavy showers,
     * 12 violent showers, 13 snow shower, 14 light snow, 15 snow, 16 heavy snow,
     * 17 snowstorm, 18 fog/mist, 19 sleet, 20 blizzard.
     */
    private fun mapConditionToWatch(owm: Int): Int = when (owm) {
        in 200..232 -> 4                 // thunderstorm
        in 300..399 -> 3                 // drizzle -> light rain (cloud+sun+rain, visually lighter)
        500 -> 3                         // light rain -> cloud+sun+rain
        501 -> 7                         // rain
        502 -> 8                         // heavy rain
        503, 504 -> 9                    // very heavy / extreme rain
        511 -> 6                         // freezing rain -> hail/ice
        520 -> 3                         // light shower -> changeable (cloud+sun+rain)
        521, 531 -> 10                   // shower
        522 -> 11                        // heavy shower
        in 505..599 -> 7                 // other rain
        600 -> 14                        // light snow
        601, 621 -> 15                   // snow
        602 -> 16                        // heavy snow
        611, 612, 613, 615, 616 -> 19    // sleet (rain + snow)
        620 -> 13                        // light shower snow
        622 -> 17                        // heavy shower snow
        in 600..699 -> 15                // other snow
        771 -> 12                        // squall
        781 -> 4                         // tornado -> storm
        in 700..799 -> 18                // fog / mist / haze / dust
        800 -> 0                         // clear
        801, 802 -> 1                    // few / scattered clouds
        803, 804 -> 2                    // broken / overcast
        906 -> 6                         // hail
        in 900..999 -> 4                 // extreme
        else -> 2
    }
    // Watch icon codes (verified on hardware): 0 clear, 1 partly cloudy, 2 cloudy,
    // 3 light rain, 4 thunderstorm, 5 hail, 6 snow, 7 rain, 8 heavy rain.

    /**
     * Write a chunked TLV message: "01 CMD abab <idx> <body slice>" per BLE write, followed by
     * "01 CMD abab fd <xor>", where the xor is over every byte of every write (index bytes and
     * the repeated 01 CMD abab prefixes cancel out, so this also equals the xor of the body).
     */
    private fun queueChunkedTlv(label: String, cmd: Byte, body: ByteArray) {
        val builder = createTransactionBuilder(label)
        var xor = 0
        var off = 0
        var idx = 0
        val maxSlice = 239
        while (off < body.size) {
            val end = minOf(off + maxSlice, body.size)
            val pkt = ByteArrayOutputStream()
            pkt.write(byteArrayOf(PKT_HEADER, cmd, MODE_SET, MODE_SET, idx.toByte()))
            pkt.write(body, off, end - off)
            val pktBytes = pkt.toByteArray()
            for (b in pktBytes) xor = xor xor (b.toInt() and 0xff)
            builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *pktBytes)
            off = end
            idx++
        }
        val terminator = byteArrayOf(PKT_HEADER, cmd, MODE_SET, MODE_SET, PKT_TERMINATOR, xor.toByte())
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *terminator)
        builder.queue()
    }

    private fun hi(v: Int): Byte = ((v ushr 8) and 0xff).toByte()
    private fun lo(v: Int): Byte = (v and 0xff).toByte()

    /** Write a TLV "01 CMD abab 00 ..." data packet followed by "01 CMD abab fd <xor>". */
    override fun onSetContacts(contacts: ArrayList<out Contact>) {
        val builder = createTransactionBuilder("set contacts")
        // START
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, PKT_HEADER, CMD_CONTACTS, MODE_SET, 0x06, 0x01, 0x00)
        // Contact list (field 0x07): [ numberLen | number UTF-16BE | nameLen | name UTF-16BE ]*
        val payload = ByteArrayOutputStream()
        for (contact in contacts) {
            val number = (contact.number ?: "").take(64).toByteArray(Charsets.UTF_16BE)
            val name = (contact.name ?: "").take(64).toByteArray(Charsets.UTF_16BE)
            if (number.isEmpty()) continue
            payload.write(number.size); payload.write(number)
            payload.write(name.size); payload.write(name)
        }
        writeContactsField(builder, 0x07, payload.toByteArray())
        // END / commit
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, PKT_HEADER, CMD_CONTACTS, MODE_SET, 0x09, 0x01, 0x01)
        builder.queue()
    }

    /**
     * Write a contacts field (0x07 = list, 0x08 = SOS) as
     * "01 c0 ab <field> <offset:2> <len> <slice>" per BLE write (offset = byte offset into the
     * payload; a single frame for lists that fit), closed by "01 c0 ab <field> fd <xor>" whose
     * xor covers every transmitted byte of this field.
     */
    private fun writeContactsField(builder: TransactionBuilder, field: Byte, payload: ByteArray) {
        var xor = 0
        var off = 0
        val maxSlice = 230
        do {
            val end = minOf(off + maxSlice, payload.size)
            val frame = ByteArrayOutputStream()
            frame.write(byteArrayOf(PKT_HEADER, CMD_CONTACTS, MODE_SET, field,
                (off ushr 8).toByte(), off.toByte(), (end - off).toByte()))
            frame.write(payload, off, end - off)
            val fb = frame.toByteArray()
            for (b in fb) xor = xor xor (b.toInt() and 0xff)
            builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *fb)
            off = end
        } while (off < payload.size)
        val terminator = byteArrayOf(PKT_HEADER, CMD_CONTACTS, MODE_SET, field, PKT_TERMINATOR, xor.toByte())
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *terminator)
    }

    private fun queueTlvWithTerminator(label: String, cmd: Byte, dataPkt: ByteArray) {
        var xor = 0
        for (b in dataPkt) xor = xor xor (b.toInt() and 0xff)
        val terminator = byteArrayOf(PKT_HEADER, cmd, MODE_SET, MODE_SET, PKT_TERMINATOR, xor.toByte())
        val builder = createTransactionBuilder(label)
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *dataPkt)
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *terminator)
        builder.queue()
    }

    private fun setTime(builder: TransactionBuilder) {
        val now = GregorianCalendar.getInstance()
        val epoch = (now.timeInMillis / 1000L).toInt()  // watch expects UTC unix time
        // Timezone field: [0x80|hours][minutes] for offsets east of UTC (e.g. UTC+2 -> 0x82 0x00).
        val offsetMinutes = (now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)) / 60_000
        val tzHours = Math.abs(offsetMinutes) / 60
        val tzMinutes = Math.abs(offsetMinutes) % 60
        val tzSign = if (offsetMinutes >= 0) 0x80 else 0x00
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put(PKT_HEADER)
        buf.put(CMD_TIME)
        buf.put(0x01)          // field: set time
        buf.put(0x04)          // len 4
        buf.putInt(epoch)      // big-endian UTC unix timestamp
        buf.put(0x10)          // field: timezone
        buf.put(0x02)          // len 2
        buf.put((tzSign or tzHours).toByte())
        buf.put(tzMinutes.toByte())
        builder.write(UUID_CHARACTERISTIC_DATA_WRITE, *buf.array())
    }

    /** Build a "get all fields" request: 01 CMD MODE MODE (mode byte doubled). */
    private fun cmdGetAll(cmd: Byte): ByteArray {
        return byteArrayOf(PKT_HEADER, cmd, MODE_GET, MODE_GET)
    }

    /** Parse repeating `<MODE> <field> <len> <value...>` TLV entries starting at [start]. */
    private fun parseTlv(value: ByteArray, start: Int): Map<Byte, ByteArray> {
        val out = HashMap<Byte, ByteArray>()
        var i = start
        while (i + 3 <= value.size) {
            // mode byte (aa/ab/ac), field, len
            val field = value[i + 1]
            val len = value[i + 2].toInt() and 0xff
            val from = i + 3
            val to = from + len
            if (to > value.size) break
            out[field] = value.copyOfRange(from, to)
            i = to
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GloryFitProSupport::class.java)

        val UUID_SERVICE_CMD: UUID = UUID.fromString("000055ff-0000-1000-8000-00805f9b34fb")
        val UUID_CHARACTERISTIC_CMD_WRITE: UUID = UUID.fromString("000035f1-0000-1000-8000-00805f9b34fb")
        val UUID_CHARACTERISTIC_CMD_READ: UUID = UUID.fromString("000035f2-0000-1000-8000-00805f9b34fb")

        val UUID_SERVICE_DATA: UUID = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
        val UUID_CHARACTERISTIC_DATA_WRITE: UUID = UUID.fromString("000034f1-0000-1000-8000-00805f9b34fb")
        val UUID_CHARACTERISTIC_DATA_READ: UUID = UUID.fromString("000034f2-0000-1000-8000-00805f9b34fb")

        const val PKT_HEADER: Byte = 0x01
        const val PKT_TERMINATOR: Byte = 0xfd.toByte()

        const val MODE_GET: Byte = 0xaa.toByte()
        const val MODE_SET: Byte = 0xab.toByte()
        const val MODE_REPORT: Byte = 0xac.toByte()

        const val CMD_BATTERY: Byte = 0xa2.toByte()
        const val CMD_TIME: Byte = 0xa3.toByte()
        const val CMD_DEVICE_INFO: Byte = 0xa4.toByte()
        const val CMD_NOTIFICATION: Byte = 0xb0.toByte()
        const val CMD_ACTIVITY_DAY: Byte = 0xc3.toByte()
        const val CMD_MUSIC_CONTROL: Byte = 0xe2.toByte()
        const val CMD_DEVICE_CONTROL: Byte = 0xa5.toByte()
        const val CMD_ALARM: Byte = 0xc7.toByte()
        const val CMD_WEATHER: Byte = 0xe0.toByte()
        const val CMD_CONTACTS: Byte = 0xc0.toByte()
        const val CMD_HEALTH: Byte = 0xc6.toByte()
        const val CMD_HEALTH_COUNT: Byte = 0xc5.toByte()
        const val CMD_HEALTH_MONITOR: Byte = 0xc4.toByte()
        const val CMD_FEATURE: Byte = 0xe1.toByte()
        const val CMD_DND: Byte = 0xea.toByte()

        /** Watch language codes, each one observed being written by the official app. */
        val LANGUAGE_CODES: Map<String, Byte> = mapOf(
            "en_US" to 0x02, "de_DE" to 0x05, "fr_FR" to 0x07,
            "it_IT" to 0x08, "ru_RU" to 0x0e, "nl_NL" to 0x0f
        )
        const val MAX_HISTORY_PAGES: Int = 64

        /**
         * Ceiling on how far back a catch-up sync reaches. The watch keeps about nine
         * days of per-minute history - a 120-day request answers with 44 pages - so
         * anything beyond this only spends round trips on a range the watch cannot fill.
         */
        const val MAX_LOOKBACK_DAYS: Int = 30
        const val CMD_WORKOUT: Byte = 0xe8.toByte()
        const val MAX_WORKOUTS: Int = 4
        const val MAX_WORKOUT_PAGES: Int = 24

        const val FIELD_MODEL: Byte = 0x02
        const val FIELD_FIRMWARE: Byte = 0x15
        const val FIELD_BATTERY: Byte = 0x16
        const val FIELD_DAY_METRICS: Byte = 0x0c
        const val FIELD_SLEEP: Byte = 0x04
        const val SLEEP_SESSION_START: Int = 0x07
        const val SLEEP_SESSION_END: Int = 0x08
        const val SLEEP_STAGE_DEEP: Int = 0x01
        const val SLEEP_STAGE_REM: Int = 0x04
        /** The watch keeps months of sessions and answers in one small message. */
        const val SLEEP_DAYS: Int = 168
        const val SUBFIELD_STEPS: Int = 0x05
    }
}
