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
package nodomain.freeyourgadget.gadgetbridge.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.sensorcontext.PhoneSensorContext
import org.slf4j.LoggerFactory
import kotlin.math.sqrt

class PhoneSensorCollectorService : Service(), SensorEventListener {
    private val logger = LoggerFactory.getLogger(PhoneSensorCollectorService::class.java)
    private var sensorManager: SensorManager? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        registerEnabledSensors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerEnabledSensors()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val tsMs = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> PhoneSensorContext.addPressure(tsMs, event.values[0].toDouble())
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                val magnitude = sqrt(x * x + y * y + z * z) - STANDARD_GRAVITY_MS2
                PhoneSensorContext.addAccelMagnitude(tsMs, magnitude)
            }
            Sensor.TYPE_LIGHT -> PhoneSensorContext.addLightLux(tsMs, event.values[0].toDouble())
            Sensor.TYPE_AMBIENT_TEMPERATURE -> PhoneSensorContext.addAmbientTemp(tsMs, event.values[0].toDouble())
            Sensor.TYPE_STEP_COUNTER -> PhoneSensorContext.addStepCounter(tsMs, event.values[0].toLong())
        }
    }

    private fun registerEnabledSensors() {
        val manager = sensorManager ?: return
        manager.unregisterListener(this)

        if (!pref(PREF_PHONE_SENSOR_COLLECTOR_ENABLED)) {
            return
        }

        registerSensor(manager, Sensor.TYPE_PRESSURE, PREF_PHONE_SENSOR_PRESSURE_ENABLED)
        registerSensor(manager, Sensor.TYPE_ACCELEROMETER, PREF_PHONE_SENSOR_ACCEL_ENABLED)
        registerSensor(manager, Sensor.TYPE_LIGHT, PREF_PHONE_SENSOR_LIGHT_ENABLED)
        registerSensor(manager, Sensor.TYPE_AMBIENT_TEMPERATURE, PREF_PHONE_SENSOR_AMBIENT_TEMP_ENABLED)
        registerSensor(manager, Sensor.TYPE_STEP_COUNTER, PREF_PHONE_SENSOR_STEP_COUNTER_ENABLED)
    }

    private fun registerSensor(manager: SensorManager, sensorType: Int, prefKey: String) {
        if (!pref(prefKey)) {
            return
        }
        val sensor = manager.getDefaultSensor(sensorType)
        if (sensor == null) {
            logger.info("Phone sensor type {} is not available", sensorType)
            return
        }
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun pref(key: String): Boolean = GBApplication.getPrefs().getBoolean(key, false)

    companion object {
        const val PREF_PHONE_SENSOR_COLLECTOR_ENABLED = "pref_phone_sensor_collector_enabled"
        const val PREF_PHONE_SENSOR_PRESSURE_ENABLED = "pref_phone_sensor_pressure_enabled"
        const val PREF_PHONE_SENSOR_ACCEL_ENABLED = "pref_phone_sensor_accel_enabled"
        const val PREF_PHONE_SENSOR_LIGHT_ENABLED = "pref_phone_sensor_light_enabled"
        const val PREF_PHONE_SENSOR_AMBIENT_TEMP_ENABLED = "pref_phone_sensor_ambient_temp_enabled"
        const val PREF_PHONE_SENSOR_STEP_COUNTER_ENABLED = "pref_phone_sensor_step_counter_enabled"
        private const val STANDARD_GRAVITY_MS2 = 9.81
    }
}
