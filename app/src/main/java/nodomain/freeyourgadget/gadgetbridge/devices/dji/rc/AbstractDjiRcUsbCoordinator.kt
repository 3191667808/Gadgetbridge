package nodomain.freeyourgadget.gadgetbridge.devices.dji.rc

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractUsbDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.dji.DjiConst
import nodomain.freeyourgadget.gadgetbridge.devices.dji.DjiDumlActivityTrackProvider
import nodomain.freeyourgadget.gadgetbridge.devices.dji.DjiVideoActivity
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.DjiPrefs
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb.DjiUsbSupport

abstract class AbstractDjiRcUsbCoordinator : AbstractUsbDeviceCoordinator() {
    override fun isExperimental(): Boolean {
        return true
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> {
        return DjiUsbSupport::class.java
    }

    override fun supports(candidate: GBDeviceCandidate): Boolean {
        return candidate.accessory?.manufacturer == "DJI" &&
                candidate.accessory?.model == "com.dji.logiclink"
    }

    override fun getManufacturer(): String {
        return "DJI"
    }

    override fun getDefaultIconResource(): Int {
        return R.drawable.ic_device_drone
    }

    override fun getBatteryCount(device: GBDevice): Int {
        return 2
    }

    override fun getBatteryConfig(device: GBDevice): Array<out BatteryConfig> {
        return arrayOf(
            // Remote
            BatteryConfig(
                DjiConst.BATTERY_IDX_RC,
                R.drawable.ic_videogame,
                R.string.remote_control,
                20,
                100,
            ),

            // Drone (each battery has multiple cells, but we only consider the aggregate)
            BatteryConfig(
                DjiConst.BATTERY_IDX_DRONE,
                R.drawable.ic_drone,
                R.string.drone,
                30,
                100,
            ),
        )
    }

    override fun supportsRecordedActivities(device: GBDevice): Boolean {
        return true
    }

    override fun getActivityTrackProvider(device: GBDevice, context: Context): ActivityTrackProvider {
        return DjiDumlActivityTrackProvider()
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.DRONE
    }

    override fun getCustomActions(): List<DeviceCardAction> {
        return DEVICE_CARD_ACTIONS
    }

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        screen(
            key = DeviceSpecificSettingsScreen.DEVELOPER.key,
            title = R.string.pref_title_developer_settings,
            icon = R.drawable.ic_developer_mode,
        ) {
            switchSetting(
                key = DjiPrefs.PREF_WRITE_VIDEO_STREAM,
                title = R.string.dji_developer_write_stream,
                icon = R.drawable.ic_video_file,
                defaultValue = BuildConfig.DEBUG,
            )
        }
    }

    companion object {
        private val DEVICE_CARD_ACTIONS = listOf(
            DeviceCardAction.forActivity(
                R.drawable.ic_camera_remote,
                R.string.activity_live_video_stream,
                DjiVideoActivity::class.java
            )
        )
    }
}
