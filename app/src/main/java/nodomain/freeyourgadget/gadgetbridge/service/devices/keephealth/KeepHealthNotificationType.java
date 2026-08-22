package nodomain.freeyourgadget.gadgetbridge.service.devices.keephealth;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;

public enum KeepHealthNotificationType {
    CALL((byte) 0x00),
    SMS((byte) 0x01),
    WECHAT((byte) 0x02),
    QQ((byte) 0x03),
    FACEBOOK((byte) 0x04),
    SKYPE((byte) 0x05),
    TWITTER((byte) 0x06),
    WHATSAPP((byte) 0x07),
    LINE((byte) 0x08),
    EMAIL((byte) 0x09),
    INSTAGRAM((byte) 0x0A),
    LINKEDIN((byte) 0x0B),
    FACEBOOK_MESSENGER((byte) 0x0C),
    VK((byte) 0x0D),
    VIBER((byte) 0x0E),
    DINGTALK((byte) 0x0F),
    TELEGRAM((byte) 0x10),
    WEIBO((byte) 0x11),
    KAKAO_TALK((byte) 0x12),
    CALENDAR((byte) 0x13),
    DOUYIN((byte) 0x20), // TikTok
    KUAISHOU((byte) 0x21), // Kwai Video
    DOUYIN_JISUBAN((byte) 0x22), // TikTok Lite
    AMAZON((byte) 0x23),
    HALUO((byte) 0x23),
    XIAOHONGSHU((byte) 0x24), // Rednote
    MEITUAN((byte) 0x25),
    ZHIFUBAO((byte) 0x26), // Alipay
    DAZHONG_DIANPING((byte) 0x27),
    MOMO((byte) 0x28),
    BILIBILI((byte) 0x29),
    BOSS_ZHIPIN((byte) 0x2A),
    QQ_YOUXIANG((byte) 0x2B), // QQ Mail
    SOUL((byte) 0x2C),
    ZOOM((byte) 0x2D),
    BAIDU_TIEBA((byte) 0x2E),
    DOUBAN((byte) 0x2F),
    ELEME((byte) 0x30), // Ele.me
    GAODE_DITU((byte) 0x31), // Gaode Maps / AMap
    JINGDONG((byte) 0x32), // JD.com
    KUAISHOU_JISUBAN((byte) 0x33), // Kwai Lite
    MAIMAI((byte) 0x34),
    PINDUODUO((byte) 0x35), // TEMU
    QIYE_WEIXIN((byte) 0x36), // Weixin Enterprise
    DIDA_CHUXING((byte) 0x37), // Dida
    TANTAN((byte) 0x38),
    TAOBAO((byte) 0x39),
    TIELU_12306((byte) 0x3A), // China Railways
    XIANYU((byte) 0x3B),
    ZHIHU((byte) 0x3C),
    ALIBABA((byte) 0x3D),
    DIDI_CHUXING((byte) 0x3E), // Didi
    MEIYOU((byte) 0x3F),
    JIAOGUAN_12123((byte) 0x40), // National Traffic Management
    DEWU((byte) 0x41),
    WANGYI_YOUXIANG_DASHI((byte) 0x42), // NetEase Mail Master
    WANGZHERONGYAO((byte) 0x43), // Honor of Kings
    TENGXUN_HUIYI((byte) 0x44), // Tencent Meet
    QUNA_LVXING((byte) 0x45),
    XIECHENG_LVXING((byte) 0x46),
    TONGCHENG_LVXING((byte) 0x47),
    FEIZHU_LVXING((byte) 0x48),
    ZHIXING_HUOCHEPIAO((byte) 0x49),
    BAIDU_DITU((byte) 0x4A), // Baidu Maps
    TENGXUN_DITU((byte) 0x4B), // Tencent Maps / QQ Maps
    DOUYIN_SHANGCHENG((byte) 0x4C), // TikTok Shop
    KEEP_HEALTH((byte) 0xFD),

    OTHER((byte) 0xFE),
    UNKNOWN((byte) 0xFF),
    ;

    private final byte code;

    KeepHealthNotificationType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static KeepHealthNotificationType fromCode(byte code) {
        for (KeepHealthNotificationType t : values()) {
            if (t.code == code) return t;
        }
        return SMS;
    }

    public static KeepHealthNotificationType fromNotificationType(NotificationType type) {
        if (type == null) return KeepHealthNotificationType.SMS;

        switch (type) {
            case GADGETBRIDGE_TEXT_RECEIVER:
            case GENERIC_VENDOR:
            case VENDOR_KEEP_HEALTH:
                return KeepHealthNotificationType.KEEP_HEALTH;

            case GENERIC_SMS:
                return KeepHealthNotificationType.SMS;
            case CONVERSATIONS:
            case HIPCHAT:
            case KONTALK:
            case ANTOX:
            case WECHAT:
            case SIGNAL:
            case GOOGLE_MESSENGER:
            case GOOGLE_HANGOUTS:
                return KeepHealthNotificationType.WECHAT;
            case DINGTALK:
                return KeepHealthNotificationType.DINGTALK;
            case GENERIC_EMAIL:
            case GMAIL:
            case YAHOO_MAIL:
            case OUTLOOK:
                return KeepHealthNotificationType.EMAIL;
            case AMAZON:
                return KeepHealthNotificationType.AMAZON;
            case FACEBOOK:
                return KeepHealthNotificationType.FACEBOOK;
            case FACEBOOK_MESSENGER:
                return KeepHealthNotificationType.FACEBOOK_MESSENGER;
            case INSTAGRAM:
            case GOOGLE_PHOTOS:
                return KeepHealthNotificationType.INSTAGRAM;
            case KAKAO_TALK:
                return KeepHealthNotificationType.KAKAO_TALK;
            case LINE:
                return KeepHealthNotificationType.LINE;
            case TIKTOK:
                return KeepHealthNotificationType.DOUYIN;
            case TIKTOK_LITE:
                return KeepHealthNotificationType.DOUYIN_JISUBAN;
            case TIKTOK_SHOP:
                return KeepHealthNotificationType.DOUYIN_SHANGCHENG;
            case TWITTER:
                return KeepHealthNotificationType.TWITTER;
            case SKYPE:
                return KeepHealthNotificationType.SKYPE;
            case TELEGRAM:
                return KeepHealthNotificationType.TELEGRAM;
            case VIBER:
            case DISCORD:
                return KeepHealthNotificationType.VIBER;
            case WHATSAPP:
                return KeepHealthNotificationType.WHATSAPP;
            case VK:
                return KeepHealthNotificationType.VK;
            case QQ:
                return KeepHealthNotificationType.QQ;
            case ZOOM:
                return KeepHealthNotificationType.ZOOM;

            default:
                String g = type.getGenericType();
                if ("generic_email".equals(g)) return KeepHealthNotificationType.EMAIL;
                if ("generic_chat".equals(g)) return KeepHealthNotificationType.WECHAT;
                return KeepHealthNotificationType.SMS;
        }
    }
}
