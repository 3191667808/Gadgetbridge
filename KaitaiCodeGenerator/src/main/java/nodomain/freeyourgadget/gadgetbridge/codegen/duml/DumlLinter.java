package nodomain.freeyourgadget.gadgetbridge.codegen.duml;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiLintException;

/**
 * DUML-specific whole-run checks, on top of the generic per-file checks {@code KaitaiLinter} already ran.
 */
final class DumlLinter {
    private DumlLinter() {
    }

    /**
     * No two types anywhere may claim the same (cmdSet, cmd, direction).
     */
    static void lintNoCrossFileCollisions(final Map<String, DumlSchema> schemasByFile) {
        final Map<String, String> owner = new HashMap<>(); // "cmdSet:cmd:direction" -> "file/typeId"
        for (final var fileEntry : schemasByFile.entrySet()) {
            final DumlSchema schema = fileEntry.getValue();
            for (final var typeEntry : schema.typeMetaByTypeId().entrySet()) {
                final DumlSchema.TypeMeta meta = typeEntry.getValue();
                final List<String> claimedDirections = "both".equals(meta.direction())
                        ? List.of("request", "response")
                        : List.of(meta.direction());
                for (final String direction : claimedDirections) {
                    final String key = schema.cmdSet() + ":" + meta.cmd() + ":" + direction;
                    final String label = fileEntry.getKey() + "/" + typeEntry.getKey();
                    final String existing = owner.putIfAbsent(key, label);
                    if (existing != null) {
                        throw new KaitaiLintException(
                                fileEntry.getKey(),
                                "types." + typeEntry.getKey(),
                                "(cmd=0x%02x, direction=%s) under cmdSet 0x%02x already claimed by %s"
                                        .formatted(meta.cmd(), direction, schema.cmdSet(), existing)
                        );
                    }
                }
            }
        }
    }
}
