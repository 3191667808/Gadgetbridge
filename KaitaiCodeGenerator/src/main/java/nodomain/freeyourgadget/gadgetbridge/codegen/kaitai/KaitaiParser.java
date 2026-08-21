/*  Copyright (C) 2026 Freeyourgadget

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
package nodomain.freeyourgadget.gadgetbridge.codegen.kaitai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Attr;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.EnumDef;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Meta;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Schema;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.TypeDef;

import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.asList;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.asMap;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.asRawMap;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.asString;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.requireKeys;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.toLong;

/**
 * Turns a raw SnakeYAML document into a {@link Schema}, rejecting anything
 * outside a deliberately small subset of Kaitai Struct.
 * <p>
 * This class knows nothing about any specific protocol - a caller that wants to
 * bolt its own extension keys onto the same {@code .ksy} files (e.g. a
 * {@code -duml} block naming which command each type decodes) is expected
 * to read and strip those keys out of the raw map itself before calling
 * {@link #parse}, so that this parser's strict allow-lists never need to
 * know about them. See {@code service.devices.dji.duml.codegen.DumlSchemaParser}
 * for that pattern in practice.
 * <p>
 * Every recognised YAML mapping is walked key-by-key against an explicit
 * allow-list; an unrecognized key fails with a {@link KaitaiLintException}
 * rather than being silently ignored.
 * <p>
 * Bitfields are native {@code bN} attributes (see {@link KaitaiSchema}).
 * While walking a type's {@code seq}, this parser tracks how many bits of
 * the current byte have been consumed so far and rejects any {@code bN}
 * field that would cross a byte boundary, and any byte-aligned field or
 * end-of-type that leaves a byte partially consumed.
 *
 * <p>Trailing optional fields use native {@code if: _io.size > N} - the one
 * narrow form of Kaitai's expression language this subset allows, since it
 * is the standard idiom for the "trailing struct present only in newer firmware"
 * shape and does not need a general expression evaluator.
 */
public final class KaitaiParser {
    private static final Pattern BIT_TYPE = Pattern.compile("b([1-8])");
    private static final Pattern IF_SIZE_GT = Pattern.compile("_io\\.size > (\\d+)");

    private KaitaiParser() {
    }

    public static Schema parse(final String fileName, final Map<String, Object> root) {
        requireKeys(fileName, "<root>", root, Set.of("meta", "doc", "enums", "types"), Set.of("meta", "types"));
        final String schemaDoc = root.containsKey("doc") ? asString(fileName, "doc", root.get("doc")) : "";

        final Map<String, Object> metaRaw = asMap(fileName, "meta", root.get("meta"));
        requireKeys(fileName, "meta", metaRaw, Set.of("id", "title", "endian", "bit-endian"), Set.of("id", "endian"));

        final String fileId = asString(fileName, "meta.id", metaRaw.get("id"));
        final String endian = asString(fileName, "meta.endian", metaRaw.get("endian"));
        if (!"le".equals(endian)) {
            throw new KaitaiLintException(fileName, "meta.endian",
                    "only little-endian (\"le\") payloads are supported, got \"" + endian + "\"");
        }
        final String bitEndian = metaRaw.containsKey("bit-endian") ? asString(fileName, "meta.bit-endian", metaRaw.get("bit-endian")) : null;
        if (bitEndian != null && !"le".equals(bitEndian)) {
            throw new KaitaiLintException(fileName, "meta.bit-endian", "only \"le\" is supported");
        }
        final Meta meta = new Meta(fileId, endian, bitEndian);

        final LinkedHashMap<String, EnumDef> enums = new LinkedHashMap<>();
        if (root.containsKey("enums")) {
            final Map<String, Object> enumsRaw = asMap(fileName, "enums", root.get("enums"));
            for (final var e : enumsRaw.entrySet()) {
                enums.put(e.getKey(), parseEnum(fileName, e.getKey(), e.getValue()));
            }
        }

        final Map<String, Object> typesRaw = asMap(fileName, "types", root.get("types"));
        boolean anyBitfield = false;
        final LinkedHashMap<String, TypeDef> types = new LinkedHashMap<>();
        for (final var e : typesRaw.entrySet()) {
            final TypeDef type = parseType(fileName, e.getKey(), asMap(fileName, "types." + e.getKey(), e.getValue()), enums);
            types.put(e.getKey(), type);
            anyBitfield |= hasBitfield(type.seq());
        }
        if (anyBitfield && !"le".equals(bitEndian)) {
            throw new KaitaiLintException(fileName, "meta.bit-endian", "required (\"le\") when any type uses a bN field");
        }

        return new Schema(fileId, schemaDoc, meta, enums, types);
    }

    /**
     * Recurses into a {@code repeat: eos} attribute's {@code recordType}, and a {@code switch-on}
     * attribute's {@code switchCases} - a nested record's or case's own {@code bN} fields wouldn't
     * otherwise be seen by the top-level {@code anyBitfield} scan in {@link #parse}, since the outer
     * type's own {@code seq} only holds the one repeat/switch {@link Attr}.
     */
    private static boolean hasBitfield(final List<Attr> seq) {
        for (final Attr a : seq) {
            if (a.isBitfield()) {
                return true;
            }
            if (a.isRepeat() && hasBitfield(a.recordType().seq())) {
                return true;
            }
            if (a.isSwitch()) {
                for (final TypeDef caseType : a.switchCases().values()) {
                    if (hasBitfield(caseType.seq())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static EnumDef parseEnum(final String fileName, final String enumId, final Object raw) {
        if (!Names.isSnakeCase(enumId)) {
            throw new KaitaiLintException(fileName, "enums." + enumId, "enum id must be snake_case");
        }

        final Map<Object, Object> valuesRaw = asRawMap(fileName, "enums." + enumId, raw);
        final LinkedHashMap<Long, String> values = new LinkedHashMap<>();
        for (final var e : valuesRaw.entrySet()) {
            final long key = toLong(fileName, "enums." + enumId, e.getKey());
            final String name = asString(fileName, "enums." + enumId, e.getValue());
            if (!Names.isSnakeCase(name)) {
                throw new KaitaiLintException(fileName, "enums." + enumId, "value name must be snake_case: \"" + name + "\"");
            }
            if (values.containsKey(key)) {
                throw new KaitaiLintException(fileName, "enums." + enumId, "duplicate value 0x" + Long.toHexString(key));
            }
            values.put(key, name);
        }
        return new EnumDef(enumId, values);
    }

    private static TypeDef parseType(
            final String fileName,
            final String typeId,
            final Map<String, Object> body,
            final Map<String, EnumDef> enums
    ) {
        return parseType(fileName, typeId, "types." + typeId, body, enums, true);
    }

    /**
     * @param where                   the dotted path to this type's body, for error messages - "types.m" for a
     *                                top-level type, "types.m.types.entry" for a nested record type declared
     *                                under it, etc. Threaded explicitly (rather than recomputed from typeId)
     *                                so a nested record type's own errors point at its real nesting location.
     * @param allowVariableConstructs false when parsing a {@code repeat: eos} attribute's nested record
     *                                type: forbids that record from itself declaring {@code types:} (only
     *                                one level of nesting), an {@code if:}-gated tail, a packed string, or
     *                                a {@code repeat} - a record read in a loop must be fully fixed-size.
     */
    private static TypeDef parseType(
            final String fileName,
            final String typeId,
            final String where,
            final Map<String, Object> body,
            final Map<String, EnumDef> enums,
            final boolean allowVariableConstructs
    ) {
        final Set<String> allowedKeys = allowVariableConstructs ? Set.of("doc", "seq", "types") : Set.of("doc", "seq");
        requireKeys(fileName, where, body, allowedKeys, Set.of("seq"));
        final String doc = body.containsKey("doc") ? asString(fileName, where + ".doc", body.get("doc")) : "";

        final Map<String, TypeDef> nestedTypes;
        if (allowVariableConstructs && body.containsKey("types")) {
            final Map<String, Object> nestedRaw = asMap(fileName, where + ".types", body.get("types"));
            final LinkedHashMap<String, TypeDef> parsed = new LinkedHashMap<>();
            for (final var e : nestedRaw.entrySet()) {
                final String nestedId = e.getKey();
                if (!Names.isSnakeCase(nestedId)) {
                    throw new KaitaiLintException(fileName, where + ".types." + nestedId, "type id must be snake_case");
                }
                final String nestedWhere = where + ".types." + nestedId;
                final TypeDef nestedType = parseType(fileName, nestedId, nestedWhere,
                        asMap(fileName, nestedWhere, e.getValue()), enums, false);
                parsed.put(nestedId, nestedType);
            }
            nestedTypes = parsed;
        } else {
            nestedTypes = Map.of();
        }

        final List<Object> seqRaw = asList(fileName, where + ".seq", body.get("seq"));
        final List<Attr> seq = new ArrayList<>();
        final Set<String> seenIds = new java.util.HashSet<>();
        int bitsIntoCurrentByte = 0;
        int bytesSoFar = 0; // mandatory fixed-size bytes consumed before the current attribute
        Integer optionalThreshold = null; // once the trailing optional run starts, its shared "if: _io.size > N" bound
        boolean sawVariableLength = false; // true once a packed string (size: <ref>), a repeat:eos, or a switch-on attribute has been consumed
        boolean sawRepeat = false;
        boolean sawSwitch = false;
        for (final Object attrObj : seqRaw) {
            if (sawRepeat) {
                throw new KaitaiLintException(fileName, where + ".seq", "a \"repeat: eos\" attribute must be the last attribute in seq");
            }
            if (sawSwitch) {
                throw new KaitaiLintException(fileName, where + ".seq", "a \"switch-on\" attribute must be the last attribute in seq");
            }
            final Map<String, Object> attrRaw = asMap(fileName, where + ".seq[]", attrObj);
            final Attr attr = parseAttr(fileName, where, attrRaw, enums, seq, bytesSoFar, optionalThreshold, nestedTypes, allowVariableConstructs);
            if (!seenIds.add(attr.id())) {
                throw new KaitaiLintException(fileName, where + ".seq", "duplicate attribute id \"" + attr.id() + "\"");
            }
            if (attr.isRepeat()) {
                if (bitsIntoCurrentByte != 0) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            bitsIntoCurrentByte + " bits declared so far do not fill a whole byte before a \"repeat: eos\" attribute");
                }
                if (optionalThreshold != null) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            "a \"repeat: eos\" attribute cannot follow an \"if\"-gated optional run");
                }
                if (sawVariableLength) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            "a \"repeat: eos\" attribute cannot follow a packed string - only one variable-length construct is allowed per type");
                }
                sawVariableLength = true;
                sawRepeat = true;
                seq.add(attr);
                continue;
            }
            if (attr.isSwitch()) {
                if (bitsIntoCurrentByte != 0) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            bitsIntoCurrentByte + " bits declared so far do not fill a whole byte before a \"switch-on\" attribute");
                }
                if (optionalThreshold != null) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            "a \"switch-on\" attribute cannot follow an \"if\"-gated optional run");
                }
                if (sawVariableLength) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            "a \"switch-on\" attribute cannot follow a packed string or \"repeat: eos\" - only one variable-length "
                                    + "construct is allowed per type");
                }
                sawVariableLength = true;
                sawSwitch = true;
                seq.add(attr);
                continue;
            }
            if (attr.optional() && sawVariableLength) {
                throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                        "an \"if\"-gated optional tail cannot follow a packed string - the tail's byte-size threshold "
                                + "can't be computed once a variable-length field precedes it");
            }
            if (attr.optional() && optionalThreshold == null) {
                optionalThreshold = bytesSoFar;
            } else if (!attr.optional() && optionalThreshold != null) {
                throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                        "is mandatory but follows an \"if\"-gated field; optional fields must be a contiguous trailing run");
            }
            if (attr.isString() && attr.strSizeRefId() != null) {
                sawVariableLength = true;
            }
            if (attr.isBitfield()) {
                if (bitsIntoCurrentByte + attr.bitWidth() > 8) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            "crosses a byte boundary (" + bitsIntoCurrentByte + " bits already consumed in this byte, field is "
                                    + attr.bitWidth() + " bits) - not allowed in this subset, split across named fields differently");
                }
                bitsIntoCurrentByte = (bitsIntoCurrentByte + attr.bitWidth()) % 8;
                if (bitsIntoCurrentByte == 0) {
                    bytesSoFar += 1; // bN fields can't be optional (checked in parseAttr), so this byte is always mandatory
                }
            } else {
                if (bitsIntoCurrentByte != 0) {
                    throw new KaitaiLintException(fileName, where + ".seq." + attr.id(),
                            bitsIntoCurrentByte + " bits declared so far do not fill a whole byte; a real Kaitai reader would "
                                    + "silently discard the remainder when byte-aligning here. Add the missing bits explicitly.");
                }
                // Packed strings (strFixedSize == -1) don't have a fixed byte count; bytesSoFar past this
                // point is only ever read to validate a later "if:" threshold, which is itself rejected
                // once sawVariableLength is set, so leaving it un-incremented here is safe, not silently wrong.
                if (!attr.optional() && !(attr.isString() && attr.strFixedSize() < 0)) {
                    bytesSoFar += attr.isString() ? attr.strFixedSize() : attr.sizeBytes();
                }
            }
            seq.add(attr);
        }
        if (bitsIntoCurrentByte != 0) {
            throw new KaitaiLintException(fileName, where + ".seq",
                    bitsIntoCurrentByte + " bits declared at the end of the type do not fill a whole byte");
        }
        if (seq.isEmpty()) {
            throw new KaitaiLintException(fileName, where + ".seq", "must declare at least one attribute");
        }

        return new TypeDef(typeId, doc, List.copyOf(seq));
    }

    private static Attr parseAttr(
            final String fileName,
            final String typeWhere,
            final Map<String, Object> raw,
            final Map<String, EnumDef> enums,
            final List<Attr> precedingInType,
            final int bytesSoFar,
            final Integer optionalThreshold,
            final Map<String, TypeDef> nestedTypes,
            final boolean allowVariableConstructs
    ) {
        final String id = asString(fileName, typeWhere + ".seq[]", raw.get("id"));
        final String where = typeWhere + ".seq." + id;
        requireKeys(fileName, where, raw,
                Set.of("id", "type", "size", "enum", "doc", "encoding", "-scale", "if", "repeat"),
                Set.of("id"));
        if (!Names.isSnakeCase(id)) {
            throw new KaitaiLintException(fileName, where, "attribute id must be snake_case");
        }
        final String doc = raw.containsKey("doc") ? asString(fileName, where + ".doc", raw.get("doc")) : "";

        final Object typeRaw = raw.get("type");
        if (typeRaw == null) {
            return parseReservedAttr(fileName, where, id, doc, raw);
        }
        if (typeRaw instanceof Map) {
            return parseSwitchAttr(fileName, where, id, doc, raw, nestedTypes, precedingInType, allowVariableConstructs);
        }
        final String type = asString(fileName, where + ".type", typeRaw);

        if (raw.containsKey("repeat")) {
            if (!allowVariableConstructs) {
                throw new KaitaiLintException(fileName, where,
                        "\"repeat\" is not valid inside a nested record type (must be fully fixed-size, no further nesting)");
            }
            final String repeatVal = asString(fileName, where + ".repeat", raw.get("repeat"));
            if (!"eos".equals(repeatVal)) {
                throw new KaitaiLintException(fileName, where + ".repeat", "only \"eos\" is supported, got \"" + repeatVal + "\"");
            }
            if (raw.containsKey("if") || raw.containsKey("size") || raw.containsKey("enum")
                    || raw.containsKey("encoding") || raw.containsKey("-scale")) {
                throw new KaitaiLintException(fileName, where,
                        "\"if\"/\"size\"/\"enum\"/\"encoding\"/\"-scale\" are not valid on a \"repeat: eos\" attribute");
            }
            final TypeDef recordType = nestedTypes.get(type);
            if (recordType == null) {
                throw new KaitaiLintException(fileName, where + ".type",
                        "unknown nested record type \"" + type + "\" (must be declared under this type's own \"types:\")");
            }
            return new Attr(id, "record", 0, 0, null, -1, null, null, false, doc, recordType, true, null, null);
        }
        if (nestedTypes.containsKey(type)) {
            throw new KaitaiLintException(fileName, where, "attribute references nested record type \"" + type + "\" but is missing \"repeat: eos\"");
        }

        final boolean optional;
        if (raw.containsKey("if")) {
            if (!allowVariableConstructs) {
                throw new KaitaiLintException(fileName, where, "\"if\" is not valid inside a nested record type (must be fully fixed-size)");
            }
            final String cond = asString(fileName, where + ".if", raw.get("if"));
            final var m = IF_SIZE_GT.matcher(cond);
            if (!m.matches()) {
                throw new KaitaiLintException(fileName, where + ".if",
                        "only the form \"_io.size > N\" is supported (a trailing run gated on total payload size), got \"" + cond + "\"");
            }
            final int threshold = Integer.parseInt(m.group(1));
            final int expected = optionalThreshold != null ? optionalThreshold : bytesSoFar;
            if (threshold != expected) {
                throw new KaitaiLintException(fileName, where + ".if",
                        "expected \"_io.size > " + expected + "\" (every field in a trailing optional run shares the same "
                                + "threshold - the mandatory byte count before the run), got \"" + cond + "\"");
            }
            optional = true;
        } else {
            optional = false;
        }
        final String enumRef = raw.containsKey("enum") ? asString(fileName, where + ".enum", raw.get("enum")) : null;
        if (enumRef != null && !enums.containsKey(enumRef)) {
            throw new KaitaiLintException(fileName, where + ".enum", "unknown enum \"" + enumRef + "\"");
        }

        final var bitMatcher = BIT_TYPE.matcher(type);
        if (bitMatcher.matches()) {
            if (optional) {
                throw new KaitaiLintException(fileName, where, "\"if\" is not valid on a bN field; only whole trailing bytes may be optional");
            }
            if (raw.containsKey("-scale")) {
                throw new KaitaiLintException(fileName, where, "\"-scale\" is not valid on a bN field");
            }
            final int width = Integer.parseInt(bitMatcher.group(1));
            return new Attr(id, type, 0, width, null, -1, enumRef, null, false, doc, null, false, null, null);
        }

        if ("str".equals(type)) {
            if (raw.containsKey("-scale") || enumRef != null) {
                throw new KaitaiLintException(fileName, where, "type \"str\" cannot have \"-scale\" or \"enum\"");
            }
            if (optional) {
                throw new KaitaiLintException(fileName, where, "\"if\" is not valid on a str field in this subset");
            }
            final String encoding = raw.containsKey("encoding") ? asString(fileName, where + ".encoding", raw.get("encoding")) : "UTF-8";
            if (!"UTF-8".equals(encoding)) {
                throw new KaitaiLintException(fileName, where + ".encoding", "only UTF-8 is supported");
            }
            final Object sizeObj = raw.get("size");
            if (sizeObj == null) {
                throw new KaitaiLintException(fileName, where, "type \"str\" requires \"size\"");
            }
            if (sizeObj instanceof Number n) {
                return new Attr(id, "str", 0, 0, null, n.intValue(), null, null, optional, doc, null, false, null, null);
            }
            if (!allowVariableConstructs) {
                throw new KaitaiLintException(fileName, where,
                        "a packed string (\"size\" referencing another field) is not valid inside a nested record type");
            }
            // Packed-string form: size references the immediately preceding u1 length field.
            final String sizeRef = asString(fileName, where + ".size", sizeObj);
            if (precedingInType.isEmpty() || !precedingInType.getLast().id().equals(sizeRef)
                    || !"u1".equals(precedingInType.getLast().kaitaiType())) {
                throw new KaitaiLintException(fileName, where + ".size",
                        "as a field reference, \"size\" must name the immediately preceding u1 attribute (packed-string form)");
            }
            return new Attr(id, "str", 0, 0, sizeRef, -1, null, null, optional, doc, null, false, null, null);
        }

        final Integer sizeBytes = KaitaiSchema.SCALAR_SIZES.get(type);
        if (sizeBytes == null) {
            throw new KaitaiLintException(fileName, where + ".type",
                    "unsupported type \"" + type + "\" (allowed: u1 u2 u4 u8 s1 s2 s4 s8 f4 f8 str b1..b8, "
                            + "or a nested record type declared under this type's \"types:\" with \"repeat: eos\")");
        }
        if (raw.containsKey("size")) {
            throw new KaitaiLintException(fileName, where, "\"size\" is only valid for type \"str\"");
        }

        Double scale = null;
        if (raw.containsKey("-scale")) {
            if (type.charAt(0) == 'f') {
                throw new KaitaiLintException(fileName, where + ".-scale", "not valid on a floating-point field");
            }
            scale = ((Number) raw.get("-scale")).doubleValue();
            if (scale <= 0) {
                throw new KaitaiLintException(fileName, where + ".-scale", "must be positive");
            }
        }

        return new Attr(id, type, sizeBytes, 0, null, -1, enumRef, scale, optional, doc, null, false, null, null);
    }

    /**
     * Parses a raw-bytes attribute: no {@code type} key at all, just {@code size: N} - the native
     * Kaitai idiom for "N bytes here whose structure isn't modeled." Unlike every other attribute
     * shape, this one is never exposed as a Kotlin property (see {@code KaitaiKotlinEmitter}'s
     * hidden-property handling): naming an unstructured span as, say, a {@code ByteArray} property
     * would need a hand-written {@code equals()}/{@code hashCode()} on every generated {@code data
     * class} that used one (Kotlin's default data-class equality is reference-based for array
     * properties), for a value nothing in this codebase actually reads back. decode() skips the
     * bytes; encode() writes zeros - see the field's own {@code doc:} in the {@code .ksy} file for
     * why that tradeoff is safe for a given use.
     */
    private static Attr parseReservedAttr(final String fileName, final String where, final String id, final String doc, final Map<String, Object> raw) {
        if (raw.containsKey("enum") || raw.containsKey("encoding") || raw.containsKey("-scale")
                || raw.containsKey("if") || raw.containsKey("repeat")) {
            throw new KaitaiLintException(fileName, where,
                    "\"enum\"/\"encoding\"/\"-scale\"/\"if\"/\"repeat\" are not valid on a raw-bytes attribute (\"type\" omitted)");
        }
        final Object sizeObj = raw.get("size");
        if (!(sizeObj instanceof Number n) || n.intValue() <= 0) {
            throw new KaitaiLintException(fileName, where + ".size",
                    "a raw-bytes attribute (\"type\" omitted) requires a positive integer \"size\"");
        }
        return new Attr(id, "reserved", n.intValue(), 0, null, -1, null, null, false, doc, null, false, null, null);
    }

    /**
     * Parses {@code type: {switch-on: <preceding attr id>, cases: {value: nested_type_id, ...}}} - a
     * discriminant-based union, e.g. a leading {@code cmd_type} byte selecting one of several trailing
     * shapes. Deliberately narrower than real Kaitai Struct's switch-on: no default/{@code _} case (a
     * discriminant value with no matching case simply dissects nothing further, matching how the DUML
     * Lua dissectors this models behave when a {@code cmd_type}/{@code phase} branch is unhandled), and
     * every case's nested type follows the exact same restricted, fully-fixed-size subset a
     * {@code repeat: eos} record does (see the {@code allowVariableConstructs} parameter on
     * {@link #parseType(String, String, String, Map, Map, boolean)} - case types reuse that same path).
     */
    private static Attr parseSwitchAttr(
            final String fileName,
            final String where,
            final String id,
            final String doc,
            final Map<String, Object> raw,
            final Map<String, TypeDef> nestedTypes,
            final List<Attr> precedingInType,
            final boolean allowVariableConstructs
    ) {
        if (!allowVariableConstructs) {
            throw new KaitaiLintException(fileName, where,
                    "\"switch-on\" is not valid inside a nested record type (must be fully fixed-size, no further nesting)");
        }
        if (raw.containsKey("if") || raw.containsKey("size") || raw.containsKey("enum")
                || raw.containsKey("encoding") || raw.containsKey("-scale") || raw.containsKey("repeat")) {
            throw new KaitaiLintException(fileName, where,
                    "\"if\"/\"size\"/\"enum\"/\"encoding\"/\"-scale\"/\"repeat\" are not valid on a \"switch-on\" attribute");
        }

        final Map<String, Object> typeMap = asMap(fileName, where + ".type", raw.get("type"));
        requireKeys(fileName, where + ".type", typeMap, Set.of("switch-on", "cases"), Set.of("switch-on", "cases"));

        final String switchOnId = asString(fileName, where + ".type.switch-on", typeMap.get("switch-on"));
        if (precedingInType.isEmpty() || !precedingInType.getLast().id().equals(switchOnId)) {
            throw new KaitaiLintException(fileName, where + ".type.switch-on",
                    "must name the immediately preceding attribute in seq, got \"" + switchOnId + "\"");
        }
        final Attr discriminant = precedingInType.getLast();
        if (discriminant.isBitfield() || discriminant.isString() || discriminant.isRepeat() || discriminant.isSwitch()
                || !Set.of("u1", "u2", "u4", "u8").contains(discriminant.kaitaiType())) {
            throw new KaitaiLintException(fileName, where + ".type.switch-on",
                    "\"" + switchOnId + "\" must be an unsigned integer scalar (u1/u2/u4/u8) to be used as a switch discriminant");
        }

        final Map<Object, Object> casesRaw = asRawMap(fileName, where + ".type.cases", typeMap.get("cases"));
        if (casesRaw.isEmpty()) {
            throw new KaitaiLintException(fileName, where + ".type.cases", "must declare at least one case");
        }
        final LinkedHashMap<Long, TypeDef> cases = new LinkedHashMap<>();
        for (final var e : casesRaw.entrySet()) {
            final long caseValue = toLong(fileName, where + ".type.cases", e.getKey());
            if (cases.containsKey(caseValue)) {
                throw new KaitaiLintException(fileName, where + ".type.cases", "duplicate case value " + caseValue);
            }
            final String caseTypeId = asString(fileName, where + ".type.cases." + caseValue, e.getValue());
            final TypeDef caseType = nestedTypes.get(caseTypeId);
            if (caseType == null) {
                throw new KaitaiLintException(fileName, where + ".type.cases." + caseValue,
                        "unknown nested record type \"" + caseTypeId + "\" (must be declared under this type's own \"types:\")");
            }
            cases.put(caseValue, caseType);
        }

        return new Attr(id, "switch", 0, 0, null, -1, null, null, false, doc, null, false, switchOnId, cases);
    }
}
