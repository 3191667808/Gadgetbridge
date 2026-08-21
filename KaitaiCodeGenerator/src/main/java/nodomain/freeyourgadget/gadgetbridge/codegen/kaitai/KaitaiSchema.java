package nodomain.freeyourgadget.gadgetbridge.codegen.kaitai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a supported subset of Kaitai Struct - see {@link KaitaiParser}
 * for exactly which constructs are allowed. This class and the rest of the
 * package know nothing about device-specific protocol: given a {@code .ksy} file
 * matching the subset, they parse it and can emit a self-contained Kotlin {@code data class}
 * per type, each with a byte-exact {@code decode()}/{@code encode()}. Protocol-specific
 * generators build on top of these data classes.
 */
public final class KaitaiSchema {
    private KaitaiSchema() {
    }

    /**
     * One field of a message: either byte-aligned (bitWidth == 0) or a native {@code bN} bit field.
     * A {@code repeat: eos} attribute (bitWidth == 0, kaitaiType == "record") reads {@code recordType}
     * repeatedly until the enclosing buffer/payload is exhausted - see {@link #isRepeat()}. A
     * {@code switch-on} attribute (bitWidth == 0, kaitaiType == "switch") reads {@code switchOnId}'s
     * already-parsed value and dissects the matching entry of {@code switchCases}- see {@link #isSwitch()}.
     * A {@code reserved} attribute (kaitaiType == "reserved") is the native Kaitai "no {@code type},
     * just {@code size: N}" idiom for a raw byte span whose structure isn't modeled - see {@link #isReserved()}.
     */
    public record Attr(
            String id,
            String kaitaiType,   // u1 u2 u4 u8 s1 s2 s4 s8 f4 f8 str bN (N=1..8) "record" (repeat: eos) "switch" (switch-on) "reserved" (bare size:)
            int sizeBytes,       // byte width for byte-aligned scalar types; 0 for bN, str, record, and switch
            int bitWidth,        // 1..8 for a bN field; 0 otherwise
            String strSizeRefId, // packed-string: id of the preceding u1 length field; null for fixed-size str
            int strFixedSize,    // fixed-size str: byte count; -1 if not fixed
            String enumRef,
            Double scale,
            boolean optional,
            String doc,
            TypeDef recordType,  // non-null only when repeatEos is true: the fixed-size shape of one array element
            boolean repeatEos,   // true for a "repeat: eos" attribute (array of recordType until the end of the stream)
            String switchOnId,   // non-null only when isSwitch(): id of the immediately preceding discriminant attribute
            LinkedHashMap<Long, TypeDef> switchCases // non-null only when isSwitch(): discriminant value -> case's fixed-size shape
    ) {
        public boolean isBitfield() {
            return bitWidth > 0;
        }

        public boolean isString() {
            return "str".equals(kaitaiType);
        }

        public boolean isRepeat() {
            return repeatEos;
        }

        public boolean isSwitch() {
            return switchCases != null;
        }

        public boolean isReserved() {
            return "reserved".equals(kaitaiType);
        }
    }

    public record Meta(String id, String endian, String bitEndian) {
    }

    public record TypeDef(String id, String doc, List<Attr> seq) {
    }

    public record EnumDef(String id, LinkedHashMap<Long, String> values) {
    }

    public record Schema(
            String fileId,
            String doc,
            Meta meta,
            LinkedHashMap<String, EnumDef> enums,
            LinkedHashMap<String, TypeDef> types
    ) {
    }

    public static final Map<String, Integer> SCALAR_SIZES = Map.of(
            "u1", 1, "s1", 1,
            "u2", 2, "s2", 2,
            "u4", 4, "s4", 4,
            "u8", 8, "s8", 8,
            "f4", 4, "f8", 8
    );
}
