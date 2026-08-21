package nodomain.freeyourgadget.gadgetbridge.codegen.kaitai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Attr;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.EnumDef;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.TypeDef;

/**
 * Emits Kotlin source fragments from a validated {@link KaitaiSchema}:
 * * an enum's sealed-class-plus-{@code Raw}-fallback text
 * * a type's constructor property list, and its {@code decode()}/{@code encode()} bodies.
 * <p>
 * This is a library, not a whole-file generator - it has no opinion on what a type
 * becomes a Kotlin class named, what it extends, or where the file goes; a
 * caller assembles those (see {@code duml.codegen.DumlKotlinEmitter}
 * for one that wraps this output in a {@code DumlCommand} sealed hierarchy).
 * <p>
 * Every scalar and bit-group read/write goes through {@link #group}, which
 * splits a flat {@code seq} into byte-aligned units - a lone scalar/string,
 * a run of {@code bN} fields that exactly fills one byte, or a packed-string
 * pair (a {@code u1} length attribute immediately followed by the {@code str}
 * that references it - guaranteed adjacent by {@link KaitaiParser}). That
 * grouping is what lets {@link #emitDecodeCompanion} read a bitfield's byte
 * once and mask out each named sub-field (or read a string's length once and
 * slice the right number of bytes after it), and lets {@link #emitEncodeMethod}
 * accumulate a bitfield group back into one {@code put()} - or, for a packed
 * string, write its length-then-bytes as one unit.
 * <p>
 * A packed string's length attribute is a real field in {@code seq} (it
 * occupies a real byte the codec must read/write) but is never itself a
 * Kotlin property - {@link KaitaiParser} already guarantees decode() would
 * recompute it from the string's own byte length, so exposing it separately
 * would let a caller set a length that disagrees with the string it names,
 * breaking byte-exactness. A {@code reserved} attribute (bare {@code size:},
 * no {@code type:}) is hidden for a different reason - see {@link Attr#isReserved()}.
 * {@link #hiddenPropertyIds} is how every method here agrees on which attribute ids to hide.
 * <p>
 * A type containing any packed string decodes/encodes via a variable-length
 * path ({@link #hasVariableLength}) instead of the fixed-size-payload path
 * the rest of this class uses: there is no fixed total size to check
 * upfront or allocate a buffer for. {@link KaitaiParser} rejects combining a
 * packed string with an {@code if:}-gated optional tail in the same type, so
 * the two paths never need to compose.
 */
@SuppressWarnings("SequencedCollectionMethodCanBeUsed")
public final class KaitaiKotlinEmitter {
    private KaitaiKotlinEmitter() {
    }

    /**
     * Imports every emitted {@code decode()}/{@code encode()} needs; callers add these once per file.
     */
    public static final List<String> REQUIRED_IMPORTS = List.of(
            "java.nio.ByteBuffer",
            "java.nio.ByteOrder",
            "org.slf4j.LoggerFactory",
            "nodomain.freeyourgadget.gadgetbridge.util.GB"
    );

    //
    // enums
    //

    public static void emitEnum(final StringBuilder out, final EnumDef enumDef) {
        final String className = Names.toPascalCase(enumDef.id());
        out.append("sealed class ").append(className).append("(val value: Int) {\n");
        for (final var e : enumDef.values().entrySet()) {
            out.append("    object ").append(Names.toPascalCase(e.getValue()))
                    .append(" : ").append(className).append("(0x").append(Long.toHexString(e.getKey())).append(")\n");
        }
        out.append("    /** Any value not named above - kept verbatim. */\n");
        out.append("    data class Raw(val rawValue: Int) : ").append(className).append("(rawValue)\n\n");
        out.append("    companion object {\n");
        out.append("        fun fromValue(v: Int): ").append(className).append(" = when (v) {\n");
        for (final var e : enumDef.values().entrySet()) {
            out.append("            0x").append(Long.toHexString(e.getKey())).append(" -> ").append(Names.toPascalCase(e.getValue())).append("\n");
        }
        out.append("            else -> Raw(v)\n");
        out.append("        }\n");
        out.append("    }\n");
        out.append("}\n\n");
    }

    //
    // type -> Kotlin
    //

    public static String kotlinType(final Attr attr) {
        if (attr.isRepeat()) {
            return "List<" + Names.toPascalCase(attr.recordType().id()) + ">";
        }
        if (attr.isSwitch()) {
            return switchInterfaceName(attr);
        }
        if (attr.isReserved()) {
            return "ByteArray"; // never optional - see KaitaiParser#parseReservedAttr
        }
        final String base;
        if (attr.enumRef() != null) {
            base = Names.toPascalCase(attr.enumRef());
        } else if (attr.isString()) {
            base = "String";
        } else if (attr.scale() != null) {
            base = "Float";
        } else if (attr.isBitfield()) {
            base = attr.bitWidth() == 1 ? "Boolean" : "Int";
        } else {
            base = switch (attr.kaitaiType()) {
                case "f8" -> "Double";
                case "f4" -> "Float";
                case "u8", "s8" -> "Long";
                default -> "Int"; // u1 u2 u4 s1 s2 s4 all fit comfortably in Int for our field widths
            };
        }
        return attr.optional() ? base + "?" : base;
    }

    /**
     * The sealed interface name a {@code switch-on} attribute's case classes (and its
     * {@link #switchUnknownClassName} fallback) implement - one per switch attribute, named after
     * the attribute's own id so multiple switch attributes in one file don't collide.
     */
    public static String switchInterfaceName(final Attr attr) {
        return Names.toPascalCase(attr.id());
    }

    /**
     * The fallback class for a discriminant value with no matching case in {@link #emitSwitchTypes} -
     * carries the undecoded bytes rather than dropping them, since DUML command sets gain new
     * discriminant values in newer firmware before this schema catches up to them.
     */
    public static String switchUnknownClassName(final Attr attr) {
        return switchInterfaceName(attr) + "Unknown";
    }

    /**
     * Emits a {@code /** ... *&#47;} KDoc block from a type's {@code doc:} text plus each
     * documented attribute's {@code doc:} as an {@code @property} line, or nothing at all if
     * neither exists. Packed-string length prefixes are skipped, same as {@link #emitProperties} -
     * {@code @property} only makes sense for things that are actually properties.
     */
    public static void emitKDoc(final StringBuilder out, final String indent, final String typeDoc, final List<Attr> seq) {
        final Set<String> hiddenIds = hiddenPropertyIds(seq);
        final List<String> propertyLines = new ArrayList<>();
        for (final Attr attr : seq) {
            if (hiddenIds.contains(attr.id()) || attr.doc() == null || attr.doc().isBlank()) {
                continue;
            }
            propertyLines.add("@property " + Names.toCamelCase(attr.id()) + " " + oneLine(attr.doc()));
        }
        final boolean hasTypeDoc = typeDoc != null && !typeDoc.isBlank();
        if (!hasTypeDoc && propertyLines.isEmpty()) {
            return;
        }

        out.append(indent).append("/**\n");
        if (hasTypeDoc) {
            for (final String line : typeDoc.strip().split("\n")) {
                out.append(indent).append(" * ").append(line.strip().replace("*/", "* /")).append("\n");
            }
        }
        if (hasTypeDoc && !propertyLines.isEmpty()) {
            out.append(indent).append(" *\n");
        }
        for (final String line : propertyLines) {
            out.append(indent).append(" * ").append(line).append("\n");
        }
        out.append(indent).append(" */\n");
    }

    /**
     * Collapses a possibly-multi-line doc string to one line and neutralizes any embedded "*\/" so it can't close the KDoc block early.
     */
    private static String oneLine(final String doc) {
        return doc.strip().replace("\n", " ").replace("*/", "* /");
    }

    /**
     * Emits just the constructor parameter lines ({@code val x: T,\n} ...) for {@code seq}.
     */
    public static void emitProperties(final StringBuilder out, final String indent, final List<Attr> seq) {
        final Set<String> hiddenIds = hiddenPropertyIds(seq);
        for (final Attr attr : seq) {
            if (hiddenIds.contains(attr.id())) {
                continue;
            }
            out.append(indent).append("val ").append(Names.toCamelCase(attr.id())).append(": ").append(kotlinType(attr));
            if (attr.optional()) {
                out.append(" = null");
            }
            out.append(",\n");
        }
    }

    /**
     * True if {@code seq} has a raw {@code ByteArray} property (a {@code reserved} / bare {@code size:}
     * attribute - see {@link Attr#isReserved()}). A caller that wraps this emitter's output in a
     * {@code data class} must additionally call {@link #emitEqualsAndHashCode} when this is true:
     * Kotlin's auto-generated data-class {@code equals()}/{@code hashCode()} compare array properties
     * by reference, not content, so two {@code decode()} calls on identical bytes would otherwise
     * compare unequal.
     */
    public static boolean hasByteArrayProperty(final List<Attr> seq) {
        return seq.stream().anyMatch(Attr::isReserved);
    }

    /**
     * Emits {@code override fun equals(other: Any?): Boolean} / {@code override fun hashCode(): Int}
     * for a type with a raw {@code ByteArray} property - see {@link #hasByteArrayProperty}. Every
     * other property compares/hashes the ordinary Kotlin way ({@code ==} is already null-safe
     * regardless of whether the property is optional); only {@code reserved} properties need
     * {@code contentEquals}/{@code contentHashCode} instead.
     */
    public static void emitEqualsAndHashCode(final StringBuilder out, final String indent, final String className, final List<Attr> seq) {
        final Set<String> hiddenIds = hiddenPropertyIds(seq);
        final List<Attr> props = seq.stream().filter(a -> !hiddenIds.contains(a.id())).toList();

        out.append(indent).append("override fun equals(other: Any?): Boolean {\n");
        out.append(indent).append("    if (this === other) return true\n");
        out.append(indent).append("    if (other !is ").append(className).append(") return false\n");
        out.append(indent).append("    return ");
        final List<String> comparisons = new ArrayList<>();
        for (final Attr a : props) {
            final String id = Names.toCamelCase(a.id());
            comparisons.add(a.isReserved() ? id + ".contentEquals(other." + id + ")" : id + " == other." + id);
        }
        out.append(String.join(" &&\n" + indent + "            ", comparisons)).append("\n");
        out.append(indent).append("}\n\n");

        out.append(indent).append("override fun hashCode(): Int {\n");
        out.append(indent).append("    var result = 0\n");
        for (final Attr a : props) {
            final String id = Names.toCamelCase(a.id());
            final String hash = a.isReserved() ? id + ".contentHashCode()" : a.optional() ? id + "?.hashCode() ?: 0" : id + ".hashCode()";
            out.append(indent).append("    result = 31 * result + (").append(hash).append(")\n");
        }
        out.append(indent).append("    return result\n");
        out.append(indent).append("}\n");
    }

    /**
     * Emits a small {@code data class} for a {@code repeat: eos} attribute's record shape, with local
     * {@code decode(buf: ByteBuffer)}/{@code encode(buf: ByteBuffer)} that read/write directly against a
     * shared buffer mid-stream - unlike {@link #emitDecodeCompanion}/{@link #emitEncodeMethod}'s
     * {@code decode(payload: ByteArray)}/{@code encode(): ByteArray}, which own/allocate their own buffer.
     * A caller emits this once per repeat attribute, before the leaf class whose {@code seq} contains it -
     * see {@code duml.codegen.DumlKotlinEmitter}. {@code record}'s own {@code seq} is guaranteed (by
     * {@link KaitaiParser}) to be fully mandatory and byte-aligned, so the fixed-size decode/encode shape
     * used here (no optional tail, no variable length) is always the correct one.
     *
     * @param implementsInterface non-null to declare {@code : implementsInterface} on the class (a
     *                            switch-on attribute's case class); null for a plain repeat:eos record.
     */
    public static void emitRecordClass(final StringBuilder out, final String indent, final TypeDef record, final String implementsInterface) {
        final String className = Names.toPascalCase(record.id());
        final List<Attr> seq = record.seq();

        emitKDoc(out, indent, record.doc(), seq);
        out.append(indent).append("data class ").append(className).append("(\n");
        emitProperties(out, indent + "    ", seq);
        out.append(indent).append(")");
        if (implementsInterface != null) {
            out.append(" : ").append(implementsInterface);
        }
        out.append(" {\n");

        out.append(indent).append("    companion object {\n");
        out.append(indent).append("        fun decode(buf: ByteBuffer): ").append(className).append(" {\n");
        for (final CodegenUnit unit : group(seq)) {
            emitDecodeUnit(out, unit, indent + "            ", true);
        }
        final Set<String> hiddenIds = hiddenPropertyIds(seq);
        out.append(indent).append("            return ").append(className).append("(\n");
        for (final Attr attr : seq) {
            if (hiddenIds.contains(attr.id())) {
                continue;
            }
            final String id = Names.toCamelCase(attr.id());
            out.append(indent).append("                ").append(id).append(" = ").append(id).append(",\n");
        }
        out.append(indent).append("            )\n");
        out.append(indent).append("        }\n");
        out.append(indent).append("    }\n\n");

        out.append(indent).append("    fun encode(buf: ByteBuffer) {\n");
        for (final CodegenUnit unit : group(seq)) {
            emitEncodeUnit(out, unit, indent + "        ");
        }
        out.append(indent).append("    }\n");
        if (hasByteArrayProperty(seq)) {
            out.append("\n");
            emitEqualsAndHashCode(out, indent + "    ", className, seq);
        }
        out.append(indent).append("}\n\n");
    }

    /**
     * Emits a {@code switch-on} attribute's full type family: a marker {@code sealed interface}
     * (see {@link #switchInterfaceName}), one {@code data class} per distinct case type (via
     * {@link #emitRecordClass}, each declared to implement that interface), and a
     * {@link #switchUnknownClassName} fallback class that also implements it - reads whatever
     * bytes remain in the buffer verbatim, for a discriminant value with no matching case. A
     * caller emits this once per switch attribute, before the leaf class whose {@code seq}
     * contains it - see {@code duml.codegen.DumlKotlinEmitter}.
     */
    public static void emitSwitchTypes(final StringBuilder out, final String indent, final Attr switchAttr) {
        final String interfaceName = switchInterfaceName(switchAttr);
        final String unknownName = switchUnknownClassName(switchAttr);

        out.append(indent).append("sealed interface ").append(interfaceName).append("\n\n");

        final var emitted = new LinkedHashSet<TypeDef>();
        for (final TypeDef caseType : switchAttr.switchCases().values()) {
            if (emitted.add(caseType)) {
                emitRecordClass(out, indent, caseType, interfaceName);
            }
        }

        out.append(indent).append("class ").append(unknownName).append("(val rawBytes: ByteArray) : ").append(interfaceName).append(" {\n");
        out.append(indent).append("    companion object {\n");
        out.append(indent).append("        fun decode(buf: ByteBuffer): ").append(unknownName).append(" {\n");
        out.append(indent).append("            val rawBytes = ByteArray(buf.remaining())\n");
        out.append(indent).append("            buf.get(rawBytes)\n");
        out.append(indent).append("            return ").append(unknownName).append("(rawBytes)\n");
        out.append(indent).append("        }\n");
        out.append(indent).append("    }\n\n");
        out.append(indent).append("    fun encode(buf: ByteBuffer) {\n");
        out.append(indent).append("        buf.put(rawBytes)\n");
        out.append(indent).append("    }\n");
        out.append(indent).append("}\n\n");
    }

    //
    // decode
    //

    /**
     * Emits {@code companion object { fun decode(payload: ByteArray): <resultTypeName> { ... } } }.
     */
    public static void emitDecodeCompanion(final StringBuilder out, final String indent, final String resultTypeName, final List<Attr> seq) {
        if (hasVariableLength(seq)) {
            emitVariableLengthDecodeCompanion(out, indent, resultTypeName, seq);
            return;
        }

        final int mandatoryBytes = totalBytes(seq, false);
        final int optionalBytes = totalBytes(seq, true);
        final boolean hasOptional = optionalBytes > 0;
        final int fullBytes = mandatoryBytes + optionalBytes;

        out.append(indent).append("companion object {\n");
        out.append(indent).append("    private val LOG = LoggerFactory.getLogger(").append(resultTypeName).append("::class.java)\n");
        out.append("\n");
        out.append(indent).append("    fun decode(payload: ByteArray): ").append(resultTypeName).append(" {\n");
        out.append(indent).append("        require(payload.size >= ").append(mandatoryBytes).append(") {\n");
        out.append(indent).append("            \"").append(resultTypeName).append(" payload must be at least ").append(mandatoryBytes)
                .append(" bytes, got ${payload.size}\"\n");
        out.append(indent).append("        }\n");
        out.append(indent).append("        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)\n");

        final List<CodegenUnit> mandatoryUnits = new ArrayList<>();
        final List<CodegenUnit> optionalUnits = new ArrayList<>();
        for (final CodegenUnit unit : group(seq)) {
            (unit.attrs().get(0).optional() ? optionalUnits : mandatoryUnits).add(unit);
        }

        for (final CodegenUnit unit : mandatoryUnits) {
            emitDecodeUnit(out, unit, indent + "        ", true);
        }

        if (hasOptional) {
            for (final Attr a : seq) {
                if (a.optional()) {
                    out.append(indent).append("        var ").append(Names.toCamelCase(a.id())).append(": ")
                            .append(kotlinType(a)).append(" = null\n");
                }
            }
            out.append(indent).append("        if (payload.size >= ").append(fullBytes).append(") {\n");
            for (final CodegenUnit unit : optionalUnits) {
                emitDecodeUnit(out, unit, indent + "            ", false);
            }
            out.append(indent).append("        }\n");
        }

        emitTrailingBytesWarning(out, indent + "        ", resultTypeName);

        final Set<String> hiddenIds = hiddenPropertyIds(seq);
        out.append(indent).append("        return ").append(resultTypeName).append("(\n");
        for (final Attr attr : seq) {
            if (hiddenIds.contains(attr.id())) {
                continue;
            }
            final String id = Names.toCamelCase(attr.id());
            out.append(indent).append("            ").append(id).append(" = ").append(id).append(",\n");
        }
        out.append(indent).append("        )\n");
        out.append(indent).append("    }\n");
        out.append(indent).append("}\n");
    }

    private static void emitVariableLengthDecodeCompanion(final StringBuilder out, final String indent, final String resultTypeName, final List<Attr> seq) {
        final Set<String> hiddenIds = hiddenPropertyIds(seq);

        out.append(indent).append("companion object {\n");
        out.append(indent).append("    private val LOG = LoggerFactory.getLogger(").append(resultTypeName).append("::class.java)\n");
        out.append("\n");
        out.append(indent).append("    fun decode(payload: ByteArray): ").append(resultTypeName).append(" {\n");
        out.append(indent).append("        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)\n");
        for (final CodegenUnit unit : group(seq)) {
            emitDecodeUnit(out, unit, indent + "        ", true);
        }
        emitTrailingBytesWarning(out, indent + "        ", resultTypeName);
        out.append(indent).append("        return ").append(resultTypeName).append("(\n");
        for (final Attr attr : seq) {
            if (hiddenIds.contains(attr.id())) {
                continue;
            }
            final String id = Names.toCamelCase(attr.id());
            out.append(indent).append("            ").append(id).append(" = ").append(id).append(",\n");
        }
        out.append(indent).append("        )\n");
        out.append(indent).append("    }\n");
        out.append(indent).append("}\n");
    }

    /**
     * A payload longer than what {@code buf} actually consumed - log the trailing bytes as a warning.
     */
    private static void emitTrailingBytesWarning(final StringBuilder out, final String indent, final String resultTypeName) {
        out.append(indent).append("if (buf.remaining() > 0) {\n");
        out.append(indent).append("    LOG.warn(\"").append(resultTypeName)
                .append(": {} unexpected trailing byte(s): {}\", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))\n");
        out.append(indent).append("}\n");
    }

    /**
     * @param declare true to introduce new {@code val}s (mandatory fields, decoded once); false to
     *                assign into {@code var}s already declared above (optional fields, inside the
     *                length-gated {@code if} block) - using {@code val} there would shadow the outer
     *                variable instead of setting it, silently leaving the result null. Packed strings
     *                can never be optional (rejected by {@link KaitaiParser}), so their length local
     *                is always {@code val} regardless of this parameter.
     */
    private static void emitDecodeUnit(final StringBuilder out, final CodegenUnit unit, final String indent, final boolean declare) {
        final String kw = declare ? "val " : "";
        if (unit.packedString()) {
            final Attr lenAttr = unit.attrs().get(0);
            final Attr strAttr = unit.attrs().get(1);
            final String lenVar = Names.toCamelCase(lenAttr.id());
            final String strVar = Names.toCamelCase(strAttr.id());
            out.append(indent).append("val ").append(lenVar).append(" = buf.get().toInt() and 0xFF\n");
            out.append(indent).append(kw).append(strVar).append(" = String(payload, buf.position(), ").append(lenVar)
                    .append(", Charsets.UTF_8).also { buf.position(buf.position() + ").append(lenVar).append(") }\n");
            return;
        }
        if (unit.repeat()) {
            final Attr a = unit.attrs().get(0);
            final String recordClassName = Names.toPascalCase(a.recordType().id());
            out.append(indent).append(kw).append(Names.toCamelCase(a.id())).append(" = buildList {\n");
            out.append(indent).append("    while (buf.hasRemaining()) {\n");
            out.append(indent).append("        add(").append(recordClassName).append(".decode(buf))\n");
            out.append(indent).append("    }\n");
            out.append(indent).append("}\n");
            return;
        }
        if (unit.switchOn()) {
            final Attr a = unit.attrs().get(0);
            final String discVar = Names.toCamelCase(a.switchOnId());
            out.append(indent).append(kw).append(Names.toCamelCase(a.id())).append(" = when (").append(discVar).append(") {\n");
            for (final var e : a.switchCases().entrySet()) {
                final String caseClass = Names.toPascalCase(e.getValue().id());
                out.append(indent).append("    ").append(e.getKey()).append(" -> ").append(caseClass).append(".decode(buf)\n");
            }
            out.append(indent).append("    else -> ").append(switchUnknownClassName(a)).append(".decode(buf)\n");
            out.append(indent).append("}\n");
            return;
        }
        if (unit.attrs().size() == 1 && unit.attrs().get(0).isReserved()) {
            final Attr a = unit.attrs().get(0);
            out.append(indent).append(kw).append(Names.toCamelCase(a.id())).append(" = ByteArray(").append(a.sizeBytes()).append(").also { buf.get(it) }\n");
            return;
        }
        if (unit.attrs().size() == 1 && !unit.attrs().get(0).isBitfield()) {
            final Attr a = unit.attrs().get(0);
            out.append(indent).append(kw).append(Names.toCamelCase(a.id())).append(" = ").append(decodeScalarExpr(a)).append("\n");
            return;
        }
        // A byte group of one or more bN fields: read the byte once, then extract each sub-field.
        final String bv = "raw" + Names.toPascalCase(unit.attrs().get(0).id());
        out.append(indent).append("val ").append(bv).append(" = buf.get().toInt() and 0xFF\n");
        int shift = 0;
        for (final Attr a : unit.attrs()) {
            final String hexString = Integer.toHexString((1 << a.bitWidth()) - 1);
            final String maskedExpr = shift == 0
                    ? bv + " and 0x" + hexString
                    : "(" + bv + " shr " + shift + ") and 0x" + hexString;
            final String valueExpr;
            if (a.enumRef() != null) {
                valueExpr = Names.toPascalCase(a.enumRef()) + ".fromValue(" + maskedExpr + ")";
            } else if (a.bitWidth() == 1) {
                valueExpr = "(" + maskedExpr + ") == 1";
            } else {
                valueExpr = maskedExpr;
            }
            out.append(indent).append(kw).append(Names.toCamelCase(a.id())).append(" = ").append(valueExpr).append("\n");
            shift += a.bitWidth();
        }
    }

    private static String decodeScalarExpr(final Attr a) {
        if (a.isString()) {
            // Only the fixed-size form reaches here; packed strings are a two-attr CodegenUnit
            // handled directly in emitDecodeUnit. Reads straight off buf (not a separate `payload`
            // ByteArray) so this also works inside a repeat:eos record's decode(buf: ByteBuffer),
            // which has no ByteArray of its own - see emitRecordClass.
            return "ByteArray(" + a.strFixedSize() + ").also { buf.get(it) }.toString(Charsets.UTF_8)";
        }
        final String raw = switch (a.kaitaiType()) {
            case "f8" -> "buf.double";
            case "f4" -> "buf.float";
            case "u1" -> "(buf.get().toInt() and 0xFF)";
            case "s1" -> "buf.get().toInt()";
            case "u2" -> "(buf.short.toInt() and 0xFFFF)";
            case "s2" -> "buf.short.toInt()";
            case "u4", "s4" -> "buf.int"; // caller treats as a raw 32-bit pattern
            case "u8", "s8" -> "buf.long";
            default -> throw new IllegalStateException("unhandled scalar type " + a.kaitaiType());
        };
        if (a.scale() != null) {
            // raw stores actual/scale (e.g. 0.1m units stored as tenths), so recovering the
            // actual value multiplies by scale - see the matching note in encodeScalarStmt.
            return "(" + raw + ") * " + a.scale() + "f";
        }
        if (a.enumRef() != null) {
            return Names.toPascalCase(a.enumRef()) + ".fromValue(" + raw + ")";
        }
        return raw;
    }

    //
    // encode
    //

    /**
     * Emits {@code [override ]fun encode(): ByteArray { ... }}.
     */
    public static void emitEncodeMethod(final StringBuilder out, final String indent, final List<Attr> seq, final boolean override) {
        if (hasVariableLength(seq)) {
            emitVariableLengthEncodeMethod(out, indent, seq, override);
            return;
        }

        final int mandatoryBytes = totalBytes(seq, false);
        final int optionalBytes = totalBytes(seq, true);
        final boolean hasOptional = optionalBytes > 0;

        out.append(indent).append(override ? "override fun encode(): ByteArray {\n" : "fun encode(): ByteArray {\n");
        if (hasOptional) {
            final List<String> optIds = seq.stream().filter(Attr::optional).map(a -> Names.toCamelCase(a.id())).toList();
            out.append(indent).append("    val optionalPresent = listOf(").append(String.join(", ", optIds)).append(").map { it != null }\n");
            out.append(indent).append("    require(optionalPresent.all { it } || optionalPresent.none { it }) {\n");
            out.append(indent).append("        \"optional tail fields must be all present or all absent\"\n");
            out.append(indent).append("    }\n");
            out.append(indent).append("    val size = if (optionalPresent[0]) ").append(mandatoryBytes + optionalBytes).append(" else ").append(mandatoryBytes).append("\n");
            out.append(indent).append("    val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)\n");
        } else {
            out.append(indent).append("    val buf = ByteBuffer.allocate(").append(mandatoryBytes).append(").order(ByteOrder.LITTLE_ENDIAN)\n");
        }

        final List<CodegenUnit> mandatoryUnits = new ArrayList<>();
        final List<CodegenUnit> optionalUnits = new ArrayList<>();
        for (final CodegenUnit unit : group(seq)) {
            (unit.attrs().get(0).optional() ? optionalUnits : mandatoryUnits).add(unit);
        }

        for (final CodegenUnit unit : mandatoryUnits) {
            emitEncodeUnit(out, unit, indent + "    ");
        }
        if (hasOptional) {
            out.append(indent).append("    if (optionalPresent[0]) {\n");
            for (final CodegenUnit unit : optionalUnits) {
                emitEncodeUnit(out, unit, indent + "        ");
            }
            out.append(indent).append("    }\n");
        }
        out.append(indent).append("    return buf.array()\n");
        out.append(indent).append("}\n");
    }

    private static void emitVariableLengthEncodeMethod(final StringBuilder out, final String indent, final List<Attr> seq, final boolean override) {
        out.append(indent).append(override ? "override fun encode(): ByteArray {\n" : "fun encode(): ByteArray {\n");

        final List<CodegenUnit> units = group(seq);
        final List<String> sizeTerms = new ArrayList<>();
        for (final CodegenUnit unit : units) {
            if (unit.packedString()) {
                final Attr strAttr = unit.attrs().get(1);
                final String strVar = Names.toCamelCase(strAttr.id());
                final String bytesVar = strVar + "Bytes";
                out.append(indent).append("    val ").append(bytesVar).append(" = ").append(strVar).append(".toByteArray(Charsets.UTF_8)\n");
                sizeTerms.add("1 + " + bytesVar + ".size");
            } else if (unit.repeat()) {
                final Attr a = unit.attrs().get(0);
                final int recordSize = totalBytes(a.recordType().seq(), false);
                sizeTerms.add(Names.toCamelCase(a.id()) + ".size * " + recordSize);
            } else if (unit.switchOn()) {
                final Attr a = unit.attrs().get(0);
                final String id = Names.toCamelCase(a.id());
                final StringBuilder sizeExpr = new StringBuilder("(when (val v = ").append(id).append(") {\n");
                for (final TypeDef caseType : new LinkedHashSet<>(a.switchCases().values())) {
                    final String caseClass = Names.toPascalCase(caseType.id());
                    final int caseSize = totalBytes(caseType.seq(), false);
                    sizeExpr.append(indent).append("        is ").append(caseClass).append(" -> ").append(caseSize).append("\n");
                }
                sizeExpr.append(indent).append("        is ").append(switchUnknownClassName(a)).append(" -> v.rawBytes.size\n");
                sizeExpr.append(indent).append("    })");
                sizeTerms.add(sizeExpr.toString());
            } else if (unit.attrs().size() == 1 && !unit.attrs().get(0).isBitfield()) {
                sizeTerms.add(String.valueOf(fixedSizeOf(unit.attrs().get(0))));
            } else {
                sizeTerms.add("1"); // one bitfield-group byte
            }
        }
        out.append(indent).append("    val buf = ByteBuffer.allocate(").append(String.join(" + ", sizeTerms)).append(").order(ByteOrder.LITTLE_ENDIAN)\n");

        for (final CodegenUnit unit : units) {
            emitEncodeUnit(out, unit, indent + "    ");
        }
        out.append(indent).append("    return buf.array()\n");
        out.append(indent).append("}\n");
    }

    private static void emitEncodeUnit(final StringBuilder out, final CodegenUnit unit, final String indent) {
        if (unit.packedString()) {
            final String bytesVar = Names.toCamelCase(unit.attrs().get(1).id()) + "Bytes";
            out.append(indent).append("buf.put(").append(bytesVar).append(".size.toByte())\n");
            out.append(indent).append("buf.put(").append(bytesVar).append(")\n");
            return;
        }
        if (unit.repeat()) {
            final String listVar = Names.toCamelCase(unit.attrs().get(0).id());
            out.append(indent).append("for (record in ").append(listVar).append(") {\n");
            out.append(indent).append("    record.encode(buf)\n");
            out.append(indent).append("}\n");
            return;
        }
        if (unit.switchOn()) {
            final Attr a = unit.attrs().get(0);
            final String id = Names.toCamelCase(a.id());
            out.append(indent).append("when (val v = ").append(id).append(") {\n");
            // Distinct by class, not by case value - two discriminant values may legitimately share
            // one case type (e.g. two commands with identical trailing shapes), which must not emit
            // the same "is X ->" branch twice.
            final Set<String> emittedClasses = new LinkedHashSet<>();
            for (final TypeDef caseType : a.switchCases().values()) {
                final String caseClass = Names.toPascalCase(caseType.id());
                if (emittedClasses.add(caseClass)) {
                    out.append(indent).append("    is ").append(caseClass).append(" -> v.encode(buf)\n");
                }
            }
            out.append(indent).append("    is ").append(switchUnknownClassName(a)).append(" -> v.encode(buf)\n");
            out.append(indent).append("}\n");
            return;
        }
        if (unit.attrs().size() == 1 && unit.attrs().get(0).isReserved()) {
            final Attr a = unit.attrs().get(0);
            final String id = Names.toCamelCase(a.id());
            // Deliberately not "id.copyOf(N)": that would silently truncate or zero-pad a wrongly-sized
            // array into a corrupted-but-valid-looking payload instead of failing loudly, undermining the
            // whole point of a byte-exact encode().
            out.append(indent).append("require(").append(id).append(".size == ").append(a.sizeBytes()).append(") {\n");
            out.append(indent).append("    \"").append(id).append(" must be ").append(a.sizeBytes())
                    .append(" bytes, got ${").append(id).append(".size}\"\n");
            out.append(indent).append("}\n");
            out.append(indent).append("buf.put(").append(id).append(")\n");
            return;
        }
        if (unit.attrs().size() == 1 && !unit.attrs().get(0).isBitfield()) {
            out.append(indent).append(encodeScalarStmt(unit.attrs().get(0))).append("\n");
            return;
        }
        out.append(indent).append("run {\n");
        out.append(indent).append("    var raw = 0\n");
        int shift = 0;
        for (final Attr a : unit.attrs()) {
            final String id = Names.toCamelCase(a.id());
            final String valueAsInt = a.enumRef() != null ? id + ".value"
                    : a.bitWidth() == 1 ? "(if (" + id + ") 1 else 0)"
                      : id;
            out.append(indent).append("    raw = raw or ((").append(valueAsInt).append(") shl ").append(shift).append(")\n");
            shift += a.bitWidth();
        }
        out.append(indent).append("    buf.put(raw.toByte())\n");
        out.append(indent).append("}\n");
    }

    private static String encodeScalarStmt(final Attr a) {
        // Bit-group and packed-string units never reach this method; it only handles byte-aligned
        // scalars and fixed-size strings. optional() fields are only called when present, so the
        // value is known non-null, but the Kotlin type is still nullable.
        final String id = Names.toCamelCase(a.id()) + (a.optional() ? "!!" : "");
        if (a.isString()) {
            return "buf.put(" + id + ".toByteArray(Charsets.UTF_8).copyOf(" + a.strFixedSize() + "))";
        }
        String value = id;
        if (a.enumRef() != null) {
            value = id + ".value";
        } else if (a.scale() != null) {
            // inverse of decodeScalarExpr's "* scale": recover the raw stored integer by dividing.
            value = "kotlin.math.round(" + id + " / " + a.scale() + "f).toInt()";
        }
        return switch (a.kaitaiType()) {
            case "f8" -> "buf.putDouble(" + id + ")";
            case "f4" -> "buf.putFloat(" + id + ")";
            case "u1", "s1" -> "buf.put((" + value + ").toByte())";
            case "u2", "s2" -> "buf.putShort((" + value + ").toShort())";
            case "u4", "s4" -> "buf.putInt(" + value + ")";
            case "u8", "s8" -> "buf.putLong(" + value + ")";
            default -> throw new IllegalStateException("unhandled scalar type " + a.kaitaiType());
        };
    }

    //
    // shared grouping / sizing
    //

    private record CodegenUnit(List<Attr> attrs, boolean packedString, boolean repeat, boolean switchOn) {
        static CodegenUnit of(final Attr a) {
            return new CodegenUnit(List.of(a), false, false, false);
        }

        static CodegenUnit bits(final List<Attr> attrs) {
            return new CodegenUnit(attrs, false, false, false);
        }

        static CodegenUnit packedString(final Attr lenAttr, final Attr strAttr) {
            return new CodegenUnit(List.of(lenAttr, strAttr), true, false, false);
        }

        static CodegenUnit repeat(final Attr a) {
            return new CodegenUnit(List.of(a), false, true, false);
        }

        static CodegenUnit switchOn(final Attr a) {
            return new CodegenUnit(List.of(a), false, false, true);
        }
    }

    static boolean hasVariableLength(final List<Attr> seq) {
        for (final Attr a : seq) {
            if ((a.isString() && a.strSizeRefId() != null) || a.isRepeat() || a.isSwitch()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The attribute ids that exist only as byte layout, never as their own Kotlin property - packed-string length prefixes.
     * {@code reserved} (bare {@code size:}) attributes are NOT hidden - they get a real {@code ByteArray}
     * property (see {@link #kotlinType}) so {@code encode(decode(bytes))} stays byte-exact even for the
     * bytes this schema doesn't otherwise understand. That property needs {@link #hasByteArrayProperty}/
     * {@link #emitEqualsAndHashCode} to stay correctly comparable, since Kotlin's default data-class
     * {@code equals()} compares array properties by reference, not content.
     */
    private static Set<String> hiddenPropertyIds(final List<Attr> seq) {
        final Set<String> ids = new HashSet<>();
        for (final Attr a : seq) {
            if (a.isString() && a.strSizeRefId() != null) {
                ids.add(a.strSizeRefId());
            }
        }
        return ids;
    }

    private static int fixedSizeOf(final Attr a) {
        return a.isString() ? a.strFixedSize() : a.sizeBytes();
    }

    /**
     * Groups a flat seq into byte-aligned units: a lone scalar/fixed-size-string, a run of bN
     * fields filling exactly one byte, or a packed-string pair (length attr + the str referencing
     * it, which {@link KaitaiParser} guarantees are adjacent).
     */
    private static List<CodegenUnit> group(final List<Attr> seq) {
        final List<CodegenUnit> units = new ArrayList<>();
        List<Attr> pendingBits = new ArrayList<>();
        int bits = 0;
        int i = 0;
        while (i < seq.size()) {
            final Attr a = seq.get(i);
            if (a.isBitfield()) {
                pendingBits.add(a);
                bits += a.bitWidth();
                if (bits == 8) {
                    units.add(CodegenUnit.bits(List.copyOf(pendingBits)));
                    pendingBits = new ArrayList<>();
                    bits = 0;
                }
                i++;
            } else if (i + 1 < seq.size() && seq.get(i + 1).isString() && a.id().equals(seq.get(i + 1).strSizeRefId())) {
                units.add(CodegenUnit.packedString(a, seq.get(i + 1)));
                i += 2;
            } else if (a.isRepeat()) {
                units.add(CodegenUnit.repeat(a));
                i++;
            } else if (a.isSwitch()) {
                units.add(CodegenUnit.switchOn(a));
                i++;
            } else {
                units.add(CodegenUnit.of(a));
                i++;
            }
        }
        return units;
    }

    private static int totalBytes(final List<Attr> seq, final boolean optional) {
        int bytes = 0;
        int bits = 0;
        for (final Attr a : seq) {
            if (a.optional() != optional) {
                continue;
            }
            if (a.isBitfield()) {
                bits += a.bitWidth();
                if (bits == 8) {
                    bytes += 1;
                    bits = 0;
                }
            } else if (a.isString()) {
                bytes += a.strFixedSize(); // only reached for fixed-size str - hasVariableLength() routes packed strings elsewhere
            } else {
                bytes += a.sizeBytes();
            }
        }
        return bytes;
    }
}
