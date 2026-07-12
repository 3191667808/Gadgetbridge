/*  Copyright (C) 2026 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.activities

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivitySignalStrengthBinding
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows the live Bluetooth signal strength (RSSI) of a device, and offers a button to toggle
 * the device's "find device" alert.
 */
@SuppressLint("MissingPermission") // this activity is only reachable for an already paired device
class FindDeviceActivity : AbstractGBActivity() {
    private lateinit var binding: ActivitySignalStrengthBinding
    private lateinit var device: GBDevice

    private var bluetoothGatt: BluetoothGatt? = null
    private var rssiPollingJob: Job? = null
    private var reconnectJob: Job? = null
    private var findingDevice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignalStrengthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(GBDevice.EXTRA_DEVICE, GBDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(GBDevice.EXTRA_DEVICE)
        }!!

        title = getString(R.string.title_activity_signal_strength)
        binding.signalDeviceName.text = device.aliasOrName
        binding.signalValue.text = getString(R.string.signal_strength_unknown)

        binding.findDeviceButton.setOnClickListener {
            toggleFindDevice()
        }
        updateFindDeviceButton()
    }

    override fun onStart() {
        super.onStart()
        connectForRssiUpdates()
    }

    override fun onStop() {
        super.onStop()
        disconnectRssiUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (findingDevice) {
            GBApplication.deviceService(device).onFindDevice(false)
        }
    }

    private fun toggleFindDevice() {
        findingDevice = !findingDevice
        GBApplication.deviceService(device).onFindDevice(findingDevice)
        updateFindDeviceButton()
    }

    private fun updateFindDeviceButton() {
        binding.findDeviceButton.text = if (findingDevice) {
            getString(R.string.find_lost_device_you_found_it)
        } else {
            getString(R.string.controlcenter_find_device)
        }
    }

    private fun connectForRssiUpdates() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            showDisconnected()
            scheduleReconnect { connectForRssiUpdates() }
            return
        }

        val remoteDevice = bluetoothAdapter.getRemoteDevice(device.address)
        bluetoothGatt = remoteDevice.connectGatt(this, false, gattCallback)
    }

    private fun disconnectRssiUpdates() {
        reconnectJob?.cancel()
        reconnectJob = null

        rssiPollingJob?.cancel()
        rssiPollingJob = null

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun scheduleReconnect(attempt: () -> Unit) {
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(RECONNECT_DELAY_MS.milliseconds)
            attempt()
        }
    }

    private fun startRssiPolling(gatt: BluetoothGatt) {
        rssiPollingJob?.cancel()
        rssiPollingJob = lifecycleScope.launch {
            while (isActive) {
                gatt.readRemoteRssi()
                delay(RSSI_POLL_INTERVAL_MS.milliseconds)
            }
        }
    }

    private fun showConnected() {
        binding.signalStatus.visibility = View.GONE
    }

    private fun showDisconnected() {
        binding.signalValue.text = getString(R.string.signal_strength_disconnected)
        binding.signalProgress.progress = 0
        binding.signalStatus.text = getString(R.string.signal_strength_reconnecting)
        binding.signalStatus.visibility = View.VISIBLE
    }

    private fun updateSignalUi(rssi: Int) {
        binding.signalValue.text = getString(R.string.signal_strength_dbm, rssi)
        binding.signalProgress.progress = rssiToPercent(rssi)
        pulse(binding.signalValue)
    }

    private fun pulse(view: View) {
        view.animate().cancel()
        view.alpha = 0.4f
        view.animate().alpha(1f).setDuration(PULSE_DURATION_MS).start()
    }

    private fun rssiToPercent(rssi: Int): Int {
        val clamped = rssi.coerceIn(RSSI_MIN_DBM, RSSI_MAX_DBM)
        return (clamped - RSSI_MIN_DBM) * 100 / (RSSI_MAX_DBM - RSSI_MIN_DBM)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    runOnUiThread { showConnected() }
                    startRssiPolling(gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    rssiPollingJob?.cancel()
                    rssiPollingJob = null
                    runOnUiThread { showDisconnected() }
                    scheduleReconnect { gatt.connect() }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return
            }
            runOnUiThread { updateSignalUi(rssi) }
        }
    }

    companion object {
        private const val RSSI_POLL_INTERVAL_MS = 1500L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val PULSE_DURATION_MS = 350L

        // Typical BLE RSSI range used to translate dBm into a 0-100% signal meter.
        private const val RSSI_MIN_DBM = -100
        private const val RSSI_MAX_DBM = -40
    }
}
