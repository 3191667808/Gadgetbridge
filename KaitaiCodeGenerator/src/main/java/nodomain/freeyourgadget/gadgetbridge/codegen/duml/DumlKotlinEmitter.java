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
package nodomain.freeyourgadget.gadgetbridge.codegen.duml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiKotlinEmitter;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiLintException;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.EnumDef;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.TypeDef;

/**
 * Wraps {@link KaitaiKotlinEmitter}'s generic property/decode/encode output
 * in DUML's dispatch shape: a {@code sealed class} per cmdSet, one nested
 * {@code sealed class} per command family (grouped by {@code -duml.name}),
 * and a {@code data class Request}/{@code Response} leaf (named after
 * {@code -duml.direction}) extending {@code DumlCommand}, overriding its
 * abstract {@code encode()}. Everything about *how bytes become Kotlin*
 * (byte/bit layout, scaling, enums) lives in {@link KaitaiKotlinEmitter}
 * instead; this class only knows about cmdSet, cmd, direction, and the
 * decoder-map wiring.
 */
final class DumlKotlinEmitter {
    private static final String PACKAGE = "nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages";

    private DumlKotlinEmitter() {
    }

    /**
     * One .kt file's worth of source: the schema's enums, its cmdSet sealed class, and its decoder map.
     */
    static String emitSchemaFile(final DumlSchema schema) {
        final StringBuilder out = new StringBuilder();
        out.append(header(schema.generic().fileId()));
        out.append("package ").append(PACKAGE).append("\n\n");
        for (final String imp : KaitaiKotlinEmitter.REQUIRED_IMPORTS) {
            out.append("import ").append(imp).append("\n");
        }
        out.append("import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacketType\n\n");

        for (final EnumDef enumDef : schema.generic().enums().values()) {
            KaitaiKotlinEmitter.emitEnum(out, enumDef);
        }

        emitCmdSetClass(out, schema);
        emitDecoderMap(out, schema);
        return out.toString();
    }

    /**
     * The small aggregator file combining every schema file's decoder map by cmdSet.
     */
    static String emitAggregator(final List<DumlSchema> schemas) {
        final StringBuilder out = new StringBuilder();
        out.append(header("<generator>"));
        out.append("package ").append(PACKAGE).append("\n\n");
        out.append("import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacketType\n\n");
        out.append("internal val GENERATED_DUML_DECODERS: Map<Int, Map<Pair<Int, DumlPacketType>, (ByteArray) -> DumlCommand>> = mapOf(\n");
        for (final DumlSchema schema : schemas) {
            out.append("    0x").append(Integer.toHexString(schema.cmdSet())).append(" to ").append(decodersConstName(schema)).append(",\n");
        }
        out.append(")\n");
        return out.toString();
    }

    private static String decodersConstName(final DumlSchema schema) {
        return schema.kotlinClassName().toUpperCase(Locale.ROOT) + "_DECODERS";
    }

    private static String header(final String sourceId) {
        return """
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
                // GENERATED CODE - source: %s
                // Do not edit by hand; edit the .ksy schema and regenerate (:DumlCodeGenerator:genKaitai).
                """.formatted(sourceId);
    }

    //
    // cmdSet sealed class
    //

    private static void emitCmdSetClass(final StringBuilder out, final DumlSchema schema) {
        final String cls = schema.kotlinClassName();
        KaitaiKotlinEmitter.emitKDoc(out, "", schema.generic().doc(), List.of());
        out.append("sealed class ").append(cls).append("(cmd: Int) : DumlCommand(CMD_SET, cmd) {\n");
        out.append("    companion object {\n");
        out.append("        const val CMD_SET = 0x").append(Integer.toHexString(schema.cmdSet())).append("\n");
        out.append("    }\n\n");

        for (final var family : groupByFamily(schema).entrySet()) {
            emitFamily(out, cls, family.getKey(), family.getValue());
        }
        out.append("}\n\n");
    }

    private record FamilyMember(TypeDef type, DumlSchema.TypeMeta meta) {
    }

    /**
     * {@code -duml.name} groups one or more types (request and/or response) sharing one cmd id.
     */
    private static Map<String, List<FamilyMember>> groupByFamily(final DumlSchema schema) {
        final Map<String, List<FamilyMember>> byName = new LinkedHashMap<>();
        for (final TypeDef type : schema.generic().types().values()) {
            final DumlSchema.TypeMeta meta = schema.typeMetaByTypeId().get(type.id());
            byName.computeIfAbsent(meta.name(), k -> new ArrayList<>()).add(new FamilyMember(type, meta));
        }
        for (final var e : byName.entrySet()) {
            final int cmd = e.getValue().getFirst().meta().cmd();
            for (final FamilyMember m : e.getValue()) {
                if (m.meta().cmd() != cmd) {
                    throw new KaitaiLintException(schema.generic().fileId(), "types", "family \"" + e.getKey()
                            + "\" has types disagreeing on cmd (0x%02x vs 0x%02x)".formatted(cmd, m.meta().cmd()));
                }
            }
        }
        return byName;
    }

    private static void emitFamily(final StringBuilder out, final String enclosingClass, final String familyName, final List<FamilyMember> members) {
        final int cmd = members.getFirst().meta().cmd();
        out.append("    sealed class ").append(familyName).append(" : ").append(enclosingClass).append("(CMD) {\n");
        out.append("        companion object {\n");
        out.append("            const val CMD = 0x").append(Integer.toHexString(cmd)).append("\n");
        out.append("        }\n\n");
        for (final FamilyMember m : members) {
            emitLeaf(out, familyName, m);
        }
        out.append("    }\n\n");
    }

    /**
     * "response" -&gt; Response; "request" or "both" -&gt; Request (the shared-shape convention "both" opts into).
     */
    private static String leafName(final String direction) {
        return "response".equals(direction) ? "Response" : "Request";
    }

    private static void emitLeaf(final StringBuilder out, final String familyName, final FamilyMember member) {
        final String leafName = leafName(member.meta().direction());
        for (final var attr : member.type().seq()) {
            if (attr.isRepeat()) {
                KaitaiKotlinEmitter.emitRecordClass(out, "        ", attr.recordType(), null);
            }
            if (attr.isSwitch()) {
                KaitaiKotlinEmitter.emitSwitchTypes(out, "        ", attr);
            }
        }
        KaitaiKotlinEmitter.emitKDoc(out, "        ", member.type().doc(), member.type().seq());
        out.append("        data class ").append(leafName).append("(\n");
        KaitaiKotlinEmitter.emitProperties(out, "            ", member.type().seq());
        out.append("        ) : ").append(familyName).append("() {\n");

        KaitaiKotlinEmitter.emitDecodeCompanion(out, "            ", leafName, member.type().seq());
        out.append("\n");
        KaitaiKotlinEmitter.emitEncodeMethod(out, "            ", member.type().seq(), true);

        if (KaitaiKotlinEmitter.hasByteArrayProperty(member.type().seq())) {
            out.append("\n");
            KaitaiKotlinEmitter.emitEqualsAndHashCode(out, "            ", leafName, member.type().seq());
        }

        out.append("        }\n\n");
    }

    //
    // decoder map for this schema file
    //

    private static void emitDecoderMap(final StringBuilder out, final DumlSchema schema) {
        out.append("internal val ").append(decodersConstName(schema))
                .append(": Map<Pair<Int, DumlPacketType>, (ByteArray) -> DumlCommand> = mapOf(\n");
        for (final TypeDef type : schema.generic().types().values()) {
            final DumlSchema.TypeMeta meta = schema.typeMetaByTypeId().get(type.id());
            final String leafName = leafName(meta.direction());
            final String decodeCall = "{ p -> " + schema.kotlinClassName() + "." + meta.name() + "." + leafName + ".decode(p) }";
            final List<String> packetTypes = "both".equals(meta.direction())
                    ? List.of("DumlPacketType.REQUEST", "DumlPacketType.RESPONSE")
                    : List.of("request".equals(meta.direction()) ? "DumlPacketType.REQUEST" : "DumlPacketType.RESPONSE");
            for (final String packetType : packetTypes) {
                out.append("    (0x").append(Integer.toHexString(meta.cmd())).append(" to ").append(packetType).append(") to ")
                        .append(decodeCall).append(",\n");
            }
        }
        out.append(")\n");
    }
}
