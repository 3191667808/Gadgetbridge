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
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Attr;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema.Schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Covers the DUML/Kaitai subset {@link KaitaiParser} enforces: which YAML
 * constructs are accepted, and - the parts most likely to silently corrupt
 * data if wrong - the byte/bit-boundary bookkeeping around {@code bN}
 * bitfields and the {@code if: _io.size > N} optional-tail form.
 * <p>
 * Each case is a real, standalone {@code .ksy} file under
 * {@code src/test/resources/kaitai/}.
 */
public class KaitaiParserTest {
    private static Schema parseFixture(final String name) throws IOException {
        final Yaml loader = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = KaitaiParserTest.class.getClassLoader().getResourceAsStream("kaitai/" + name)) {
            assertNotNull("fixture kaitai/" + name + " must exist", in);
            final Map<String, Object> root = loader.load(in);
            return KaitaiParser.parse(name, root);
        }
    }

    @Test
    public void parsesMinimalValidSchema() throws IOException {
        final Schema schema = parseFixture("valid-minimal.ksy");

        assertEquals("test_schema", schema.fileId());
        assertEquals("le", schema.meta().endian());
        assertEquals(1, schema.types().size());
        final List<Attr> seq = schema.types().get("simple_msg").seq();
        assertEquals(2, seq.size());
        assertEquals("a", seq.getFirst().id());
        assertEquals("u1", seq.get(0).kaitaiType());
        assertEquals(1, seq.get(0).sizeBytes());
        assertEquals("b", seq.get(1).id());
        assertEquals(2, seq.get(1).sizeBytes());
        assertEquals("", schema.doc()); // no root doc: in this fixture
    }

    @Test
    public void capturesTheRootDocBlock() throws IOException {
        final Schema schema = parseFixture("valid-root-doc.ksy");
        assertTrue(schema.doc().contains("Commands under this cmdSet."));
        assertTrue(schema.doc().contains("Second line of the root doc."));
    }

    @Test
    public void rejectsUnsupportedTopLevelKey() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-unsupported-top-level-key.ksy"));
        assertTrue(ex.getMessage().startsWith("invalid-unsupported-top-level-key.ksy: <root>: unsupported key \"instances\" (allowed: ["));
    }

    @Test
    public void rejectsNonLittleEndian() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-non-le-endian.ksy"));
        assertEquals("invalid-non-le-endian.ksy: meta.endian: only little-endian (\"le\") payloads are supported, got \"be\"",
                ex.getMessage());
    }

    @Test
    public void rejectsUnsupportedType() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-unsupported-type.ksy"));
        assertEquals("invalid-unsupported-type.ksy: types.m.seq.a.type: unsupported type \"b64\" "
                + "(allowed: u1 u2 u4 u8 s1 s2 s4 s8 f4 f8 str b1..b8, or a nested record type declared under "
                + "this type's \"types:\" with \"repeat: eos\")", ex.getMessage());
    }

    //
    // bN bitfield byte-boundary bookkeeping
    //

    @Test
    public void bitEndianLeIsRequiredWhenAnyBnFieldIsUsed() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-bn-missing-bit-endian.ksy"));
        assertEquals("invalid-bn-missing-bit-endian.ksy: meta.bit-endian: required (\"le\") when any type uses a bN field",
                ex.getMessage());
    }

    @Test
    public void acceptsBnFieldsThatExactlyFillAByte() throws IOException {
        final Schema schema = parseFixture("valid-bn-fills-byte.ksy");
        assertEquals(2, schema.types().get("m").seq().size());
    }

    @Test
    public void rejectsBnFieldCrossingAByteBoundary() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-bn-crosses-byte-boundary.ksy"));
        assertEquals("invalid-bn-crosses-byte-boundary.ksy: types.m.seq.b: crosses a byte boundary "
                + "(6 bits already consumed in this byte, field is 4 bits) - not allowed in this subset, "
                + "split across named fields differently", ex.getMessage());
    }

    @Test
    public void rejectsDanglingBitsBeforeAByteAlignedField() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-dangling-bits-before-byte-aligned.ksy"));
        assertEquals("invalid-dangling-bits-before-byte-aligned.ksy: types.m.seq.b: 4 bits declared so far do not fill "
                + "a whole byte; a real Kaitai reader would silently discard the remainder when byte-aligning here. "
                + "Add the missing bits explicitly.", ex.getMessage());
    }

    @Test
    public void rejectsDanglingBitsAtEndOfType() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-dangling-bits-at-end.ksy"));
        assertEquals("invalid-dangling-bits-at-end.ksy: types.m.seq: 4 bits declared at the end of the type do not fill a whole byte",
                ex.getMessage());
    }

    @Test
    public void bitfieldsCanSpanMultipleBytesByteByByte() throws IOException {
        // controller_state-shaped: 4 separate one-byte-filling runs in a row, no field crosses a boundary.
        final Schema schema = parseFixture("valid-bitfield-spans-multiple-bytes.ksy");
        assertEquals(4, schema.types().get("m").seq().size());
    }

    //
    // if: _io.size > N (native Kaitai optional-tail form)
    //

    @Test
    public void acceptsIfSizeGtMatchingMandatoryByteCount() throws IOException {
        final Schema schema = parseFixture("valid-if-size-gt.ksy");
        final List<Attr> seq = schema.types().get("m").seq();
        assertFalse(seq.get(0).optional());
        assertTrue(seq.get(1).optional());
    }

    @Test
    public void rejectsIfSizeGtWithWrongThreshold() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-if-wrong-threshold.ksy"));
        assertEquals("invalid-if-wrong-threshold.ksy: types.m.seq.b.if: expected \"_io.size > 1\" (every field in a "
                + "trailing optional run shares the same threshold - the mandatory byte count before the run), "
                + "got \"_io.size > 99\"", ex.getMessage());
    }

    @Test
    public void rejectsIfOnANonSizeExpression() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-if-non-size-expression.ksy"));
        assertEquals("invalid-if-non-size-expression.ksy: types.m.seq.b.if: only the form \"_io.size > N\" is supported "
                + "(a trailing run gated on total payload size), got \"some_field == 1\"", ex.getMessage());
    }

    @Test
    public void rejectsMandatoryFieldAfterOptionalTailStarts() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-mandatory-after-optional.ksy"));
        assertEquals("invalid-mandatory-after-optional.ksy: types.m.seq.c: is mandatory but follows an \"if\"-gated field; "
                + "optional fields must be a contiguous trailing run", ex.getMessage());
    }

    @Test
    public void rejectsIfOnABnField() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-if-on-bn-field.ksy"));
        assertEquals("invalid-if-on-bn-field.ksy: types.m.seq.b: \"if\" is not valid on a bN field; only whole trailing "
                + "bytes may be optional", ex.getMessage());
    }

    //
    // -scale
    //

    @Test
    public void scaleProducesAScaledAttr() throws IOException {
        final Schema schema = parseFixture("valid-scale.ksy");
        assertEquals(0.1, schema.types().get("m").seq().getFirst().scale(), 1e-9);
    }

    @Test
    public void rejectsScaleOnAFloatingPointField() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-scale-on-float.ksy"));
        assertEquals("invalid-scale-on-float.ksy: types.m.seq.a.-scale: not valid on a floating-point field", ex.getMessage());
    }

    //
    // enum references
    //

    @Test
    public void rejectsUnknownEnumReference() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-unknown-enum-reference.ksy"));
        assertEquals("invalid-unknown-enum-reference.ksy: types.m.seq.a.enum: unknown enum \"nonexistent\"", ex.getMessage());
    }

    @Test
    public void resolvesAKnownEnumReference() throws IOException {
        final Schema schema = parseFixture("valid-enum-reference.ksy");
        assertEquals("color", schema.types().get("m").seq().getFirst().enumRef());
        assertEquals(2, schema.enums().get("color").values().size());
    }

    //
    // packed-string syntax (see KaitaiKotlinEmitterTest for how the emitter turns this into Kotlin)
    //

    @Test
    public void acceptsPackedStringReferencingPrecedingU1LengthField() throws IOException {
        final Schema schema = parseFixture("valid-packed-string.ksy");
        final Attr strAttr = schema.types().get("m").seq().get(1);
        assertTrue(strAttr.isString());
        assertEquals("name_len", strAttr.strSizeRefId());
    }

    @Test
    public void rejectsPackedStringNotReferencingImmediatelyPrecedingU1() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-packed-string-wrong-preceding-type.ksy"));
        assertEquals("invalid-packed-string-wrong-preceding-type.ksy: types.m.seq.name.size: as a field reference, \"size\" "
                + "must name the immediately preceding u1 attribute (packed-string form)", ex.getMessage());
    }

    @Test
    public void rejectsIfDirectlyOnAStrField() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-if-on-str-field.ksy"));
        assertEquals("invalid-if-on-str-field.ksy: types.m.seq.b: \"if\" is not valid on a str field in this subset",
                ex.getMessage());
    }

    @Test
    public void rejectsOptionalTailFollowingAPackedString() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-optional-tail-after-packed-string.ksy"));
        assertEquals("invalid-optional-tail-after-packed-string.ksy: types.m.seq.extra: an \"if\"-gated optional tail cannot "
                + "follow a packed string - the tail's byte-size threshold can't be computed once a variable-length field "
                + "precedes it", ex.getMessage());
    }

    @Test
    public void rejectsDuplicateAttributeId() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-duplicate-attribute-id.ksy"));
        assertEquals("invalid-duplicate-attribute-id.ksy: types.m.seq: duplicate attribute id \"a\"", ex.getMessage());
    }

    //
    // repeat: eos (nested record types)
    //

    @Test
    public void acceptsRepeatEosOfANestedRecordType() throws IOException {
        final Schema schema = parseFixture("valid-repeat-eos.ksy");
        assertEquals(1, schema.types().size());
        final List<Attr> seq = schema.types().get("m").seq();
        assertEquals(1, seq.size());
        final Attr entries = seq.getFirst();
        assertEquals("entries", entries.id());
        assertTrue(entries.isRepeat());
        assertFalse(entries.isBitfield());
        assertFalse(entries.isString());
        assertFalse(entries.optional());
        assertEquals("entry", entries.recordType().id());
        assertEquals(2, entries.recordType().seq().size());
        assertEquals("a", entries.recordType().seq().get(0).id());
        assertEquals("u1", entries.recordType().seq().get(0).kaitaiType());
        assertEquals("b", entries.recordType().seq().get(1).id());
        assertEquals("u4", entries.recordType().seq().get(1).kaitaiType());
    }

    @Test
    public void rejectsRepeatValueOtherThanEos() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-repeat-not-eos.ksy"));
        assertEquals("invalid-repeat-not-eos.ksy: types.m.seq.entries.repeat: only \"eos\" is supported, got \"until_something\"",
                ex.getMessage());
    }

    @Test
    public void rejectsRepeatAttributeNotInLastPosition() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-repeat-not-last.ksy"));
        assertEquals("invalid-repeat-not-last.ksy: types.m.seq: a \"repeat: eos\" attribute must be the last attribute in seq",
                ex.getMessage());
    }

    @Test
    public void rejectsRepeatCombinedWithIf() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-repeat-with-if.ksy"));
        assertEquals("invalid-repeat-with-if.ksy: types.m.seq.entries: \"if\"/\"size\"/\"enum\"/\"encoding\"/\"-scale\" "
                + "are not valid on a \"repeat: eos\" attribute", ex.getMessage());
    }

    @Test
    public void rejectsNestedRecordTypeDeclaringItsOwnNestedTypes() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-repeat-record-has-nested-types.ksy"));
        assertTrue(ex.getMessage().startsWith("invalid-repeat-record-has-nested-types.ksy: types.m.types.entry: unsupported key \"types\" (allowed: ["));
    }

    @Test
    public void rejectsPackedStringInsideANestedRecordType() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-repeat-record-has-packed-string.ksy"));
        assertEquals("invalid-repeat-record-has-packed-string.ksy: types.m.types.entry.seq.name: a packed string "
                + "(\"size\" referencing another field) is not valid inside a nested record type", ex.getMessage());
    }

    @Test
    public void rejectsIfInsideANestedRecordType() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-repeat-record-has-if.ksy"));
        assertEquals("invalid-repeat-record-has-if.ksy: types.m.types.entry.seq.b: \"if\" is not valid inside a nested "
                + "record type (must be fully fixed-size)", ex.getMessage());
    }

    @Test
    public void rejectsRepeatReferencingAnUnknownNestedType() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-repeat-unknown-type.ksy"));
        assertEquals("invalid-repeat-unknown-type.ksy: types.m.seq.entries.type: unknown nested record type "
                + "\"nonexistent_entry\" (must be declared under this type's own \"types:\")", ex.getMessage());
    }

    //
    // switch-on (discriminant-based union)
    //

    @Test
    public void acceptsSwitchOnValueWithMultipleCases() throws IOException {
        final Schema schema = parseFixture("valid-switch-on-value.ksy");
        assertEquals(1, schema.types().size());
        final List<Attr> seq = schema.types().get("m").seq();
        assertEquals(2, seq.size());
        final Attr cmdType = seq.get(0);
        assertEquals("cmd_type", cmdType.id());
        assertEquals("u1", cmdType.kaitaiType());
        assertFalse(cmdType.isSwitch());

        final Attr body = seq.get(1);
        assertEquals("body", body.id());
        assertTrue(body.isSwitch());
        assertFalse(body.isBitfield());
        assertFalse(body.isString());
        assertFalse(body.isRepeat());
        assertFalse(body.optional());
        assertEquals("cmd_type", body.switchOnId());
        assertEquals(2, body.switchCases().size());
        assertEquals("config_body", body.switchCases().get(3L).id());
        assertEquals(2, body.switchCases().get(3L).seq().size());
        assertEquals("encrypt_body", body.switchCases().get(4L).id());
        assertEquals(2, body.switchCases().get(4L).seq().size());
    }

    @Test
    public void rejectsSwitchAttributeNotInLastPosition() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-not-last.ksy"));
        assertEquals("invalid-switch-not-last.ksy: types.m.seq: a \"switch-on\" attribute must be the last attribute in seq",
                ex.getMessage());
    }

    @Test
    public void rejectsSwitchOnNamingSomethingOtherThanTheImmediatelyPrecedingAttribute() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-wrong-discriminant.ksy"));
        assertEquals("invalid-switch-wrong-discriminant.ksy: types.m.seq.body.type.switch-on: must name the immediately "
                + "preceding attribute in seq, got \"cmd_type\"", ex.getMessage());
    }

    @Test
    public void rejectsASignedDiscriminant() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-signed-discriminant.ksy"));
        assertEquals("invalid-switch-signed-discriminant.ksy: types.m.seq.body.type.switch-on: \"cmd_type\" must be an "
                + "unsigned integer scalar (u1/u2/u4/u8) to be used as a switch discriminant", ex.getMessage());
    }

    @Test
    public void rejectsADuplicateCaseValue() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-duplicate-case.ksy"));
        assertEquals("invalid-switch-duplicate-case.ksy: types.m.seq.body.type.cases: duplicate case value 3", ex.getMessage());
    }

    @Test
    public void rejectsSwitchCaseReferencingAnUnknownNestedType() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-unknown-type.ksy"));
        assertEquals("invalid-switch-unknown-type.ksy: types.m.seq.body.type.cases.3: unknown nested record type "
                + "\"nonexistent_body\" (must be declared under this type's own \"types:\")", ex.getMessage());
    }

    @Test
    public void rejectsASwitchCaseTypeDeclaringItsOwnNestedTypes() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-case-has-nested-types.ksy"));
        assertTrue(ex.getMessage().startsWith("invalid-switch-case-has-nested-types.ksy: types.m.types.config_body: unsupported key \"types\" (allowed: ["));
    }

    @Test
    public void rejectsIfInsideASwitchCaseType() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-case-has-if.ksy"));
        assertEquals("invalid-switch-case-has-if.ksy: types.m.types.config_body.seq.extra: \"if\" is not valid inside a "
                + "nested record type (must be fully fixed-size)", ex.getMessage());
    }

    @Test
    public void rejectsSwitchCombinedWithIf() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-switch-with-if.ksy"));
        assertEquals("invalid-switch-with-if.ksy: types.m.seq.body: \"if\"/\"size\"/\"enum\"/\"encoding\"/\"-scale\"/\"repeat\" "
                + "are not valid on a \"switch-on\" attribute", ex.getMessage());
    }

    //
    // reserved bytes (bare "size:", no "type:" - the native Kaitai raw-bytes idiom)
    //

    @Test
    public void acceptsReservedBytesAttribute() throws IOException {
        final Schema schema = parseFixture("valid-reserved-bytes.ksy");
        final List<Attr> seq = schema.types().get("m").seq();
        assertEquals(2, seq.size());
        final Attr reserved = seq.getFirst();
        assertEquals("reserved", reserved.id());
        assertTrue(reserved.isReserved());
        assertEquals(4, reserved.sizeBytes());
        assertFalse(reserved.isBitfield());
        assertFalse(reserved.isString());
        assertFalse(reserved.optional());
        assertFalse(seq.get(1).isReserved());
    }

    @Test
    public void rejectsReservedAttributeMissingSize() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-reserved-missing-size.ksy"));
        assertEquals("invalid-reserved-missing-size.ksy: types.m.seq.mystery.size: a raw-bytes attribute (\"type\" omitted) "
                + "requires a positive integer \"size\"", ex.getMessage());
    }

    @Test
    public void rejectsReservedAttributeWithZeroSize() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-reserved-zero-size.ksy"));
        assertEquals("invalid-reserved-zero-size.ksy: types.m.seq.mystery.size: a raw-bytes attribute (\"type\" omitted) "
                + "requires a positive integer \"size\"", ex.getMessage());
    }

    @Test
    public void rejectsReservedAttributeCombinedWithEnum() {
        final var ex = assertThrows(KaitaiLintException.class, () -> parseFixture("invalid-reserved-with-enum.ksy"));
        assertEquals("invalid-reserved-with-enum.ksy: types.m.seq.mystery: \"enum\"/\"encoding\"/\"-scale\"/\"if\"/\"repeat\" "
                + "are not valid on a raw-bytes attribute (\"type\" omitted)", ex.getMessage());
    }
}
