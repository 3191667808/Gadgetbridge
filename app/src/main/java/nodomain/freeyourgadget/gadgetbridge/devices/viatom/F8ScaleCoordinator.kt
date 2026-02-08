package nodomain.freeyourgadget.gadgetbridge.devices.viatom

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import de.greenrobot.dao.AbstractDao
import de.greenrobot.dao.Property
import nodomain.freeyourgadget.gadgetbridge.GBApplication.getDevicePrefs
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator.DeviceKind
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.F8BioImpedanceSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.F8WeightSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.viatom.F8Support
import java.util.Collections
import java.util.regex.Pattern

class F8ScaleCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getAllDeviceDao(session: DaoSession): MutableMap<AbstractDao<*, *>?, Property?> {
        return object : HashMap<AbstractDao<*, *>?, Property?>() {
            init {
                put(session.f8WeightSampleDao, F8WeightSampleDao.Properties.DeviceId)
                put(
                    session.f8BioImpedanceSampleDao,
                    F8BioImpedanceSampleDao.Properties.DeviceId
                )
            }
        }
    }

    override fun getWeightSampleProvider(
        device: GBDevice, session: DaoSession
    ): TimeSampleProvider<out WeightSample?> {
        return F8WeightSampleProvider(device, session)
    }

    override fun getCustomActions(): MutableList<DeviceCardAction?> {
        return Collections.singletonList<DeviceCardAction>(object : DeviceCardAction {
            override fun getIcon(device: GBDevice): Int {
                return R.drawable.ic_badge
            }

            override fun getDescription(device: GBDevice, context: Context): String {
                return context.getString(R.string.weight_scale_show_measurement)
            }

            override fun isVisible(device: GBDevice): Boolean {
                return true
            }

            override fun onClick(device: GBDevice, context: Context) {
                val users = arrayOf("User 1", "User 2")

                val spinner = Spinner(context)
                val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, users)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter

                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        pos: Int,
                        id: Long
                    ) {
                        getDevicePrefs(device).preferences.edit().putInt("active user", pos + 1)
                            .apply()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                AlertDialog.Builder(context)
                    .setTitle("Choose the active user")
                    .setView(spinner)
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
    }

    protected override fun getSupportedDeviceName(): Pattern? {
        return Pattern.compile("F8")
    }

    override fun getBondingStyle(): Int {
        return BONDING_STYLE_NONE
    }

    override fun getManufacturer(): String {
        return "viatom"
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport?> {
        return F8Support::class.java
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_f8
    }

    override fun getDeviceKind(device: GBDevice): DeviceKind {
        return DeviceKind.SCALE
    }

    override fun getDefaultIconResource(): Int {
        return R.drawable.ic_device_miscale
    }

    override fun getDeviceSpecificSettings(device: GBDevice): DeviceSpecificSettings {
        val deviceSpecificSettings = DeviceSpecificSettings()

        val generic = deviceSpecificSettings.addRootScreen(DeviceSpecificSettingsScreen.GENERIC)
        generic.add(R.xml.devicesettings_scale_user1)
        generic.add(R.xml.devicesettings_scale_user2)

        return deviceSpecificSettings
    }

    override fun supportsWeightMeasurement(device: GBDevice): Boolean {
        return true
    }

    override fun supportsCharts(device: GBDevice): Boolean {
        return true
    }
}
