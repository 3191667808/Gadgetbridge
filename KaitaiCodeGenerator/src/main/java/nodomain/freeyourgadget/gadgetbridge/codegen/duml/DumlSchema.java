package nodomain.freeyourgadget.gadgetbridge.codegen.duml;

import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema;

/**
 * DUML's layer on top of a generic {@link KaitaiSchema.Schema}: which cmdSet
 * a file's types belong to, and which (cmd, direction) each individual type
 * decodes/encodes. Kept as a thin wrapper - {@code KaitaiSchema.Schema} still
 * owns the actual byte layout (enums, attrs) - so that nothing DUML-specific
 * leaks into the {@code codegen.kaitai} package.
 */
record DumlSchema(
        int cmdSet,
        String kotlinClassName,
        KaitaiSchema.Schema generic,
        Map<String, TypeMeta> typeMetaByTypeId
) {
    /**
     * The {@code -duml} block on one type: which (cmd, direction) it is, and its command-family name.
     */
    record TypeMeta(int cmd, String direction, String name) {
    }
}
