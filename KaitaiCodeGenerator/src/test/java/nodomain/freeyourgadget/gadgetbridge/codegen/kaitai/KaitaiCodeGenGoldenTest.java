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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Attr;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Schema;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.TypeDef;

import static org.junit.Assert.assertEquals;

public class KaitaiCodeGenGoldenTest {
    /**
     * Base names with both a kaitai/<name>.ksy and a kaitai/<name>.kt fixture.
     */
    private static final List<String> GOLDEN_PAIRS = List.of(
            "simple",
            "valid-minimal",
            "valid-root-doc",
            "valid-bn-fills-byte",
            "valid-bitfield-spans-multiple-bytes",
            "valid-if-size-gt",
            "valid-scale",
            "valid-enum-reference",
            "valid-packed-string",
            "valid-repeat-eos",
            "valid-switch-on-value",
            "valid-reserved-bytes"
    );

    @Test
    public void everyGoldenPair_emitsExactlyTheExpectedKotlin() throws IOException {
        for (final String name : GOLDEN_PAIRS) {
            final Schema schema = parseFixture("kaitai/" + name + ".ksy");
            assertEquals(name + ".ksy must declare exactly one type for this single-type golden wrapper",
                    1, schema.types().size());
            final var typeEntry = schema.types().entrySet().iterator().next();
            final String className = Names.toPascalCase(typeEntry.getKey());
            final TypeDef type = typeEntry.getValue();
            final List<Attr> seq = type.seq();

            final StringBuilder out = new StringBuilder();
            for (final Attr attr : seq) {
                if (attr.isRepeat()) {
                    KaitaiKotlinEmitter.emitRecordClass(out, "", attr.recordType(), null);
                }
                if (attr.isSwitch()) {
                    KaitaiKotlinEmitter.emitSwitchTypes(out, "", attr);
                }
            }
            KaitaiKotlinEmitter.emitKDoc(out, "", type.doc(), seq);
            out.append("data class ").append(className).append("(\n");
            KaitaiKotlinEmitter.emitProperties(out, "    ", seq);
            out.append(") {\n");
            KaitaiKotlinEmitter.emitDecodeCompanion(out, "    ", className, seq);
            out.append("\n");
            KaitaiKotlinEmitter.emitEncodeMethod(out, "    ", seq, false);
            if (KaitaiKotlinEmitter.hasByteArrayProperty(seq)) {
                out.append("\n");
                KaitaiKotlinEmitter.emitEqualsAndHashCode(out, "    ", className, seq);
            }
            out.append("}\n");

            final String expected = readResource("kaitai/" + name + ".kt");
            assertEquals("mismatch for kaitai/" + name + ".kt", expected, out.toString());
        }
    }

    private static Schema parseFixture(final String resourcePath) throws IOException {
        final Yaml loader = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = KaitaiCodeGenGoldenTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("fixture " + resourcePath + " must exist on the test classpath");
            }
            final Map<String, Object> root = loader.load(in);
            final Schema schema = KaitaiParser.parse(resourcePath, root);
            KaitaiLinter.lint(resourcePath, schema);
            return schema;
        }
    }

    private static String readResource(final String resourcePath) throws IOException {
        try (InputStream in = KaitaiCodeGenGoldenTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("fixture " + resourcePath + " must exist on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
