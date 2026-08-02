package nodomain.freeyourgadget.gadgetbridge.service.devices.bm6

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventDisplayMessage
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.UUID

class Bm6Support : AbstractBTLESingleDeviceSupport(LOG) {
    private val decryptCipher: Cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(ByteArray(16)))
    }
    private val encryptCipher: Cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(ByteArray(16)))
    }

    private val deviceInfoProfile: DeviceInfoProfile<Bm6Support>
    private val startTestBuffer = StringBuilder()
    private var waitingForStartTest = false
    private var initialSetupRequested = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    init {
        val mListener = IntentListener { intent: Intent? ->
            intent?.action?.let { action ->
                when (action) {
                    DeviceInfoProfile.ACTION_DEVICE_INFO -> {
                        @Suppress("DEPRECATION")
                        val deviceInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO, DeviceInfo::class.java)
                        } else {
                            intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO)
                        }
                        LOG.debug("Device info: {}", deviceInfo)

                        val events = DeviceInfoProfile.toDeviceEvents(deviceInfo)
                        for (event in events) {
                            handleGBDeviceEvent(event)
                        }
                    }
                }
            }
        }

        addSupportedService(UUID_SERVICE_BM6)

        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION)
        deviceInfoProfile = DeviceInfoProfile<Bm6Support>(this)
        deviceInfoProfile.addListener(mListener)
        addSupportedProfile(deviceInfoProfile)
    }

    override fun useAutoConnect(): Boolean {
        return true
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        builder.setDeviceState(GBDevice.State.INITIALIZING)
        deviceInfoProfile.requestDeviceInfo(builder)
        builder.write(UUID_CHARACTERISTIC_BM6_WRITE, *encryptCipher.doFinal(COMMAND_REQUEST))
        builder.notify(UUID_CHARACTERISTIC_BM6_NOTIFY, true)
        builder.setDeviceState(GBDevice.State.INITIALIZED)
        refreshHandler.postDelayed({ requestInitialSetup() }, 700)
        return builder
    }

    override fun dispose() {
        cancelRealtimeRefresh()
        super.dispose()
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        if (UUID_CHARACTERISTIC_BM6_NOTIFY == characteristic.uuid) {
            val decrypted = decryptCipher.doFinal(value)
            val payloadHex = payloadToHex(decrypted)

            if (waitingForStartTest && !payloadHex.startsWith("D15507")) {
                startTestBuffer.append(payloadHex)
                parseStartTestIfComplete()
                return true
            }

            when {
                payloadHex.startsWith("D15503") -> {
                    if (payloadHex.substring(6, 8).equals("FF", ignoreCase = true)) {
                        LOG.debug("BM6 start test returned no data: {}", payloadHex)
                        waitingForStartTest = false
                        startTestBuffer.setLength(0)
                    } else {
                        waitingForStartTest = true
                        startTestBuffer.setLength(0)
                        startTestBuffer.append(payloadHex)
                        parseStartTestIfComplete()
                    }
                    return true
                }
                payloadHex.startsWith("D1550401") -> {
                    LOG.debug("BM6 charge system data part 1: {}", payloadHex)
                    return true
                }
                payloadHex.startsWith("D1550402") -> {
                    LOG.debug("BM6 charge system data part 2: {}", payloadHex)
                    return true
                }
                payloadHex.startsWith("D1550403") -> {
                    handleChargeTestPayload(decrypted)
                    return true
                }
                payloadHex.startsWith("D15507FF") -> {
                    LOG.debug("BM6 realtime report ack: {}", payloadHex)
                    return true
                }
                payloadHex.startsWith("D15507") -> {
                    val (voltage, level, temperature) = parse(decrypted) ?: run {
                        return true
                    }
                    LOG.debug("Voltage: {}V, Level: {}%, Temperature: {}°C", voltage, level, temperature)
                    val batteryEvent = GBDeviceEventBatteryInfo()
                    batteryEvent.voltage = voltage
                    batteryEvent.level = level
                    evaluateGBDeviceEvent(batteryEvent)

                    val sample = GenericTemperatureSample()
                    sample.timestamp = System.currentTimeMillis()
                    sample.temperature = temperature.toFloat()
                    sample.temperatureLocation = GenericTemperatureSample.LOCATION_UNKNOWN
                    sample.temperatureType = GenericTemperatureSample.TYPE_UNKNOWN

                    try {
                        GBApplication.acquireDB().use { handler ->
                            val session = handler.getDaoSession()
                            val sampleProvider = GenericTemperatureSampleProvider(device, session)

                            sampleProvider.persistSamples(sample, context)
                        }
                    } catch (e: Exception) {
                        GB.toast(context, "Error saving temperature samples", Toast.LENGTH_SHORT, GB.ERROR, e)
                    }

                    scheduleRealtimeRefresh()
                    return true
                }
                else -> {
                    LOG.debug("BM6 unsupported payload: {}", payloadHex)
                    return true
                }
            }
        }

        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    private fun payloadToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun formatByteHex(value: Byte): String {
        return String.format(Locale.US, "%02X", value.toInt() and 0xFF)
    }

    private fun requestInitialSetup() {
        if (initialSetupRequested) {
            return
        }
        initialSetupRequested = true

        val devicePrefs = GBApplication.getDevicePrefs(device)
        if (devicePrefs.getBoolean(Bm6Constants.PREF_PROFILE_CONFIGURED, false)) {
            return
        }

        try {
            val builder = performInitialized("Configure BM6 initial setup")
            val batteryType = devicePrefs.getString(Bm6Constants.PREF_BATTERY_TYPE, Bm6Constants.DEFAULT_BATTERY_TYPE_VALUE)
                ?: Bm6Constants.DEFAULT_BATTERY_TYPE_VALUE
            val batteryTypeBytes = buildBatteryTypeConfigurationBytes(batteryType)
            if (batteryTypeBytes != null) {
                builder.write(UUID_CHARACTERISTIC_BM6_WRITE, *encryptCipher.doFinal(batteryTypeBytes))
            }
            builder.write(UUID_CHARACTERISTIC_BM6_WRITE, *encryptCipher.doFinal(COMMAND_START_TEST_REQUEST))
            builder.write(UUID_CHARACTERISTIC_BM6_WRITE, *encryptCipher.doFinal(COMMAND_CHARGE_TEST_REQUEST))
            builder.queue()
            devicePrefs.getPreferences().edit().putBoolean(Bm6Constants.PREF_PROFILE_CONFIGURED, true).apply()
        } catch (e: Exception) {
            initialSetupRequested = false
            LOG.warn("Unable to configure BM6 initial setup", e)
        }
    }

    private fun buildBatteryTypeConfigurationBytes(batteryType: String): ByteArray? {
        val typeCode = when (batteryType) {
            "agm" -> 2
            "gel" -> 3
            "lithium" -> 5
            else -> 1
        }
        return byteArrayOf(0xD1.toByte(), 0x55.toByte(), 0x08.toByte(), typeCode.toByte())
    }

    private fun scheduleRealtimeRefresh(delayMs: Long = REALTIME_REFRESH_INTERVAL_MS) {
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isConnected) {
                return@Runnable
            }
            try {
                val builder = performInitialized("Request BM6 realtime data")
                builder.write(UUID_CHARACTERISTIC_BM6_WRITE, *encryptCipher.doFinal(COMMAND_REQUEST))
                builder.queue()
            } catch (e: Exception) {
                LOG.warn("Unable to request BM6 realtime data", e)
            }
        }
        refreshRunnable = runnable
        refreshHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelRealtimeRefresh() {
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        refreshRunnable = null
    }

    private fun parseStartTestIfComplete() {
        val completePayload = startTestBuffer.toString()
        val startIndex = completePayload.indexOf("FFFFFE")
        val endIndex = completePayload.indexOf("FFFEFE")
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            return
        }

        val payload = completePayload.substring(0, endIndex)
        waitingForStartTest = false
        startTestBuffer.setLength(0)

        val startTest = parseStartTestPayload(payload) ?: return
        handleStartTestPayload(startTest)
    }

    private fun parseStartTestPayload(payloadHex: String): StartTest? {
        if (payloadHex.length < 20) {
            LOG.warn("BM6 start test payload too short: {}", payloadHex)
            return null
        }

        return try {
            val status = payloadHex.substring(6, 8).toInt(16)
            val voltage = payloadHex.substring(8, 12).toInt(16) / 100.0f
            val duration = payloadHex.substring(12, 16).toInt(16)
            val offsetMinutes = payloadHex.substring(16, 20).toLong(16)
            val testTimestamp = System.currentTimeMillis() - (offsetMinutes * 120_000)
            val chartData = if (payloadHex.contains("FFFFFE") && payloadHex.contains("FFFEFE")) {
                val chartPayload = payloadHex.substring(payloadHex.indexOf("FFFFFE") + 6, payloadHex.indexOf("FFFEFE"))
                parseChartData(chartPayload)
            } else {
                emptyList()
            }
            StartTest(status, voltage, duration, testTimestamp, chartData)
        } catch (e: Exception) {
            LOG.warn("Failed to parse BM6 start test payload", e)
            null
        }
    }

    private fun parseChartData(chartPayload: String): List<Float> {
        val values = mutableListOf<Float>()
        var index = 0
        while (index + 3 <= chartPayload.length) {
            val value = chartPayload.substring(index, index + 3).toInt(16) / 100.0f
            if (value != 0.0f) {
                values.add(value)
            }
            index += 3
        }
        return values
    }

    private fun handleStartTestPayload(startTest: StartTest) {
        LOG.info("BM6 start test status={} voltage={}V duration={}s points={}", startTest.status, startTest.voltage, startTest.duration, startTest.chartData)
        val message = String.format(
            Locale.US,
            "BM6 啟動測試: 狀態=%s, 電壓=%.2fV, 持續=%ds, 取樣數=%d",
            formatTestStatus(startTest.status),
            startTest.voltage,
            startTest.duration,
            startTest.chartData.size
        )
        handleGBDeviceEvent(GBDeviceEventDisplayMessage(message, Toast.LENGTH_LONG, GB.INFO))
    }

    private fun handleChargeTestPayload(decrypted: ByteArray) {
        if (decrypted.size < 11) {
            LOG.warn("BM6 charge test response too short: {} bytes", decrypted.size)
            return
        }
        if (decrypted[0] != 0xd1.toByte() || decrypted[1] != 0x55.toByte() || decrypted[2] != 0x04.toByte() || decrypted[3] != 0x03.toByte()) {
            LOG.warn("BM6 charge test response has unknown header: {}", payloadToHex(decrypted))
            return
        }

        val status = decrypted[4].toInt() and 0xFF
        val noLoadRaw = ((decrypted[5].toInt() and 0xFF) shl 8) or (decrypted[6].toInt() and 0xFF)
        val loadRaw = ((decrypted[7].toInt() and 0xFF) shl 8) or (decrypted[8].toInt() and 0xFF)
        val rippleRaw = ((decrypted[9].toInt() and 0xFF) shl 8) or (decrypted[10].toInt() and 0xFF)
        val noLoadVoltage = noLoadRaw / 100.0f
        val loadVoltage = loadRaw / 100.0f

        LOG.info("BM6 charge test status={} no-load={}V load={}V ripple={}mV", status, noLoadVoltage, loadVoltage, rippleRaw)
        val message = String.format(
            Locale.US,
            "BM6 充電測試: 狀態=%s, 空載=%.2fV, 充載=%.2fV, 漣波=%dmV",
            formatTestStatus(status),
            noLoadVoltage,
            loadVoltage,
            rippleRaw
        )
        handleGBDeviceEvent(GBDeviceEventDisplayMessage(message, Toast.LENGTH_LONG, GB.INFO))
    }

    private fun formatTestStatus(status: Int): String {
        return when (status) {
            0 -> "成功"
            1 -> "失敗"
            2 -> "進行中"
            else -> "狀態 $status"
        }
    }

    private data class StartTest(
        val status: Int,
        val voltage: Float,
        val duration: Int,
        val testTimestamp: Long,
        val chartData: List<Float>
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(Bm6Support::class.java)

        private val UUID_SERVICE_BM6: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val UUID_CHARACTERISTIC_BM6_WRITE: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
        private val UUID_CHARACTERISTIC_BM6_NOTIFY: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        private const val REALTIME_REFRESH_INTERVAL_MS = 10_000L

        private val KEY = byteArrayOf(108, 101, 97, 103, 101, 110, 100, 255.toByte(), 254.toByte(), 48, 49, 48, 48, 48, 48, 57)

        // d1550700000000000000000000000000
        private val COMMAND_REQUEST = byteArrayOf(
            0xd1.toByte(), 0x55, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        private val COMMAND_START_TEST_REQUEST = byteArrayOf(0xd1.toByte(), 0x55, 0x03)
        private val COMMAND_CHARGE_TEST_REQUEST = byteArrayOf(0xd1.toByte(), 0x55, 0x04, 0x03)

        internal fun parse(decrypted: ByteArray): Triple<Float, Int, Int>? {
            if (decrypted.size < 9) {
                LOG.warn("Decrypted data too short: {} bytes", decrypted.size)
                return null
            }
            if (decrypted[0] != 0xd1.toByte() || decrypted[1] != 0x55.toByte() || decrypted[2] != 0x07.toByte()) {
                LOG.warn(
                    "Unknown header: 0x{} 0x{} 0x{}",
                    formatByteHex(decrypted[0]),
                    formatByteHex(decrypted[1]),
                    formatByteHex(decrypted[2])
                )
                return null
            }
            val tempNegative = decrypted[3].toInt() and 0xFF == 0x01
            val tempValue = decrypted[4].toInt() and 0xFF
            val temperature = if (tempNegative) -tempValue else tempValue
            val level = decrypted[6].toInt() and 0xFF
            val voltageRaw = ((decrypted[7].toInt() and 0x0F) shl 8) or (decrypted[8].toInt() and 0xFF)
            val voltage = voltageRaw / 100.0f
            return Triple(voltage, level, temperature)
        }
    }
}
