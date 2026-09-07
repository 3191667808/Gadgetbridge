/*  Copyright (C) 2026 oddballza

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
package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import org.slf4j.LoggerFactory

/**
 * Most recent weight measurement taken on or before the selected day. Weight is not
 * measured every day, so the last known value is shown rather than a value for the day
 * itself. The gauge is coloured by WHO BMI class, computed from the profile height.
 */
object WeightWidget : GaugeWidget<WeightWidget.Data>() {
    private val LOG = LoggerFactory.getLogger(WeightWidget::class.java)

    override val id = "weight"
    override val label = R.string.menuitem_weight
    override val icon = R.drawable.ic_weight
    override val chartTab = "weight"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsWeightMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val latest: WeightSample? = try {
            scope.db { db ->
                var newest: WeightSample? = null
                for (dev in scope.devices) {
                    val sample = dev.deviceCoordinator.getWeightSampleProvider(dev, db.daoSession)
                        ?.getLatestSample(scope.query.timeTo * 1000L)
                    if (sample != null && (newest == null || sample.timestamp > newest.timestamp)) {
                        newest = sample
                    }
                }
                newest
            }
        } catch (e: Exception) {
            LOG.error("Could not get weight samples", e)
            null
        }

        return Data(latest?.weightKg?.toDouble() ?: 0.0, ActivityUser().heightCm)
    }

    override fun draw(context: Context, gaugeValue: TextView, gaugeBar: ImageView, data: Data) {
        if (data.weightKg <= 0) {
            gaugeValue.text = context.getString(R.string.stats_empty_value)
            drawSimpleGauge(gaugeBar, Color.GRAY, -1f)
            return
        }

        gaugeValue.text = WeightUnit.formatWeight(context, data.weightKg, GBApplication.getPrefs().weightUnit)

        if (data.heightCm <= 0) {
            // no height in the profile, so no BMI to colour by
            drawSimpleGauge(gaugeBar, Color.rgb(76, 175, 80), -1f)
            return
        }

        // WHO BMI classification: colour and gauge position
        val heightM = data.heightCm / 100.0
        val bmi = data.weightKg / (heightM * heightM)
        val color: Int
        val fraction: Float
        when {
            bmi < 18.5 -> {
                color = Color.rgb(255, 152, 0) // orange - underweight
                fraction = 0.15f
            }

            bmi < 25 -> {
                color = Color.rgb(76, 175, 80) // green - normal
                fraction = 0.40f
            }

            bmi < 30 -> {
                color = Color.rgb(255, 152, 0) // orange - overweight
                fraction = 0.65f
            }

            else -> {
                color = Color.rgb(244, 67, 54) // red - obese
                fraction = 0.88f
            }
        }
        drawSimpleGauge(gaugeBar, color, fraction)
    }

    data class Data(val weightKg: Double, val heightCm: Int)
}
