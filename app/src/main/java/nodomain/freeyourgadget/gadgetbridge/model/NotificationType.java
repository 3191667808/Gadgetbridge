package nodomain.freeyourgadget.gadgetbridge.model;

import java.util.Arrays;
import java.util.Comparator;

public enum NotificationType {
    UNKNOWN,
    
    GENERIC_ALARM_CLOCK,
    GENERIC_CALENDAR,
    GENERIC_EMAIL,
    GENERIC_ENTERTAINMENT,
    GENERIC_GAMING,
    GENERIC_NAVIGATION,
    GENERIC_NEWS,
    GENERIC_PHONE,
    GENERIC_TRANSPORT,
    GENERIC_SOCIAL,
    GENERIC_SHOPPING,
    GENERIC_SMS,
    GENERIC_VENDOR,
    GENERIC_WALLET,

    AMAZON,
    AMAZON_PRIME_VIDEO,
    ANTOX,
    BBM,
    BUSINESS_CALENDAR,
    COL_REMINDER,
    CONVERSATIONS,
    DAILYHUNT,
    DELTACHAT,
    DIDA_CHUXING,
    DIDI_CHUXING,
    DINGTALK,
    DISCORD,
    DUNZO,
    ELEMENT,
    FACEBOOK,
    FACEBOOK_MESSENGER,
    FLIPKART,
    GAANA,
    GMAIL,
    GRAB,
    GOJEK,
    GOOGLE_CALENDAR,
    GOOGLE_CHAT,
    GOOGLE_DRIVE,
    GOOGLE_HANGOUTS,
    GOOGLE_INBOX,
    GOOGLE_MAPS,
    GOOGLE_MESSENGER,
    GOOGLE_PAY,
    GOOGLE_PHOTOS,
    HIPCHAT,
    HOTSTAR,
    INSHORTS,
    INSTAGRAM,
    KAKAO_TALK,
    KIK,
    KONTALK,
    LARK,
    LIGHTHOUSE, // ??? - No idea what this is, but it works.
    LINE,
    LINKEDIN,
    LYFT,
    MAILBOX,
    MICROSOFT_TEAMS,
    MOLLY,
    NATEON,
    NETFLIX,
    OLA,
    OUTLOOK,
    PAYTM,
    PHONEPE,
    PINTEREST,
    QQ,
    REDDIT,
    SHOPEE,
    SIGNAL,
    SKYPE,
    SLACK,
    SNAPCHAT,
    SWIGGY,
    TELEGRAM,
    THREADS,
    THREEMA,
    TIKTOK,
    TIKTOK_LITE,
    TIKTOK_SHOP,
    TOKOPEDIA,
    TRANSIT,
    TUMBLR,
    TWITTER,
    UBER,
    VIBER,
    VK,
    WECHAT,
    WHATSAPP,
    WHATSAPP_BUSINESS,
    WIRE,
    WYNK,
    YAHOO_MAIL,
    YOUTUBE,
    YOUTUBE_KIDS,
    YOUTUBE_MUSIC,
    ZALO,
    ZOMATO,
    ZOOM,

    VENDOR_KEEP_HEALTH,
    VENDOR_MORMAII,

    GADGETBRIDGE_TEXT_RECEIVER,
    GAMES,
    WEATHER,
    ;


    /**
     * Returns the enum constant as a fixed String value, e.g. to be used
     * as preference key. In case the keys are ever changed, this method
     * may be used to bring backward compatibility.
     */
    public String getFixedValue() {
        return name().toLowerCase();
    }

    public String getGenericType() {
        switch (this) {
            case GENERIC_ALARM_CLOCK:
            case GENERIC_CALENDAR:
            case GENERIC_EMAIL:
            case GENERIC_ENTERTAINMENT:
            case GENERIC_NAVIGATION:
            case GENERIC_NEWS:
            case GENERIC_PHONE:
            case GENERIC_TRANSPORT:
            case GENERIC_SHOPPING:
            case GENERIC_SOCIAL:
            case GENERIC_SMS:
            case GENERIC_VENDOR:
            case GENERIC_WALLET:
                return getFixedValue();
            case BUSINESS_CALENDAR:
            case GOOGLE_CALENDAR:
                return "generic_calendar";
            case CONVERSATIONS:
            case ANTOX:
            case BBM:
            case DELTACHAT:
            case DISCORD:
            case ELEMENT:
            case FACEBOOK_MESSENGER:
            case GOOGLE_CHAT:
            case GOOGLE_HANGOUTS:
            case GOOGLE_MESSENGER:
            case HIPCHAT:
            case KAKAO_TALK:
            case KIK:
            case KONTALK:
            case LARK:
            case LINE:
            case MICROSOFT_TEAMS:
            case MOLLY:
            case NATEON:
            case QQ:
            case SIGNAL:
            case SLACK:
            case SKYPE:
            case TELEGRAM:
            case THREEMA:
            case VIBER:
            case WECHAT:
            case WHATSAPP:
            case WHATSAPP_BUSINESS:
            case WIRE:
            case ZALO:
            case ZOOM:
                return "generic_chat";
            case GMAIL:
            case GOOGLE_INBOX:
            case MAILBOX:
            case OUTLOOK:
            case YAHOO_MAIL:
                return "generic_email";
            case AMAZON_PRIME_VIDEO:
            case HOTSTAR:
            case NETFLIX:
            case WYNK:
            case YOUTUBE:
            case YOUTUBE_KIDS:
            case YOUTUBE_MUSIC:
                return "generic_entertainment";
            case INSTAGRAM:
            case LINKEDIN:
            case FACEBOOK:
            case REDDIT:
            case SNAPCHAT:
            case TIKTOK:
            case TIKTOK_LITE:
            case TWITTER:
            case VK:
                return "generic_social";
            case DAILYHUNT:
            case GAANA:
            case INSHORTS:
                return "generic_news";
            case AMAZON:
            case FLIPKART:
            case SHOPEE:
            case TIKTOK_SHOP:
            case TOKOPEDIA:
                return "generic_shopping";
            case DIDA_CHUXING:
            case DIDI_CHUXING:
            case DUNZO:
            case GOJEK:
            case GRAB:
            case LYFT:
            case OLA:
            case SWIGGY:
            case UBER:
            case ZOMATO:
                return "generic_transport";
            case VENDOR_KEEP_HEALTH:
            case VENDOR_MORMAII:
                return "generic_vendor";
            case GOOGLE_PAY:
            case PAYTM:
            case PHONEPE:
                return "generic_wallet";
            case COL_REMINDER:
            case GADGETBRIDGE_TEXT_RECEIVER:
            case GAMES:
            case WEATHER:
            case UNKNOWN:
            default:
                return "generic";
        }
    }

    public static NotificationType[] sortedValues() {
        final NotificationType[] sorted = NotificationType.values();
        Arrays.sort(sorted, new Comparator<NotificationType>() {
            @Override public int compare(final NotificationType n1, final NotificationType n2) {
                // Keep unknown first
                if (n1.equals(NotificationType.UNKNOWN)) {
                    return -1;
                } else if (n2.equals(NotificationType.UNKNOWN)) {
                    return 1;
                }

                return n1.name().compareToIgnoreCase(n2.name());
            }
        });

        return sorted;
    }
}
