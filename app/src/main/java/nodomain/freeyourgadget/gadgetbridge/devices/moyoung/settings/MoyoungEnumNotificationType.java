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

import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;

public enum MoyoungEnumNotificationType implements MoyoungEnum {
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

    MoyoungEnumNotificationType(byte value) {
        this.value = value;
    }

    @Override
    public byte value() {
        return value;
    }

    public static MoyoungEnumNotificationType fromNotificationType(NotificationType notificationType) {
        return switch (notificationType) {
            case GENERIC_CALENDAR, BUSINESS_CALENDAR ->
                    MoyoungEnumNotificationType.MESSAGE_CALENDAR_DEFAULT;
            case GENERIC_EMAIL -> MoyoungEnumNotificationType.MESSAGE_EMAIL;
            case GENERIC_SMS -> MoyoungEnumNotificationType.MESSAGE_SMS;
            case GENERIC_PHONE -> MoyoungEnumNotificationType.MESSAGE_MISS_CALL;
            case AMAZON -> MoyoungEnumNotificationType.MESSAGE_AMAZON;
            case AMAZON_PRIME_VIDEO -> MoyoungEnumNotificationType.MESSAGE_AMAZON_PRIME_VIDEO;
            case DINGTALK -> MoyoungEnumNotificationType.MESSAGE_DINGTALK;
            case DISCORD -> MoyoungEnumNotificationType.MESSAGE_DISCORD;
            case FACEBOOK -> MoyoungEnumNotificationType.MESSAGE_FACEBOOK;
            case FACEBOOK_MESSENGER -> MoyoungEnumNotificationType.MESSAGE_MESSENGER;
            case FLIPKART -> MoyoungEnumNotificationType.MESSAGE_FLIPKART;
            case GAANA -> MoyoungEnumNotificationType.MESSAGE_GAANA;
            case GMAIL -> MoyoungEnumNotificationType.MESSAGE_GMAIL;
            case GOJEK -> MoyoungEnumNotificationType.MESSAGE_GOJEK;
            case GOOGLE_CALENDAR -> MoyoungEnumNotificationType.MESSAGE_GOOGLE_CALENDAR;
            case GOOGLE_CHAT, GOOGLE_HANGOUTS ->
                    MoyoungEnumNotificationType.MESSAGE_GOOGLE_CHAT;
            case GOOGLE_DRIVE -> MoyoungEnumNotificationType.MESSAGE_GOOGLE_DRIVE;
            case GOOGLE_MAPS -> MoyoungEnumNotificationType.MESSAGE_GOOGLE_MAPS;
            case GOOGLE_PAY -> MoyoungEnumNotificationType.MESSAGE_GOOGLE_PAY;
            case GRAB -> MoyoungEnumNotificationType.MESSAGE_GRAB;
            case HOTSTAR -> MoyoungEnumNotificationType.MESSAGE_HOTSTAR;
            case INSHORTS -> MoyoungEnumNotificationType.MESSAGE_INSHORTS;
            case INSTAGRAM -> MoyoungEnumNotificationType.MESSAGE_INSTAGRAM;
            case KAKAO_TALK -> MoyoungEnumNotificationType.MESSAGE_KAKAOTALK;
            case LARK -> MoyoungEnumNotificationType.MESSAGE_LARK;
            case LINE -> MoyoungEnumNotificationType.MESSAGE_LINE;
            case LINKEDIN -> MoyoungEnumNotificationType.MESSAGE_LINKEDIN;
            case LYFT -> MoyoungEnumNotificationType.MESSAGE_LYFT;
            case MICROSOFT_TEAMS -> MoyoungEnumNotificationType.MESSAGE_MICROSOFT_TEAMS;
            case NATEON -> MoyoungEnumNotificationType.MESSAGE_NATEON;
            case NETFLIX -> MoyoungEnumNotificationType.MESSAGE_NETFLIX;
            case OLA -> MoyoungEnumNotificationType.MESSAGE_OLA;
            case OUTLOOK -> MoyoungEnumNotificationType.MESSAGE_OUTLOOK;
            case PAYTM -> MoyoungEnumNotificationType.MESSAGE_PAYTM;
            case PHONEPE -> MoyoungEnumNotificationType.MESSAGE_PHONEPE;
            case QQ -> MoyoungEnumNotificationType.MESSAGE_QQ;
            case REDDIT -> MoyoungEnumNotificationType.MESSAGE_REDDIT;
            case SHOPEE -> MoyoungEnumNotificationType.MESSAGE_SHOPEE;
            case SKYPE -> MoyoungEnumNotificationType.MESSAGE_SKYPE;
            case SLACK -> MoyoungEnumNotificationType.MESSAGE_SLACK;
            case SNAPCHAT -> MoyoungEnumNotificationType.MESSAGE_SNAPCHAT;
            case SWIGGY -> MoyoungEnumNotificationType.MESSAGE_SWIGGY;
            case TELEGRAM -> MoyoungEnumNotificationType.MESSAGE_TELEGRAM;
            case THREADS -> MoyoungEnumNotificationType.MESSAGE_THREADS;
            case TIKTOK -> MoyoungEnumNotificationType.MESSAGE_TIKTOK;
            case TOKOPEDIA -> MoyoungEnumNotificationType.MESSAGE_TOKOPEDIA;
            case TWITTER -> MoyoungEnumNotificationType.MESSAGE_TWITTER;
            case UBER -> MoyoungEnumNotificationType.MESSAGE_UBER;
            case VIBER -> MoyoungEnumNotificationType.MESSAGE_VIBER;
            case WECHAT -> MoyoungEnumNotificationType.MESSAGE_WECHAT;
            case WHATSAPP, WHATSAPP_BUSINESS -> MoyoungEnumNotificationType.MESSAGE_WHATSAPP;
            case WYNK -> MoyoungEnumNotificationType.MESSAGE_WYNK;
            case YAHOO_MAIL -> MoyoungEnumNotificationType.MESSAGE_YAHOO;
            case YOUTUBE, YOUTUBE_KIDS -> MoyoungEnumNotificationType.MESSAGE_YOUTUBE;
            case YOUTUBE_MUSIC -> MoyoungEnumNotificationType.MESSAGE_YOUTUBE_MUSIC;
            case ZALO -> MoyoungEnumNotificationType.MESSAGE_ZALO;
            case ZOMATO -> MoyoungEnumNotificationType.MESSAGE_ZOMATO;
            case VENDOR_MORMAII -> MoyoungEnumNotificationType.MESSAGE_MORMAII_SMARTWATCHES;
            default -> MESSAGE_OTHER;
        };
    }
}
