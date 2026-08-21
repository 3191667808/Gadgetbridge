package nodomain.freeyourgadget.gadgetbridge.codegen.kaitai;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Attr;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.EnumDef;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Schema;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.TypeDef;

/**
 * Whole-schema checks that {@link KaitaiParser} can't do while building one
 * {@link Schema} - here, that every enum and every type produces a distinct
 * Kotlin identifier once PascalCased, since enums and types share one Kotlin
 * file's top-level namespace. A {@code repeat: eos} attribute's nested record
 * type, and a {@code switch-on} attribute's sealed interface, each of its case
 * types, and its {@code Unknown} fallback class, all become their own
 * top-level-ish Kotlin declaration too (see {@code KaitaiKotlinEmitter
 * #emitRecordClass}/{@code #emitSwitchTypes}), so each claims a name in the
 * same namespace even though none of them appear in {@link Schema#types()}.
 */
public final class KaitaiLinter {
    private KaitaiLinter() {
    }

    public static void lint(final String fileName, final Schema schema) {
        final Map<String, String> byKotlinName = new HashMap<>(); // PascalCase name -> "enums.x" / "types.y"
        for (final EnumDef enumDef : schema.enums().values()) {
            claim(fileName, byKotlinName, Names.toPascalCase(enumDef.id()), "enums." + enumDef.id());
        }
        for (final TypeDef type : schema.types().values()) {
            claim(fileName, byKotlinName, Names.toPascalCase(type.id()), "types." + type.id());
            claimNestedRecordTypes(fileName, byKotlinName, type.seq(), "types." + type.id());
        }
    }

    private static void claimNestedRecordTypes(final String fileName, final Map<String, String> byKotlinName, final List<Attr> seq, final String where) {
        for (final Attr attr : seq) {
            if (attr.isRepeat()) {
                final TypeDef record = attr.recordType();
                claim(fileName, byKotlinName, Names.toPascalCase(record.id()), where + ".seq." + attr.id());
            }
            if (attr.isSwitch()) {
                // The sealed interface and its Unknown fallback (see KaitaiKotlinEmitter#emitSwitchTypes)
                // claim names too, alongside each case type.
                claim(fileName, byKotlinName, KaitaiKotlinEmitter.switchInterfaceName(attr), where + ".seq." + attr.id());
                claim(fileName, byKotlinName, KaitaiKotlinEmitter.switchUnknownClassName(attr), where + ".seq." + attr.id());
                // A single case type reused across multiple discriminant values (see
                // KaitaiKotlinEmitter's switch-on emission) claims its Kotlin name once, not once
                // per case value that references it.
                final Set<TypeDef> claimed = new LinkedHashSet<>();
                for (final TypeDef caseType : attr.switchCases().values()) {
                    if (claimed.add(caseType)) {
                        claim(fileName, byKotlinName, Names.toPascalCase(caseType.id()), where + ".seq." + attr.id() + ".cases." + caseType.id());
                    }
                }
            }
        }
    }

    private static void claim(final String fileName, final Map<String, String> byKotlinName, final String kotlinName, final String label) {
        final String existing = byKotlinName.putIfAbsent(kotlinName, label);
        if (existing != null) {
            throw new KaitaiLintException(
                    fileName,
                    label,
                    "produces Kotlin identifier \"" + kotlinName + "\", which \"" + existing + "\" already uses"
            );
        }
    }
}
