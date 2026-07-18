/*  Copyright (C) 2026 d3vv3

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
package nodomain.freeyourgadget.gadgetbridge.devices.withingsscanwatch

import android.content.Context
import androidx.fragment.app.Fragment
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DefaultChartsProvider
import nodomain.freeyourgadget.gadgetbridge.activities.charts.EcgCollectionFragment
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

class WithingsScanwatchChartsProvider : DefaultChartsProvider() {
    override fun getSupportedCharts(device: GBDevice): List<String> {
        val supportedCharts = super.getSupportedCharts(device).toMutableList()
        if (!supportedCharts.contains("ecg")) {
            supportedCharts.add("ecg")
        }
        return supportedCharts
    }

    override fun getChartLabel(context: Context, device: GBDevice, chartName: String): String {
        return when (chartName) {
            "ecg" -> context.getString(R.string.withings_scanwatch_screen_ecg)
            else -> super.getChartLabel(context, device, chartName)
        }
    }

    override fun getChartFragment(device: GBDevice, chartName: String, allowSwipe: Boolean): Fragment {
        return when (chartName) {
            "ecg" -> EcgCollectionFragment.newInstance(allowSwipe)
            else -> super.getChartFragment(device, chartName, allowSwipe)
        }
    }
}
