/*  Copyright (C) 2025 Daniel Giritzer, MSc (giri@nwrk.biz)
    Copyright (C) 2025 De_Coder (de_coder@posteo.de)

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
package nodomain.freeyourgadget.gadgetbridge.devices.keepfit;

import java.util.UUID;

public class KeepFitConstants {
    public static final UUID SUOTA_SERVICE = UUID.fromString("0000fef5-0000-1000-8000-00805f9b34fb"); // Renesas SUOTA Service for Dialog DA145XX controllers
    public static final UUID COMMUNICATION_SERVICE = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb"); // Communication Service
    public static final UUID CHARACTERISTIC_WRITE = UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb"); // Communication Service Write Characteristic
    public static final UUID CHARACTERISTIC_READ = UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb"); // Communication Service Read Characteristic
    
    public static final byte CMD_SET_TIME = 0x01;
    public static final byte CMD_SET_USER_INFO = 0x02;
    public static final byte CMD_SET_USER_INFO_ERROR = (byte)0x82;
    public static final byte CMD_GET_CUR_SPORT_DATA = 0x03;
    public static final byte CMD_SEND_VIBRATION_SIGNAL = 0x04; // used by the ring to blink
    public static final byte CMD_GET_DEVICE_COMMAND = 0x06; // arr[0] = opcode, arr[1] = command
    public static final byte CMD_SET_IDLE_TIME = 0x08; // idle move reminder
    public static final byte CMD_SET_REMINDER = (byte)0x31; // medicine, drink alerts and custom alerts
    public static final byte CMD_SET_SLEEP_TIME = 0x09;
    public static final byte CMD_SET_MENSTRUAL_CYCLE = (byte)0x44;

    public static final byte CMD_SET_HOUR_FORMAT = 0x1d;
    public static final byte CMD_KEEPALIVE_PING = 0x3a;
    public static final byte CMD_SET_APP_ID = 0x48;
    public static final byte CMD_TOGGLE_SPO2 = 0x3e; // arr[0] = opcode, arr[1] = 1 / 0
    public static final byte CMD_TOGGLE_BLOOD_PRESSURE = 0x23; // arr[0] = opcode, arr[1] = 1 / 0 (measures heartrate, systolicPressure, daoiastolicpressure, oxygen, fatigueValue)
    public static final byte CMD_TOGGLE_BLOOD_PRESSURE_DONE = (byte)0xa3;
    public static final byte CMD_RECEIVED_SPO2_DATA = 0x3f;
    public static final byte CMD_RECEIVED_SENSOR_DATA = 0x24; // result of the "CMD_TOGGLE_BLOOD_PRESSURE" command: heartrate, systolicPressure, daoiastolicpressure, oxygen, fatigueValue
    public static final byte CMD_SET_LIVE_HEART_RATE_ENABLED = 0x14;
    public static final byte CMD_SET_LIVE_HEART_RATE_DISABLED = 0x15;
    public static final byte CMD_SET_AUTO_HEART_MODE = 0x19; // allows setting the heart rate measure interval
    public static final byte CMD_SET_LANG = 0x21;
    public static final byte CMD_SET_CAMERA_MODE = 0x7; // arr[0] = opcode, arr[1] = 1 / 0  enable or disable remote photo snap

    public static final byte CMD_GET_BATTERY = 0xb;
    public static final byte CMD_GET_BATTERY_ERROR = (byte)0x8b;
    public static final byte CMD_GET_DEVICE_INFO = 0x0c;
    public static final byte CMD_GET_DEVICE_INFO_ERROR = (byte)0x8c;
    public static final byte CMD_SET_DEVICE_INFO = 0x1b; // vibrate, light, quiet mode, (quiet mode timespan) 
    public static final byte CMD_SET_DEVICE_MODE = 0x0e;
    public static final byte CMD_SET_ALARM = 0x0d;

    public static final byte CMD_TRIGGER_ACTIVITY_REPORT = 0x10;
    public static final byte CMD_TRIGGER_ACTIVITY_REPORT_SLEEP_RESULT = 0x11;
    public static final byte CMD_TRIGGER_ACTIVITY_REPORT_ERROR = (byte)0x90;
    public static final byte CMD_TRIGGER_HEART_RATE_REPORT = 0x16;
    public static final byte CMD_TRIGGER_HEART_RATE_REPORT_ERROR = (byte)0x96;
    public static final byte CMD_TRIGGER_SPORT_REPORT = 0x25;
    public static final byte CMD_SPORT_REPORT_RESULT_CURRENT = 0x13;
    public static final byte CMD_SPORT_REPORT_RESULT_ERROR = (byte)0x83;

    public static final byte CMD_TRIGGER_LOST = 0x05; // antilost
    public static final byte CMD_TRIGGER_LOST_DONE = (byte)0x85; // antilost-finished
    public static final byte CMD_SET_STEP_GOAL = 0x1A; // allows storing a step goal
    public static final byte CMD_SET_STEP_GOAL_ERROR = (byte)0x9A;
    public static final byte CMD_SET_HEART_RATE_AREA = 0x26;
    public static final byte CMD_ALERT_NOTIFICATION = 0x12;
    public static final byte CMD_GET_DEVICE_WALLPAPER = 0x34;

    public static final byte CMD_DEVICE_SUPPORTED_FUNCTIONS = 0x20;
    public static final byte CMD_SET_WEATHER_DATA = 0x22; 

    public static final byte CMD_NOTIFY_SENSOR_DATA = 0x27;
    public static final byte CMD_NOTIFY_BLOOD_DATA = 0x28;


    public static final int NOTIFICATION_DING = 11;
    public static final int NOTIFICATION_FACEBOOK = 4;
    public static final int NOTIFICATION_GMAIL = 28;
    public static final int NOTIFICATION_INSTAGRAM = 13;
    public static final int NOTIFICATION_IOSCALENDAR = 23;
    public static final int NOTIFICATION_IOSEMAIL = 22;
    public static final int NOTIFICATION_KAKAOTALK = 9;
    public static final int NOTIFICATION_LINE = 8;
    public static final int NOTIFICATION_LINKEDIN = 14;
    public static final int NOTIFICATION_MQQ = 3;
    public static final int NOTIFICATION_NATEON = 18;
    public static final int NOTIFICATION_OUTLOOK = 21;
    public static final int NOTIFICATION_PHONE = 0;
    public static final int NOTIFICATION_PINTEREST = 26;
    public static final int NOTIFICATION_SKYPE = 5;
    public static final int NOTIFICATION_SMS = 1;
    public static final int NOTIFICATION_SNAPCHAT = 15;
    public static final int NOTIFICATION_TELEGRAM = 20;
    public static final int NOTIFICATION_TIKTOK = 27;
    public static final int NOTIFICATION_TUMBLR = 17;
    public static final int NOTIFICATION_TWITTER = 6;
    public static final int NOTIFICATION_VIBER = 19;
    public static final int NOTIFICATION_VKONTAKTE = 24;
    public static final int NOTIFICATION_WANG = 12;
    public static final int NOTIFICATION_WEIBO = 16;
    public static final int NOTIFICATION_WEIXIN = 2;
    public static final int NOTIFICATION_WHATSAPP = 7;
    public static final int NOTIFICATION_YOUTUBE = 25;
}