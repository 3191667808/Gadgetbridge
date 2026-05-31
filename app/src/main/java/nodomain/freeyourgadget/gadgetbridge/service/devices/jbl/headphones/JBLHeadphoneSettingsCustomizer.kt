/*  Copyright (C) 2025 hemisputnik (https://512b.dev/)

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones

import android.os.Parcel
import android.os.Parcelable
import androidx.preference.Preference
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler
import nodomain.freeyourgadget.gadgetbridge.util.Prefs

class JBLHeadphoneSettingsCustomizer : DeviceSpecificSettingsCustomizer {
    override fun customizeSettings(handler: DeviceSpecificSettingsHandler, prefs: Prefs, rootKey: String?) {
        handler.addPreferenceHandlerFor(SettingKeys.PREF_JBL_VOICEAWARE)
    }

    override fun onPreferenceChange(preference: Preference?, handler: DeviceSpecificSettingsHandler?) = Unit
    override fun getPreferenceKeysWithSummary() = emptySet<String>()
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<JBLHeadphoneSettingsCustomizer> {
            override fun createFromParcel(source: Parcel) = JBLHeadphoneSettingsCustomizer()
            override fun newArray(size: Int) = arrayOfNulls<JBLHeadphoneSettingsCustomizer>(size)
        }
    }
}
