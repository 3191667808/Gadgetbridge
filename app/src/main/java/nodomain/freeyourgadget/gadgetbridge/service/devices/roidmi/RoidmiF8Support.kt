/*  Copyright (C) 2026 David Girón

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.roidmi

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.widget.Toast
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.devices.BatteryCurrentSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryCurrentSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.devices.gatt_client.BleGattClientSupport
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Device support for the Roidmi F8 Cordless Vacuum Cleaner (XCQ03RM / "ROIDMI Cleaner F1").
 *
 * ## Connection flow
 * On connect, subscribe to all FFD0 telemetry characteristics, read their initial values
 * and send the D5FF init command `55 55` to activate the command channel.
 *
 * ## Sensor payload format (DxFF telemetry chars)
 * ```
 * [opcode][0][0][0][0][0][value_hi][value_lo][checksum]
 * ```
 * - D2FF (0x0029) – pack voltage + temperature; subtype in value[0]: 0x21 → voltage in
 *   bytes[4..5]/100 V; 0x22 → temperature in bytes[4..5]/100 °C and voltage in bytes[6..7]/100 V
 * - D4FF (0x0031) – active gear (byte[2]) + cumulative cleaning time (bytes[6..7],
 *   little-endian, minutes)
 * - D7FF (0x003D) – control / idle flags
 * - D8FF (0x0041) – run state + current: byte[1]=0x00 running; byte[5]/100 → A
 * - DBFF (0x004D) – secondary ~19 V rail (not the app's battery voltage), bytes[6..7] / 100 → V
 *
 * ## Configuration writes (ATT Write Command to D7FF / 0x003D)
 * The application value written is the ROIDMI payload only (no ATT header bytes):
 * - Standard gear 80/130/180 W: `71 0x 00 00` (x=0,1,2)
 * - Dust full reminder off/on:  `75 75 00 00` / `76 76 00 00`
 * - Reset filter timer:         `73 73`
 */
class RoidmiF8Support : BleGattClientSupport() {

    private enum class DeviceState(val code: Int) {
        RUNNING(0x00),
        IDLE(0x01),
        CHARGING(0x02);

        companion object {
            fun fromCode(code: Int): DeviceState? = values().firstOrNull { it.code == code }
        }
    }

    // ── Resolved GATT characteristic instances ────────────────────────────────

    private var charD1FF: BluetoothGattCharacteristic? = null
    private var charTemperature: BluetoothGattCharacteristic? = null   // D2FF
    private var charD4FF: BluetoothGattCharacteristic? = null
    private var charCommand: BluetoothGattCharacteristic? = null       // D5FF
    private var charControl: BluetoothGattCharacteristic? = null       // D7FF
    private var charStatus: BluetoothGattCharacteristic? = null        // D8FF
    private var charVoltage: BluetoothGattCharacteristic? = null       // DBFF
    private var charAuth: BluetoothGattCharacteristic? = null          // FE95 0x0012 – RC4 challenge/response
    private var charAuthInit: BluetoothGattCharacteristic? = null      // FE95 0x001b – MI_KEY1 write
    private var charVersion: BluetoothGattCharacteristic? = null       // FE95 0x0017 – firmware token (read after auth)

    /** Last computed battery level (0–100); -1 = unknown. */
    private var lastBatteryLevel = -1
    /** Whether the pack is currently charging (derived from the D8FF status flags). */
    private var charging = false
    /** Whether the motor is currently running (D8FF state 0x00); the pack voltage sags under load. */
    private var running = false

    /** Last measured pack voltage (V); <= 0 = unknown. */
    private var lastBatteryVoltage = 0f

    /** Last charge current reported on D8FF while charging (A); used to detect a full charge. */
    private var lastChargeCurrentAmps = 0f

    /** Firmware major from the D5FF 0x53 sensor response (e.g. "v0.5" → "5"); null = unknown. */
    private var firmwareMajor: String? = null

    /** Firmware build from the D5FF 0x51 response (e.g. "v_1.0.0.1" → "1001"); null = unknown. */
    private var firmwareBuild: String? = null

    /** Firmware revision from the D5FF 0x53 response: hex of the pre-dot ASCII byte ('0' → "30"). */
    private var firmwareRevision: String? = null

    /** Guards against acting on more than one AUTH notification per handshake. */
    @Volatile
    private var authDone = false

    init {
        addSupportedService(UUID_SERVICE_FFD0)
        addSupportedService(UUID_SERVICE_FE95)
        // The parent (BleGattClientSupport) adds the standard GATT battery and device-info
        // services by default. This device does not use them – remove them to avoid
        // unexpected characteristic reads or subscriptions on those services.
        supportedServices.remove(GattService.UUID_SERVICE_BATTERY_SERVICE)
        supportedServices.remove(GattService.UUID_SERVICE_DEVICE_INFORMATION)
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Initialisation: resolve all characteristic references and start the Xiaomi FE95
     * auth handshake. FFD0 subscriptions are enqueued after auth completes
     * (see [handleAuthNotification]). If the FE95 service is absent, FFD0 setup is
     * performed directly.
     */
    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        charD1FF = getCharacteristic(UUID_CHAR_D1FF)
        charTemperature = getCharacteristic(UUID_CHAR_D2FF)
        charD4FF = getCharacteristic(UUID_CHAR_D4FF)
        charCommand = getCharacteristic(UUID_CHAR_D5FF)
        charControl = getCharacteristic(UUID_CHAR_D7FF)
        charStatus = getCharacteristic(UUID_CHAR_D8FF)
        charVoltage = getCharacteristic(UUID_CHAR_DBFF)
        charAuth = getCharacteristic(UUID_CHAR_FE95_AUTH)
        charAuthInit = getCharacteristic(UUID_CHAR_FE95_INIT)
        charVersion = getCharacteristic(UUID_CHAR_FE95_VERSION)

        if (charTemperature == null || charStatus == null ||
            charVoltage == null || charControl == null || charCommand == null
        ) {
            LOG.warn("initializeDevice: required FFD0 chars not found – aborting")
            builder.setDeviceState(GBDevice.State.NOT_CONNECTED)
            return builder
        }

        builder.setDeviceState(GBDevice.State.AUTHENTICATING)

        val auth = charAuth
        val authInit = charAuthInit
        if (auth != null && authInit != null) {
            // Xiaomi MiBeacon (Mi Kettle-style) RC4 handshake. The device drops the link
            // unless it is authenticated with the user's miio token before any command.
            val token = getToken()
            if (token.size < 12) {
                LOG.warn("Xiaomi auth: no valid miio token configured – authentication required")
                builder.setDeviceState(GBDevice.State.AUTHENTICATION_REQUIRED)
                GB.toast(context, R.string.authentication_failed_check_key, Toast.LENGTH_LONG, GB.WARN)
                val device = getDevice()
                if (device != null) {
                    GBApplication.deviceService(device).disconnect()
                }
                return builder
            }
            authDone = false
            val reversedMac = parseMacReversed(device.address)
            val frame = rc4(mixA(reversedMac, PRODUCT_ID), token)
            LOG.debug("Xiaomi auth: starting MiBeacon RC4 handshake")
            //  1. Subscribe to AUTH notifications (CCCD 0x0013).
            builder.notify(auth, true)
            //  2. Write MI_KEY1 to AUTH-INIT (0x001b) to open the handshake.
            builder.write(authInit, *MI_KEY1)
            //  3. Write RC4(mixA(reversedMac, productId), token) to AUTH (0x0012).
            //     The device replies with a notification handled in handleAuthNotification().
            builder.write(auth, *frame)
        } else {
            LOG.warn("initializeDevice: FE95 auth service not found – skipping auth")
            enqueueFFD0Setup(builder)
        }
        return builder
    }

    /**
     * Queues all FFD0 CCCD subscriptions, initial reads, the D5FF init sequence and
     * sets the device state to INITIALIZED. Called either directly (no auth service)
     * or from [handleAuthNotification] after the handshake completes.
     */
    private fun enqueueFFD0Setup(builder: TransactionBuilder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING)
        // Subscribe (CCCD) + initial read for all notify-capable FFD0 characteristics.
        // This matches the sequence used by the original ROIDMI application.
        subscribeAndRead(builder, charD1FF)
        subscribeAndRead(builder, charTemperature)   // D2FF – temperature
        subscribeAndRead(builder, getCharacteristic(UUID_CHAR_D3FF))
        subscribeAndRead(builder, charD4FF)
        // D5FF: command channel – subscribe only, no read.
        builder.notify(charCommand, true)
        // D6FF: read-only MAC address – no CCCD.
        getCharacteristic(UUID_CHAR_D6FF)?.let { builder.read(it) }
        subscribeAndRead(builder, charControl)       // D7FF – control flags (NOTIFY+WRITE+READ)
        subscribeAndRead(builder, charStatus)        // D8FF – run state + current
        subscribeAndRead(builder, getCharacteristic(UUID_CHAR_D9FF))
        subscribeAndRead(builder, getCharacteristic(UUID_CHAR_DAFF))
        subscribeAndRead(builder, charVoltage)       // DBFF – pack voltage
        subscribeAndRead(builder, getCharacteristic(UUID_CHAR_DCFF))

        // D5FF init sequence: activate command channel and request device info.
        // The device only answers one query at a time and drops queries sent too soon
        // after the "55 55" init, so space the writes out like the official app does.
        builder.write(charCommand, *CMD_INIT)
        builder.sleep(D5FF_QUERY_DELAY_MS)
        builder.write(charCommand, *CMD_VERSION_INFO)
        builder.sleep(D5FF_QUERY_DELAY_MS)
        builder.write(charCommand, *CMD_FILTER_INFO)
        builder.sleep(D5FF_QUERY_DELAY_MS)
        builder.write(charCommand, *CMD_SENSOR_INFO)

        builder.setDeviceState(GBDevice.State.INITIALIZED)
    }

    /**
     * Handles the single AUTH notification of the MiBeacon RC4 handshake.
     *
     * By the time this fires, the client has already written [MI_KEY1] to AUTH-INIT and
     * `RC4(mixA(reversedMac, productId), token)` to AUTH. The device replies with its own
     * 12-byte confirmation frame (not validated here). The client completes the handshake
     * by writing `RC4(token, MI_KEY2)` to AUTH and reading the VERSION characteristic,
     * then proceeds to FFD0 setup.
     *
     * This exact sequence and its RC4 key schedule were verified byte-for-byte against a
     * real handshake capture (see `roidmi.txt`).
     */
    private fun handleAuthNotification(value: ByteArray) {
        if (authDone) {
            LOG.debug("Xiaomi auth: extra AUTH notification ignored: {}", GB.hexdump(value))
            return
        }
        authDone = true
        LOG.debug("Xiaomi auth: device confirm={}", GB.hexdump(value))
        try {
            val b = createTransactionBuilder("auth_finish")
            // Final client frame: RC4(token, MI_KEY2).
            b.write(charAuth, *rc4(getToken(), MI_KEY2))
            // Read VERSION to complete authentication (device firmware token).
            charVersion?.let { b.read(it) }
            // Authenticated: enqueue the FFD0 telemetry setup and go INITIALIZED.
            enqueueFFD0Setup(b)
            b.queue()
        } catch (e: Exception) {
            LOG.error("handleAuthNotification: failed to finish handshake", e)
        }
    }

    /**
     * Returns the Xiaomi/miio device token from device preferences.
     *
     * The token is entered by the user during pairing (via `AuthKeyActivity`) and stored
     * under the standard [DeviceSettingsPreferenceConst.PREF_AUTH_KEY] preference.
     *
     * The value must be a hex string; an optional `"0x"` prefix is accepted. Returns an
     * empty array if not configured or invalid (the MiBeacon token is 12 bytes / 24 hex
     * chars).
     */
    private fun getToken(): ByteArray {
        val prefs = GBApplication.getDeviceSpecificSharedPrefs(device.address)
        var hex = prefs.getString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, "").orEmpty().trim()
        // Allow "0x" prefix to avoid user mistakes
        if (hex.length > 2 && hex.startsWith("0x")) {
            hex = hex.substring(2)
        }
        if (hex.isEmpty()) {
            return ByteArray(0)
        }
        if (hex.length % 2 != 0 || hex.length > 32) {
            LOG.warn("getToken: token length {} is invalid (expected 24 hex chars)", hex.length)
            return ByteArray(0)
        }
        return try {
            StringUtils.hexToBytes(hex)
        } catch (e: NumberFormatException) {
            LOG.warn("getToken: invalid token '{}'", hex)
            ByteArray(0)
        }
    }

    /** Subscribes to notifications and queues an initial read for [characteristic]. */
    private fun subscribeAndRead(
        builder: TransactionBuilder,
        characteristic: BluetoothGattCharacteristic?,
    ) {
        if (characteristic == null) return
        builder.notify(characteristic, true)
        builder.read(characteristic)
    }

    // ── Misc overrides ────────────────────────────────────────────────────────

    override fun useAutoConnect(): Boolean = false

    // ── GATT callbacks ────────────────────────────────────────────────────────

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ): Boolean {
        // Explicitly skip the parent's standard GATT battery level handler – this device
        // reports battery via pack voltage on DBFF, not via UUID_CHARACTERISTIC_BATTERY_LEVEL.
        if (characteristic.uuid == GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL) {
            return true
        }
        if (super.onCharacteristicRead(gatt, characteristic, value, status)) {
            return true
        }
        return when (characteristic) {
            charTemperature -> { handleTemperature(value, status); true }
            charVoltage -> { handleVoltage(value, status); true }
            charStatus -> { handleStatus(value, status); true }
            charD4FF -> { if (status == BluetoothGatt.GATT_SUCCESS) handleGear(value); true }
            charVersion -> { LOG.debug("Xiaomi auth: VERSION={}", GB.hexdump(value)); true }
            else -> false
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true
        }

        return when (characteristic) {
            // ── FE95 auth ───────────────────────────────────────────────────
            charAuth -> { handleAuthNotification(value); true }
            // ── FFD0 telemetry ──────────────────────────────────────────────
            charTemperature -> { handleTemperature(value, BluetoothGatt.GATT_SUCCESS); true }
            charVoltage -> { handleVoltage(value, BluetoothGatt.GATT_SUCCESS); true }
            charStatus -> { handleStatus(value, BluetoothGatt.GATT_SUCCESS); true }
            charD1FF -> { handleD1FFNotification(value); true }
            charD4FF -> { handleGear(value); true }
            // Control channel (D7FF) – log raw values for future parsing.
            charControl -> {
                LOG.debug("DxFF notification {}: {}", characteristic.uuid, GB.hexdump(value)); true
            }
            charCommand -> { handleCommandResponse(value); true }
            else -> {
                LOG.warn("Unhandled characteristic notification: {}", characteristic.uuid); false
            }
        }
    }

    // ── Telemetry parsers ─────────────────────────────────────────────────────

    /**
     * D1FF – event / status sub-type notification (ATT handle 0x0025).
     *
     * Observed subtypes (byte[0]):
     * - `0x11` – working mode / gear change confirmation
     * - `0x12` – dust-bin full event
     * - `0x13` – filter maintenance reminder
     * - `0x17` – device identifies / wakeup ping
     *
     * All other subtypes are logged at DEBUG level for future analysis.
     */
    private fun handleD1FFNotification(value: ByteArray) {
        if (value.isEmpty()) return
        when (val subtype = value[0].toInt() and 0xFF) {
            0x11 -> LOG.info("D1FF: working-mode / gear change, raw={}", GB.hexdump(value))
            0x12 -> LOG.info("D1FF: dust-bin full event")
            0x13 -> LOG.info("D1FF: filter maintenance reminder")
            0x17 -> LOG.info("D1FF: device wakeup / identify ping")
            else -> LOG.debug(
                "D1FF: unknown subtype 0x{}: {}",
                String.format("%02X", subtype), GB.hexdump(value)
            )
        }
    }

    /**
     * D4FF – active suction gear + filter counters (ATT handle 0x0031).
     * - byte[2] is the gear index the device is currently set to (0 = 80 W, 1 = 130 W, 2 = 180 W).
     *   Mirrored into the GB preference so the settings UI reflects the value actually configured
     *   on the device, which may differ from whatever GB wrote in a previous session.
     * - bytes[6..7] (little-endian uint16) → cumulative cleaning time in minutes. This is the
     *   counter behind the official app's filter info card: it accumulates motor-on minutes and
     *   is zeroed by "Reset filter used time" (`73 73`). byte[8] is the running checksum
     *   `(byte[6] + byte[7]) & 0xFF`.
     *
     * Example: `41 00 00 00 00 00 09 01 0A` → gear 0, 0x0109 = 265 min.
     */
    private fun handleGear(value: ByteArray) {
        // D4FF gear needs at least 3 bytes (the gear index is at byte[2]).
        if (value.size < 3) return
        val gear = value[2].toInt() and 0xFF
        // Highest valid standard-gear index (0 = 80 W, 1 = 130 W, 2 = 180 W).
        if (gear > 2) {
            LOG.debug("D4FF: unexpected gear index {}", gear)
            return
        }
        LOG.info("Roidmi F8 suction gear index: {}", gear)
        val editor = GBApplication.getDeviceSpecificSharedPrefs(device.address).edit()
            .putString(DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_STANDARD_GEAR, gear.toString())

        // bytes[6..7] (little-endian) = cumulative cleaning time in minutes.
        if (value.size >= 9) {
            val minutes = (value[6].toInt() and 0xFF) or ((value[7].toInt() and 0xFF) shl 8)
            LOG.info("Roidmi F8 cumulative cleaning time: {} min", minutes)
            editor.putString(DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_CLEANING_TIME, "$minutes min")
        }
        editor.apply()
    }

    /**
     * D2FF – temperature + pack voltage (ATT handle 0x0029).
     * The frame subtype in value[0] selects which measurement bytes[4..5] carries; both subtypes
     * expose the pack voltage the official app shows as the battery voltage (e.g. 32.90 V), which
     * is the value that tracks the state of charge (the DBFF/0x004D rail is a separate ~19 V line):
     * - `0x21`: bytes[4..5] / 100 → pack voltage V (bytes[6..7] is a latched copy, ignored).
     *   Example: `21 00 00 00 0C DA 0D 18 0B` → 0x0CDA = 3290 → 32.90 V.
     * - `0x22`: bytes[4..5] / 100 → pack temperature °C, bytes[6..7] / 100 → pack voltage V.
     *   Example: `22 00 00 00 0B E1 0D 18 11` → temp 0x0BE1 = 3041 → 30.41 °C, pack 0x0D18 → 33.52 V.
     */
    private fun handleTemperature(value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.warn("handleTemperature: GATT error {}", status)
            return
        }
        // Sensor characteristics need at least 8 bytes (indices 0–7).
        if (value.size < 8) {
            LOG.warn("handleTemperature: payload too short ({})", value.size)
            return
        }
        val field45 = ((value[4].toInt() and 0xFF) shl 8) or (value[5].toInt() and 0xFF)
        when (value[0].toInt() and 0xFF) {
            SENSOR_FRAME_VOLTAGE -> {
                val voltage = field45 / 100.0f
                LOG.info("Roidmi F8 pack voltage: {} V", voltage)
                lastBatteryVoltage = voltage
                reportBattery()
            }
            SENSOR_FRAME_TEMPERATURE -> {
                val tempCelsius = field45 / 100.0f
                device.setExtraInfo(EXTRA_TEMPERATURE_CELSIUS, tempCelsius)
                val voltage = (((value[6].toInt() and 0xFF) shl 8) or (value[7].toInt() and 0xFF)) / 100.0f
                LOG.info("Roidmi F8 temperature: {} °C, pack voltage: {} V", tempCelsius, voltage)
                lastBatteryVoltage = voltage
                reportBattery()
            }
            else -> LOG.debug("Roidmi F8 unknown 0x0029 subtype: {}", GB.hexdump(value))
        }
    }

    /**
     * DBFF – secondary voltage rail (ATT handle 0x004D), big-endian uint16 at bytes[6..7] / 100 → V.
     * This is NOT the pack voltage shown by the app (that comes from 0x0029); it reads a lower
     * value (~19 V) and stays clamped near 19.84 V while charging, so it is only logged.
     * Example: `B1 00 00 00 00 00 07 16 1D` → 0x0716 = 1814 → 18.14 V.
     */
    private fun handleVoltage(value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.warn("handleVoltage: GATT error {}", status)
            return
        }
        // Sensor characteristics need at least 8 bytes (indices 0–7).
        if (value.size < 8) {
            LOG.warn("handleVoltage: payload too short ({})", value.size)
            return
        }
        val raw = ((value[6].toInt() and 0xFF) shl 8) or (value[7].toInt() and 0xFF)
        LOG.debug("Roidmi F8 secondary rail voltage: {} V", raw / 100.0f)
    }

    /**
     * D8FF – run / charge state + instantaneous current (ATT handle 0x0041).
     * - value[1] → device state:
     *   `0x00` motor running (cleaning), `0x01` idle / standby, `0x02` charging.
     * - value[5] / 100 → amps (motor draw while running, charge current while charging).
     * - value[6..7] → 16-bit running counter (not a charge flag).
     *
     * Examples: `81 01 00 00 00 00 …` → idle; `81 02 00 00 00 6A …` → charging (1.06 A).
     */
    private fun handleStatus(value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.warn("handleStatus: GATT error {}", status)
            return
        }
        // D8FF status needs at least 6 bytes (indices 0–5).
        if (value.size < 6) {
            LOG.warn("handleStatus: payload too short ({})", value.size)
            return
        }
        val state = value[1].toInt() and 0xFF
        val deviceState = DeviceState.fromCode(state)
        running = deviceState == DeviceState.RUNNING
        charging = deviceState == DeviceState.CHARGING
        val currentAmps = (value[5].toInt() and 0xFF) / 100.0f
        lastChargeCurrentAmps = currentAmps
        LOG.info(
            "Roidmi F8 status: state={} (0x{}), current={} A",
            deviceState ?: "UNKNOWN", String.format("%02X", state), currentAmps
        )
        device.setExtraInfo(EXTRA_CURRENT_AMPS, currentAmps)

        // Persist every reading so the battery charts can chart the current.
        // D8FF reports the motor draw while running and the charge current while docked, both
        // as amps, so a single BatteryCurrentSample covers both phases without needing a sign flip.
        try {
            GBApplication.acquireDB().use { db ->
                val sample = BatteryCurrentSample()
                sample.timestamp = System.currentTimeMillis()
                sample.batteryIndex = 0
                sample.current = currentAmps
                BatteryCurrentSampleProvider(device, db.daoSession).persistSamples(sample, context)
            }
        } catch (e: Exception) {
            LOG.error("handleStatus: failed to persist current sample", e)
        }

        reportBattery()
    }

    /**
     * Emits a single battery event combining the estimated state-of-charge level and the
     * charge state. The level is estimated from the pack voltage, which is only a reliable
     * open-circuit reading while the vacuum is idle: under motor load the voltage sags (e.g.
     * down to ~31.9 V at 100 %), and while docked it is pinned at the charge (CV) limit. So the
     * level is only recomputed at rest; while running the last resting level is held, and a
     * charging pack whose current has tapered to ~0 A is reported as full. Skipped until a
     * voltage reading is available so the UI never shows a placeholder level right after
     * connecting.
     */
    private fun reportBattery() {
        if (lastBatteryVoltage <= 0) return
        val chargingFull = charging && lastChargeCurrentAmps <= FULL_CHARGE_CURRENT_AMPS
        lastBatteryLevel = when {
            chargingFull -> 100
            // Under motor load the pack voltage sags and is not a valid SoC reading: hold the
            // last resting level instead of dropping the percentage while cleaning.
            running && lastBatteryLevel >= 0 -> lastBatteryLevel
            else -> estimateBatteryLevel(lastBatteryVoltage)
        }
        val batteryInfo = GBDeviceEventBatteryInfo()
        batteryInfo.batteryIndex = 0
        batteryInfo.level = lastBatteryLevel
        batteryInfo.state = when {
            chargingFull -> BatteryState.BATTERY_CHARGING_FULL
            charging -> BatteryState.BATTERY_CHARGING
            else -> BatteryState.BATTERY_NORMAL
        }
        batteryInfo.voltage = lastBatteryVoltage
        handleGBDeviceEvent(batteryInfo)
    }

    /**
     * Estimates the state of charge (0–100%) from the pack voltage using a Li-ion discharge
     * curve calibrated against the official app. The pack voltage from 0x0029 matches the value
     * the app displays, so it is mapped directly (per-cell = voltage / cell count).
     */
    private fun estimateBatteryLevel(voltage: Float): Int {
        val perCell = voltage / BATTERY_CELL_COUNT - BATTERY_VOLTAGE_BIAS_PER_CELL
        val curve = BATTERY_SOC_CURVE
        if (perCell <= curve.first().first) return 0
        if (perCell >= curve.last().first) return 100
        for (i in 1 until curve.size) {
            val (v1, p1) = curve[i]
            if (perCell < v1) {
                val (v0, p0) = curve[i - 1]
                return (p0 + (perCell - v0) / (v1 - v0) * (p1 - p0)).roundToInt()
            }
        }
        return 100
    }

    // ── D5FF command response parser ──────────────────────────────────────────

    /**
     * Parses a notification from D5FF (command channel).
     * - 0x51 – firmware build: dotted ASCII with digits joined (e.g. "v_1.0.0.1!" → "1001").
     * - 0x52 – filter used time: big-endian uint16 seconds at bytes[1..2].
     * - 0x53 – firmware major + revision (e.g. "v0.5" → major "5", revision hex "30").
     *
     * The full firmware version shown by the official app combines both: `v_<major>.<build>.<rev>`
     * (e.g. "v0.5" + build "1001" → "v_5.1001.30"), where the revision is the ASCII byte of the
     * digit before the dot ('0' = 0x30) rendered as hex.
     */
    private fun handleCommandResponse(value: ByteArray) {
        if (value.isEmpty()) return
        when (value[0].toInt() and 0xFF) {
            0x51 -> {
                // The firmware build is reported as a dotted ASCII string (e.g. "v_1.0.0.1!"),
                // terminated by '!'. The official app joins the digits into a single build
                // number (e.g. "1.0.0.1" → "1001"), so drop every non-digit character.
                firmwareBuild = String(value, 1, value.size - 1, Charsets.UTF_8)
                    .filter { it.isDigit() }
                    .ifEmpty { null }
                LOG.info("Roidmi F8 firmware build: {}", firmwareBuild)
                updateFirmwareVersion()
            }
            0x52 -> {
                if (value.size >= 3) {
                    val secs = ((value[1].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
                    LOG.info("Roidmi F8 filter used: {} s ({} h)", secs, secs / 3600)
                    device.setExtraInfo("filter_used_seconds", secs)
                }
            }
            0x53 -> {
                // The sensor response (e.g. "v0.5") carries the firmware major and revision:
                // the digit after the dot is the major ("5"); the digit before the dot is the
                // revision, shown as the hex of its ASCII byte ('0' = 0x30 → "30").
                val sensorVer = String(value, 1, value.size - 1, Charsets.UTF_8).trim()
                LOG.info("Roidmi F8 sensor version: {}", sensorVer)
                val digits = sensorVer.removePrefix("v").split(".")
                if (digits.size >= 2) {
                    val revChar = digits[0].firstOrNull()
                    firmwareMajor = digits[1].filter { it.isDigit() }.ifEmpty { null }
                    firmwareRevision = revChar?.let { String.format("%02x", it.code) }
                }
                updateFirmwareVersion()
            }
            else -> LOG.debug(
                "D5FF response 0x{}: {}",
                String.format("%02X", value[0].toInt() and 0xFF), GB.hexdump(value)
            )
        }
    }

    /**
     * Composes and publishes the firmware version once its parts are known, in the same
     * `v_<major>.<build>.<revision>` format used by the official app (e.g. "v_5.1001.30").
     * Missing parts are simply omitted so a partial version is still shown.
     */
    private fun updateFirmwareVersion() {
        val major = firmwareMajor
        val build = firmwareBuild
        val revision = firmwareRevision
        if (major == null && build == null) {
            return
        }
        val version = buildString {
            append("v_")
            append(major ?: "")
            if (build != null) append(".").append(build)
            if (revision != null) append(".").append(revision)
        }
        LOG.info("Roidmi F8 firmware version: {}", version)
        device.setFirmwareVersion(version)
        device.sendDeviceUpdateIntent(context)
    }

    // ── Configuration writes ──────────────────────────────────────────────────

    override fun onSendConfiguration(config: String) {
        val control = charControl
        if (control == null) {
            LOG.warn("onSendConfiguration: control characteristic not available")
            return
        }

        val prefs = GBApplication.getDeviceSpecificSharedPrefs(device.address)

        val cmd: ByteArray = when (config) {
            DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_STANDARD_GEAR -> {
                val gear = prefs.getString(
                    DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_STANDARD_GEAR, "0"
                )?.toIntOrNull()?.coerceIn(0, 2) ?: 0
                gearCommand(gear)
            }
            DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_DUST_REMINDER -> {
                val on = prefs.getBoolean(
                    DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_DUST_REMINDER, true
                )
                if (on) CMD_DUST_REMINDER_ON else CMD_DUST_REMINDER_OFF
            }
            DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_RESET_FILTER -> CMD_RESET_FILTER
            else -> return
        }

        try {
            val builder = performInitialized("sendConfig:$config")
            builder.write(control, *cmd)
            builder.queue()
        } catch (e: IOException) {
            LOG.error("onSendConfiguration: failed to send command", e)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(RoidmiF8Support::class.java)

        /**
         * First fixed key written to the AUTH-INIT characteristic to open the handshake.
         */
        private val MI_KEY1 = byteArrayOf(0x90.toByte(), 0xCA.toByte(), 0x85.toByte(), 0xDE.toByte())

        /**
         * Second fixed key; the final client frame is `RC4(token, MI_KEY2)`.
         */
        private val MI_KEY2 = byteArrayOf(0x92.toByte(), 0xAB.toByte(), 0x54.toByte(), 0xFA.toByte())

        /** MiBeacon product id for roidmi.vacuum.v1 (advertised device id 0x0248). */
        private const val PRODUCT_ID = 0x0248

        // ── Service UUIDs ─────────────────────────────────────────────────────

        /** Vendor FFD0 service – hosts all DxFF telemetry + command characteristics. */
        private val UUID_SERVICE_FFD0: UUID = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb")

        /** Xiaomi MiBeacon service – provides the BLE auth handshake. */
        private val UUID_SERVICE_FE95: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

        /** FE95 AUTH characteristic (UUID 0x0001, ATT handle 0x0012, read/write/notify). */
        private val UUID_CHAR_FE95_AUTH: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")

        /** FE95 AUTH-INIT characteristic (UUID 0x0010, ATT handle 0x001b, write). */
        private val UUID_CHAR_FE95_INIT: UUID = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb")

        /** FE95 VERSION characteristic (UUID 0x0004, ATT handle 0x0017, read/notify). */
        private val UUID_CHAR_FE95_VERSION: UUID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")

        // ── FFD0 characteristic UUIDs (DxFF telemetry + command chars) ────────

        private val UUID_CHAR_D1FF: UUID = UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb")
        /** D2FF – temperature: big-endian uint16 at bytes[6..7] / 100 → °C */
        private val UUID_CHAR_D2FF: UUID = UUID.fromString("0000ffd2-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_D3FF: UUID = UUID.fromString("0000ffd3-0000-1000-8000-00805f9b34fb")
        /** D4FF – active suction gear: byte[2] = gear index (0=80 W, 1=130 W, 2=180 W);
         *  bytes[6..7] little-endian = cumulative cleaning time (minutes). */
        private val UUID_CHAR_D4FF: UUID = UUID.fromString("0000ffd4-0000-1000-8000-00805f9b34fb")
        /** D5FF – command channel (write + notify). Init `55 55`, queries `51/52/53`. */
        private val UUID_CHAR_D5FF: UUID = UUID.fromString("0000ffd5-0000-1000-8000-00805f9b34fb")
        /** D6FF – device MAC address (read-only). */
        private val UUID_CHAR_D6FF: UUID = UUID.fromString("0000ffd6-0000-1000-8000-00805f9b34fb")
        /** D7FF – control / settings write channel (no-response writes). */
        private val UUID_CHAR_D7FF: UUID = UUID.fromString("0000ffd7-0000-1000-8000-00805f9b34fb")
        /** D8FF – run state + current: byte[1]=0x00 running; byte[5]/100 → A */
        private val UUID_CHAR_D8FF: UUID = UUID.fromString("0000ffd8-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_D9FF: UUID = UUID.fromString("0000ffd9-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_DAFF: UUID = UUID.fromString("0000ffda-0000-1000-8000-00805f9b34fb")
        /** DBFF – battery pack voltage: big-endian uint16 at bytes[6..7] / 100 → V */
        private val UUID_CHAR_DBFF: UUID = UUID.fromString("0000ffdb-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_DCFF: UUID = UUID.fromString("0000ffdc-0000-1000-8000-00805f9b34fb")

        // ── D5FF init + query commands ────────────────────────────────────────

        /** Sent to D5FF immediately after setup to wake up the command channel. */
        private val CMD_INIT = byteArrayOf(0x55, 0x55)
        /** Version query written to D5FF after init. */
        private val CMD_VERSION_INFO = byteArrayOf(0x51)

        // ── D7FF configuration command payloads (ATT value only – no header bytes) ─

        /** Builds the standard-gear command `71 <index> 00 00` (index 0=80 W, 1=130 W, 2=180 W). */
        private fun gearCommand(gear: Int): ByteArray = byteArrayOf(0x71, gear.toByte(), 0x00, 0x00)

        /** Dust-full reminder: off / on */
        private val CMD_DUST_REMINDER_OFF = byteArrayOf(0x75, 0x75, 0x00, 0x00)
        private val CMD_DUST_REMINDER_ON = byteArrayOf(0x76, 0x76, 0x00, 0x00)

        /** Reset filter used-time counter */
        private val CMD_RESET_FILTER = byteArrayOf(0x73, 0x73)
        /** Filter usage info query. Response: opcode 0x52 + big-endian uint16 seconds used. */
        private val CMD_FILTER_INFO = byteArrayOf(0x52)
        /** Sensor / brush version query. Response: opcode 0x53 + ASCII version string. */
        private val CMD_SENSOR_INFO = byteArrayOf(0x53)

        // ── Extra-info keys ───────────────────────────────────────────────────

        /** Extra-info key for the last motor / charge current (Float, amps). */
        const val EXTRA_CURRENT_AMPS = "current_amps"
        /** Extra-info key for the last pack temperature (Float, °C). */
        const val EXTRA_TEMPERATURE_CELSIUS = "temperature_celsius"

        /** 0x0029 frame subtype (value[0]): bytes[4..5] carry the pack voltage. */
        private const val SENSOR_FRAME_VOLTAGE = 0x21
        /** 0x0029 frame subtype (value[0]): bytes[4..5] carry the temperature, bytes[6..7] the voltage. */
        private const val SENSOR_FRAME_TEMPERATURE = 0x22

        /**
         * Charge current (A) below which a charging pack is considered full. During the CV phase
         * the charge current tapers towards 0 A as the pack tops off (observed dropping from
         * ~1.9 A down to 0 A on the official app's 100 % point).
         */
        private const val FULL_CHARGE_CURRENT_AMPS = 0.05f
        /** Number of Li-ion cells in series in the pack (8S, ~33.5 V full ≈ 4.19 V/cell). */
        private const val BATTERY_CELL_COUNT = 8
        /**
         * Per-cell correction subtracted from the measured voltage before mapping. The pack
         * voltage from 0x0029 matches the app's battery voltage directly, so no bias is applied.
         */
        private const val BATTERY_VOLTAGE_BIAS_PER_CELL = 0f

        /**
         * Li-ion state-of-charge curve: per-cell voltage → % (ascending), interpolated linearly
         * and clamped to 0/100. Anchored to the official app: a rested full pack sits at ~32.9 V
         * (0x0029), i.e. ~4.11 V/cell = 100 %, so the top of the curve tops out at 4.10 V;
         * 29.08 V (3.635 V/cell) reads 30 % on the app, which fixes the 3.60/3.70 V anchors.
         */
        private val BATTERY_SOC_CURVE = arrayOf(
            3.30f to 0f,
            3.50f to 8f,
            3.60f to 25f,
            3.70f to 40f,
            3.75f to 45f,
            3.80f to 48f,
            3.85f to 58f,
            3.90f to 62f,
            3.95f to 72f,
            4.00f to 82f,
            4.05f to 92f,
            4.10f to 100f,
        )

        /**
         * Delay between consecutive D5FF query writes. The device answers one query at a
         * time and drops queries issued too soon after "55 55"; the official app spaces
         * them ~1.6 s apart, so this leaves comfortable margin.
         */
        private const val D5FF_QUERY_DELAY_MS = 800

        // ── Xiaomi MiBeacon (Mi Kettle-style) RC4 auth ────────────────────────

        private fun parseMacReversed(address: String): ByteArray =
            StringUtils.hexToBytes(address.replace(":", "")).reversedArray()

        /**
         * MiBeacon key-mixing function A over the reversed MAC and product id.
         * Layout: `[mac0, mac2, mac5, pid&0xff, pid&0xff, mac4, mac5, mac1]`.
         */
        private fun mixA(mac: ByteArray, productId: Int): ByteArray = byteArrayOf(
            mac[0], mac[2], mac[5],
            (productId and 0xff).toByte(), (productId and 0xff).toByte(),
            mac[4], mac[5], mac[1],
        )

        /**
         * Standard RC4 stream cipher (no keystream drop), used for every MiBeacon frame.
         * Encryption and decryption are identical.
         */
        private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            val out = ByteArray(data.size)
            var a = 0
            var b = 0
            for (k in data.indices) {
                a = (a + 1) and 0xff
                b = (b + s[a]) and 0xff
                val t = s[a]; s[a] = s[b]; s[b] = t
                out[k] = ((data[k].toInt() and 0xff) xor s[(s[a] + s[b]) and 0xff]).toByte()
            }
            return out
        }
    }
}
