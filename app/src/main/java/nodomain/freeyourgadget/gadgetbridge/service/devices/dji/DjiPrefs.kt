package nodomain.freeyourgadget.gadgetbridge.service.devices.dji

import android.content.SharedPreferences
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs
import kotlin.random.Random
import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.BuildConfig

class DjiPrefs(preferences: SharedPreferences, gbDevice: GBDevice) : DevicePrefs(preferences, gbDevice) {
    companion object {
        const val PREF_PAIRING_ID = "dji_pairing_id"
        const val PREF_PAIRING_PIN = "dji_pairing_pin"

        const val PREF_WRITE_VIDEO_STREAM = "pref_dji_write_video_stream"
    }

    fun getOrCreatePairingId(): String {
        return getOrCreateRandomDecimal(PREF_PAIRING_ID, 15)
    }

    fun getOrCreatePairingPin(): String {
        return getOrCreateRandomDecimal(PREF_PAIRING_PIN, 4)
    }

    fun dumpVideoStream(): Boolean {
        return getBoolean(PREF_WRITE_VIDEO_STREAM, BuildConfig.DEBUG)
    }

    private fun getOrCreateRandomDecimal(key: String, digits: Int): String {
        val existingValue = getString(key, "")
        if (!existingValue.isEmpty() && existingValue.length == digits) {
            return existingValue
        }
        val newValue = buildString(digits) { repeat(digits) { append(Random.nextInt(10)) } }
        preferences.edit {
            putString(key, newValue)
        }
        return newValue
    }
}
