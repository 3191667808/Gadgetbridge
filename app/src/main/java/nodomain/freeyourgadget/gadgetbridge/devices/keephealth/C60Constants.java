package nodomain.freeyourgadget.gadgetbridge.devices.keephealth;

import java.util.UUID;

public class C60Constants {
    public static final UUID SERVICE = UUID.fromString("000ff00-0000-1000-8000-00805f9b34fb");
    public static final UUID CHARACTERISTIC_WRITE = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    public static final UUID CHARACTERISTIC_READ = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    public static final int CHECKSUM_CODE = 86;


    public static final UUID BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID CFG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID OTA_CHARACTERISTIC_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b12");
    public static final UUID OTA_SERVICE_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912");
    public static final UUID VERSION_CHARACTERISTIC_UUID = UUID.fromString("0000ffd4-0000-1000-8000-00805f9b34fb");
    public static final UUID VERSION_SERVICE_UUID = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212");


    public static final UUID READ_ECG = UUID.fromString("0000ef01-0000-1000-8000-00805f9b34fb");
    public static final UUID READ_FFD2 = UUID.fromString("0000ffd2-0000-1000-8000-00805f9b34fb");
    public static final UUID SERVICE_ACTIVE_UPLOAD = UUID.fromString("0000fc00-0000-1000-8000-00805f9b34fb");
    public static final UUID SERVICE_ACTIVE_UPLOAD_READ = UUID.fromString("0000fc01-0000-1000-8000-00805f9b34fb");
    public static final UUID SERVICE_ECG = UUID.fromString("0000ef00-0000-1000-8000-00805f9b34fb");
    public static final UUID SERVICE_FFD2 = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb");
    public static final UUID SERVICE_PAIR = UUID.fromString("0000ff04-0000-1000-8000-00805f9b34fb");
    public static final UUID WRITE_ECG = UUID.fromString("0000ef02-0000-1000-8000-00805f9b34fb");
    public static final UUID WRITE_FFD2 = UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb");

}
