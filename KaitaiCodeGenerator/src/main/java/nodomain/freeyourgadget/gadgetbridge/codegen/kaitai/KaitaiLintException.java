package nodomain.freeyourgadget.gadgetbridge.codegen.kaitai;

/**
 * Thrown when a .ksy file uses a construct outside the supported Kaitai
 * Struct subset, or violates one of the whole-schema rules (full byte
 * coverage via sequential-only fields, no overlapping bit masks, etc).
 * Deliberately fails the generator - there is no "best effort" mode, since
 * a silently-ignored construct would produce code that would not behave
 * as the developer expects.
 */
public final class KaitaiLintException extends RuntimeException {
    public KaitaiLintException(final String file, final String where, final String message) {
        super("%s: %s: %s".formatted(file, where, message));
    }
}
