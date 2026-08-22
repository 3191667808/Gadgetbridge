/*  Copyright (C) 2019 krzys_h
    Copyright (C) 2026 Reinhart Previano Koentjoro

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.moyoung.settings;

import nodomain.freeyourgadget.gadgetbridge.model.AppNotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;

public enum MoyoungEnumNotificationSource implements MoyoungEnum {
    CALL_OFF_HOOK((byte) -1), // Assumed from old V1 firmware code
    CALL((byte) 0),
    MESSAGE_SMS((byte) 1),
    MESSAGE_WECHAT((byte) 2),
    MESSAGE_QQ((byte) 3),
    MESSAGE_FACEBOOK((byte) 4),
    MESSAGE_TWITTER((byte) 5),
    MESSAGE_INSTAGRAM((byte) 6),
    MESSAGE_SKYPE((byte) 7),
    MESSAGE_WHATSAPP((byte) 8),
    MESSAGE_LINE((byte) 9),
    MESSAGE_KAKAOTALK((byte) 10),
    MESSAGE_EMAIL((byte) 11),
    MESSAGE_MESSENGER((byte) 12),
    MESSAGE_ZALO((byte) 13),
    MESSAGE_TELEGRAM((byte) 14),
    MESSAGE_VIBER((byte) 15),
    MESSAGE_NATEON((byte) 16),
    MESSAGE_GMAIL((byte) 17),
    MESSAGE_GOOGLE_CALENDAR((byte) 18),
    MESSAGE_DAILYHUNT((byte) 19),
    MESSAGE_OUTLOOK((byte) 20),
    MESSAGE_YAHOO((byte) 21),
    MESSAGE_INSHORTS((byte) 22),
    MESSAGE_PHONEPE((byte) 23),
    MESSAGE_GOOGLE_PAY((byte) 24),
    MESSAGE_PAYTM((byte) 25),
    MESSAGE_SWIGGY((byte) 26),
    MESSAGE_ZOMATO((byte) 27),
    MESSAGE_UBER((byte) 28),
    MESSAGE_OLA((byte) 29),
    MESSAGE_REFLEXAPP((byte) 30),
    MESSAGE_SNAPCHAT((byte) 31),
    MESSAGE_YOUTUBE_MUSIC((byte) 32),
    MESSAGE_YOUTUBE((byte) 33),
    MESSAGE_LINKEDIN((byte) 34),
    MESSAGE_AMAZON((byte) 35),
    MESSAGE_FLIPKART((byte) 36),
    MESSAGE_NETFLIX((byte) 37),
    MESSAGE_HOTSTAR((byte) 38),
    MESSAGE_AMAZON_PRIME_VIDEO((byte) 39),
    MESSAGE_GOOGLE_CHAT((byte) 40),
    MESSAGE_WYNK((byte) 41),
    MESSAGE_GOOGLE_DRIVE((byte) 42),
    MESSAGE_DUNZO((byte) 43),
    MESSAGE_GAANA((byte) 44),
    MESSAGE_MISS_CALL((byte) 45), // In iOS app this icon is also used for priority / time-sensitive notifications
    MESSAGE_WHATSAPP_BUSINESS((byte) 46),
    MESSAGE_DINGTALK((byte) 47),
    MESSAGE_TIKTOK((byte) 48),
    MESSAGE_LYFT((byte) 49),
    MESSAGE_MAIL((byte) 50),
    MESSAGE_GOOGLE_MAPS((byte) 51),
    MESSAGE_SLACK((byte) 52),
    MESSAGE_MICROSOFT_TEAMS((byte) 53),
    MESSAGE_MORMAII_SMARTWATCHES((byte) 54),
    MESSAGE_REDDIT((byte) 55),
    MESSAGE_DISCORD((byte) 56),
    MESSAGE_CALENDAR_DEFAULT((byte) 57),
    MESSAGE_GOJEK((byte) 58),
    MESSAGE_LARK((byte) 59),
    MESSAGE_GRAB((byte) 60),
    MESSAGE_SHOPEE((byte) 61),
    MESSAGE_TOKOPEDIA((byte) 62),
    MESSAGE_THREADS((byte) 63),
    MESSAGE_SMARTGOODS((byte) 64),
    MESSAGE_OTHER((byte) 128)
    ;
    
    public final byte value;

    MoyoungEnumNotificationSource(byte value) {
        this.value = value;
    }

    @Override
    public byte value() {
        return value;
    }

    public static MoyoungEnumNotificationSource fromNotificationType(NotificationType notificationType) {
        return switch (notificationType) {
            case GENERIC_CALENDAR, BUSINESS_CALENDAR ->
                    MoyoungEnumNotificationSource.MESSAGE_CALENDAR_DEFAULT;
            case GENERIC_EMAIL -> MoyoungEnumNotificationSource.MESSAGE_EMAIL;
            case GENERIC_SMS -> MoyoungEnumNotificationSource.MESSAGE_SMS;
            case GENERIC_PHONE -> MoyoungEnumNotificationSource.MESSAGE_MISS_CALL;
            case AMAZON -> MoyoungEnumNotificationSource.MESSAGE_AMAZON;
            case AMAZON_PRIME_VIDEO -> MoyoungEnumNotificationSource.MESSAGE_AMAZON_PRIME_VIDEO;
            case DINGTALK -> MoyoungEnumNotificationSource.MESSAGE_DINGTALK;
            case DISCORD -> MoyoungEnumNotificationSource.MESSAGE_DISCORD;
            case FACEBOOK -> MoyoungEnumNotificationSource.MESSAGE_FACEBOOK;
            case FACEBOOK_MESSENGER -> MoyoungEnumNotificationSource.MESSAGE_MESSENGER;
            case FLIPKART -> MoyoungEnumNotificationSource.MESSAGE_FLIPKART;
            case GAANA -> MoyoungEnumNotificationSource.MESSAGE_GAANA;
            case GMAIL -> MoyoungEnumNotificationSource.MESSAGE_GMAIL;
            case GOJEK -> MoyoungEnumNotificationSource.MESSAGE_GOJEK;
            case GOOGLE_CALENDAR -> MoyoungEnumNotificationSource.MESSAGE_GOOGLE_CALENDAR;
            case GOOGLE_CHAT, GOOGLE_HANGOUTS ->
                    MoyoungEnumNotificationSource.MESSAGE_GOOGLE_CHAT;
            case GOOGLE_DRIVE -> MoyoungEnumNotificationSource.MESSAGE_GOOGLE_DRIVE;
            case GOOGLE_MAPS -> MoyoungEnumNotificationSource.MESSAGE_GOOGLE_MAPS;
            case GOOGLE_PAY -> MoyoungEnumNotificationSource.MESSAGE_GOOGLE_PAY;
            case GRAB -> MoyoungEnumNotificationSource.MESSAGE_GRAB;
            case HOTSTAR -> MoyoungEnumNotificationSource.MESSAGE_HOTSTAR;
            case INSHORTS -> MoyoungEnumNotificationSource.MESSAGE_INSHORTS;
            case INSTAGRAM -> MoyoungEnumNotificationSource.MESSAGE_INSTAGRAM;
            case KAKAO_TALK -> MoyoungEnumNotificationSource.MESSAGE_KAKAOTALK;
            case LARK -> MoyoungEnumNotificationSource.MESSAGE_LARK;
            case LINE -> MoyoungEnumNotificationSource.MESSAGE_LINE;
            case LINKEDIN -> MoyoungEnumNotificationSource.MESSAGE_LINKEDIN;
            case LYFT -> MoyoungEnumNotificationSource.MESSAGE_LYFT;
            case MICROSOFT_TEAMS -> MoyoungEnumNotificationSource.MESSAGE_MICROSOFT_TEAMS;
            case NATEON -> MoyoungEnumNotificationSource.MESSAGE_NATEON;
            case NETFLIX -> MoyoungEnumNotificationSource.MESSAGE_NETFLIX;
            case OLA -> MoyoungEnumNotificationSource.MESSAGE_OLA;
            case OUTLOOK -> MoyoungEnumNotificationSource.MESSAGE_OUTLOOK;
            case PAYTM -> MoyoungEnumNotificationSource.MESSAGE_PAYTM;
            case PHONEPE -> MoyoungEnumNotificationSource.MESSAGE_PHONEPE;
            case QQ -> MoyoungEnumNotificationSource.MESSAGE_QQ;
            case REDDIT -> MoyoungEnumNotificationSource.MESSAGE_REDDIT;
            case SHOPEE -> MoyoungEnumNotificationSource.MESSAGE_SHOPEE;
            case SKYPE -> MoyoungEnumNotificationSource.MESSAGE_SKYPE;
            case SLACK -> MoyoungEnumNotificationSource.MESSAGE_SLACK;
            case SNAPCHAT -> MoyoungEnumNotificationSource.MESSAGE_SNAPCHAT;
            case SWIGGY -> MoyoungEnumNotificationSource.MESSAGE_SWIGGY;
            case TELEGRAM -> MoyoungEnumNotificationSource.MESSAGE_TELEGRAM;
            case THREADS -> MoyoungEnumNotificationSource.MESSAGE_THREADS;
            case TIKTOK -> MoyoungEnumNotificationSource.MESSAGE_TIKTOK;
            case TOKOPEDIA -> MoyoungEnumNotificationSource.MESSAGE_TOKOPEDIA;
            case TWITTER -> MoyoungEnumNotificationSource.MESSAGE_TWITTER;
            case UBER -> MoyoungEnumNotificationSource.MESSAGE_UBER;
            case VIBER -> MoyoungEnumNotificationSource.MESSAGE_VIBER;
            case WECHAT -> MoyoungEnumNotificationSource.MESSAGE_WECHAT;
            case WHATSAPP, WHATSAPP_BUSINESS -> MoyoungEnumNotificationSource.MESSAGE_WHATSAPP;
            case WYNK -> MoyoungEnumNotificationSource.MESSAGE_WYNK;
            case YAHOO_MAIL -> MoyoungEnumNotificationSource.MESSAGE_YAHOO;
            case YOUTUBE, YOUTUBE_KIDS -> MoyoungEnumNotificationSource.MESSAGE_YOUTUBE;
            case YOUTUBE_MUSIC -> MoyoungEnumNotificationSource.MESSAGE_YOUTUBE_MUSIC;
            case ZALO -> MoyoungEnumNotificationSource.MESSAGE_ZALO;
            case ZOMATO -> MoyoungEnumNotificationSource.MESSAGE_ZOMATO;
            case VENDOR_MORMAII -> MoyoungEnumNotificationSource.MESSAGE_MORMAII_SMARTWATCHES;
            default -> MESSAGE_OTHER;
        };
    }
}
