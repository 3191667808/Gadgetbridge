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
