package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils;

import java.util.LinkedHashMap;
import java.util.Map;

public class CardoMap<K extends ByteUtils.CardoField, Object> extends LinkedHashMap<K, Object> {
    public Object getValueByName(String name) {
        for (Map.Entry<K, Object> entry : this.entrySet()) {
            if (entry.getKey() != null && name.equals(entry.getKey().getName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public ByteUtils.CardoField getFieldByName(String name) {
        for (Map.Entry<K, Object> entry : this.entrySet()) {
            if (entry.getKey() != null && name.equals(entry.getKey().getName())) {
                return entry.getKey();
            }
        }
        return null;
    }
}
