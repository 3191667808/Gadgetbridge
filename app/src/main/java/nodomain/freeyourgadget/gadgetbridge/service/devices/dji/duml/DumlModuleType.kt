package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml

/**
 * The 5-bit module-type half of a [DumlAddress] - which kind of module a
 * packet is from/to, e.g. the flight controller, a camera, or the phone
 * app itself.
 */
sealed class DumlModuleType(val value: Int) {
    object Invalid : DumlModuleType(0) // "Invalid/Any" - also seems to be used as a broadcast-to-all address
    object Camera : DumlModuleType(1)
    object App : DumlModuleType(2)
    object FlightController : DumlModuleType(3)
    object Gimbal : DumlModuleType(4)
    object CenterBoard : DumlModuleType(5)
    object RemoteControl : DumlModuleType(6)
    object Wifi : DumlModuleType(7) // "Wi-Fi air side" - see also WifiGround (27)
    object DM36xTranscoderAir : DumlModuleType(8)
    object HdLinkAir : DumlModuleType(9)
    object PC : DumlModuleType(10)
    object Battery : DumlModuleType(11)
    object ESC : DumlModuleType(12)
    object DM36xTranscoderGround : DumlModuleType(13)
    object HdLinkGround : DumlModuleType(14)
    object UsbCtrlAir : DumlModuleType(15) // "Serial-to-parallel (USB ctrl.) air side"
    object UsbCtrlGround : DumlModuleType(16)
    object Monocular : DumlModuleType(17)
    object Binocular : DumlModuleType(18)
    object HdFpgaAir : DumlModuleType(19) // "HD transmission FPGA air side"
    object HdFpgaGround : DumlModuleType(20)
    object Simulator : DumlModuleType(21)
    object BaseStation : DumlModuleType(22)
    object AirborneComputingPlatform : DumlModuleType(23)
    object RcBattery : DumlModuleType(24)
    object Imu : DumlModuleType(25)
    object GpsRtk : DumlModuleType(26)
    object WifiGround : DumlModuleType(27)
    object SigCvt : DumlModuleType(28)
    object Pmu : DumlModuleType(29)
    object Unidentified : DumlModuleType(30)
    object Last : DumlModuleType(31) // sentinel/broadcast address? seen in the connect-handshake "Version Inquiry"

    /** Any value not named above - kept verbatim. */
    data class Raw(val rawValue: Int) : DumlModuleType(rawValue)

    override fun toString(): String = when (this) {
        is Raw -> "0x%02x".format(rawValue)
        else -> this::class.simpleName ?: "0x%02x".format(value)
    }

    companion object {
        fun fromValue(v: Int): DumlModuleType = when (v) {
            0 -> Invalid
            1 -> Camera
            2 -> App
            3 -> FlightController
            4 -> Gimbal
            5 -> CenterBoard
            6 -> RemoteControl
            7 -> Wifi
            8 -> DM36xTranscoderAir
            9 -> HdLinkAir
            10 -> PC
            11 -> Battery
            12 -> ESC
            13 -> DM36xTranscoderGround
            14 -> HdLinkGround
            15 -> UsbCtrlAir
            16 -> UsbCtrlGround
            17 -> Monocular
            18 -> Binocular
            19 -> HdFpgaAir
            20 -> HdFpgaGround
            21 -> Simulator
            22 -> BaseStation
            23 -> AirborneComputingPlatform
            24 -> RcBattery
            25 -> Imu
            26 -> GpsRtk
            27 -> WifiGround
            28 -> SigCvt
            29 -> Pmu
            30 -> Unidentified
            31 -> Last
            else -> Raw(v)
        }
    }
}
