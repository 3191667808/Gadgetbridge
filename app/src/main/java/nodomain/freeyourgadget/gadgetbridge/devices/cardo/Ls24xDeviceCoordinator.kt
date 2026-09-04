package nodomain.freeyourgadget.gadgetbridge.devices.cardo

import nodomain.freeyourgadget.gadgetbridge.GBException
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.Device
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.CardoDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmRegion
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils.CardoField
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap
import java.util.regex.Pattern

class Ls24xDeviceCoordinator : AbstractBLEDeviceCoordinator() {
    @JvmField
    val deviceStatus: CardoMap<CardoField?, Any?> = CardoMap<CardoField?, Any?>()

    @Throws(GBException::class)
    override fun deleteDevice(gbDevice: GBDevice, device: Device, session: DaoSession) {
    }

    override fun getManufacturer(): String {
        return "Cardo"
    }

    protected override fun getSupportedDeviceName(): Pattern? {
        return Pattern.compile("UCS LS2")
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport?> {
        return CardoDeviceSupport::class.java
    }


    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec {
        return deviceSettings {
            xmlScreen(
                DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
                R.xml.devicesettings_headphones,
                connectedOnly = false,
            )
            screen(
                key = "pref_cardo_screen_fm_radio",
                title = R.string.cardo_card_fm_radio,
                icon = R.drawable.ic_music_note
            ) {
                switchSetting(
                    key = "pref_cardo_toggle_fm",
                    title = R.string.cardo_control_enable_radio,
                    icon = R.drawable.ic_speaker,
                    connectedOnly = true,
                    defaultValue = false,
                )
                //TODO devicestatus access below crashes if disconnected
                seekbar( //FIXME need to divide by 100
                    key = "pref_cardo_fm_tuning",
                    title = R.string.cardo_control_fm_frequency,
                    icon = R.drawable.ic_speaker,
                    min = (deviceStatus.getValueByName("fmRegion") as CardoFmRegion).getMinFreq(), //FIXME min seems always 100
                    max = (deviceStatus.getValueByName("fmRegion") as CardoFmRegion).getMaxFreq(),
                    defaultValue = 9400,
                    showValue = true,
                    dependency = "pref_cardo_toggle_fm"
                )
                action (
                    key = "fake_seek_up",
                    title = R.string.cardo_control_fm_seek_scan_up,
                    icon = R.drawable.ic_arrow_upward,
                    dependency = "pref_cardo_toggle_fm",
                    onClick = { handler ->
                        handler.notifyPreferenceChanged("fake_seek_up")
                        true
                    },
                )
                action (
                    key = "fake_seek_down",
                    title = R.string.cardo_control_fm_seek_scan_down,
                    icon = R.drawable.ic_arrow_downward,
                    dependency = "pref_cardo_toggle_fm",
                    onClick = { handler ->
                        handler.notifyPreferenceChanged("fake_seek_down")
                        true
                    },
                )
            }
            screen(
                key = "pref_cardo_screen_general",
                title = R.string.cardo_card_voice_prompts,
            ) {
                switchSetting(
                    key = "pref_cardo_toggle_voice_prompts",
                    title = R.string.cardo_control_enable_voice_prompts,
                    icon = R.drawable.ic_speaker,//TODO
                    connectedOnly = true,
                    defaultValue = false,
                )
                seekbar(
                    key = "pref_cardo_standby_volume",
                    title = R.string.cardo_control_volume,
                    icon = R.drawable.ic_speaker,
                    min = 0,
                    max = 15,
                    defaultValue = 3,
                    showValue = true,
                )
            }
        }
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_ls2_4x
    }

    override fun getDefaultIconResource(): Int {
        return R.drawable.ic_device_supercars
    }

    override fun isExperimental(): Boolean {
        return true
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.HEAD_MOUNTED
    }

    companion object {
        const val ACTION_DEVICE_STATUS_UPDATED: String =
            "nodomain.freeyourgadget.gadgetbridge.cardo.DEVICE_STATUS_UPDATED"
    }
}
