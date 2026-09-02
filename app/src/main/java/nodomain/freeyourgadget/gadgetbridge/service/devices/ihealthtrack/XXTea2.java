/*
    Copyright (C) 2026 Jonathan Styles

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package nodomain.freeyourgadget.gadgetbridge.service.devices.ihealthtrack;

/**
 * From-scratch implementation of XXTEA ("Corrected Block TEA"), the public
 * block cipher published by David Wheeler and Roger Needham in
 * "Correcting the Block TEA" (1998).
 *
 * <p>The round function, delta constant and overall block-encryption
 * structure follow the widely mirrored public reference C implementation
 * described in Wikipedia's "XXTEA" article
 * (https://en.wikipedia.org/wiki/XXTEA), cross-checked against the
 * independent, MIT-licensed {@code xxtea-java} library by Ma Bingyao
 * (https://github.com/xxtea/xxtea-java) for the little-endian
 * byte-array-to-word packing and "include length" trailing-word framing
 * used here. This class is written independently from those public
 * sources; it is not derived from, and shares no code with, any vendor
 * SDK or any third-party KN-550BT/iHealth-specific reference
 * implementation.
 *
 * <p>The KN-550BT ("iHealth Track") auth handshake (see PROTOCOL.md) uses
 * this cipher with a fixed 16-byte key extracted from the vendor's own
 * Android app (see CRYPTO_PROVENANCE.md) to encrypt 16-byte challenge and
 * device-id values exchanged during pairing.
 */
final class XXTea2 {
    private static final int DELTA = 0x9E3779B9;
    private static final int KEY_WORDS = 4;
    private static final int KEY_BYTES = KEY_WORDS * 4;

    private XXTea2() {
        // utility class, do not instantiate
    }

    /**
     * Encrypts {@code data} with the given key using XXTEA's "include
     * length" framing: the plaintext is packed into little-endian uint32
     * words with one extra trailing word holding the original byte
     * length, and the whole word array is then run through the standard
     * XXTEA block round function. The returned ciphertext is therefore
     * always a multiple of 4 bytes, and is longer than {@code data} by
     * between 1 and 4 bytes (to fit the length word and any padding).
     *
     * @param data the plaintext bytes to encrypt (may be any length)
     * @param key  the encryption key; used as-is if exactly 16 bytes,
     *             otherwise zero-padded or truncated to 16 bytes
     * @return the encrypted bytes
     */
    static byte[] encrypt(final byte[] data, final byte[] key) {
        if (data.length == 0) {
            return data.clone();
        }
        final int[] v = packWords(data);
        final int[] k = packKey(key);
        encryptWords(v, k);
        return unpackWords(v);
    }

    /**
     * In-place XXTEA block encryption of a word array, per the standard
     * "Corrected Block TEA" reference algorithm: {@code v.length - 1}
     * data words (the last word is metadata, here the original byte
     * length) are mixed for {@code 6 + 52 / n} rounds, where {@code n} is
     * the total word count.
     */
    private static void encryptWords(final int[] v, final int[] key) {
        final int n = v.length - 1;
        if (n < 1) {
            return;
        }
        final int rounds = 6 + 52 / (n + 1);
        int sum = 0;
        int z = v[n];
        int y;
        int p;
        for (int round = 0; round < rounds; round++) {
            sum += DELTA;
            final int e = (sum >>> 2) & 3;
            for (p = 0; p < n; p++) {
                y = v[p + 1];
                z = v[p] += mx(sum, y, z, p, e, key);
            }
            y = v[0];
            z = v[n] += mx(sum, y, z, n, e, key);
        }
    }

    /** The XXTEA MX round-mixing function. */
    private static int mx(final int sum, final int y, final int z, final int p, final int e, final int[] key) {
        return ((z >>> 5 ^ y << 2) + (y >>> 3 ^ z << 4)) ^ ((sum ^ y) + (key[(p & 3) ^ e] ^ z));
    }

    /**
     * Packs {@code data} into little-endian uint32 words, appending one
     * extra trailing word holding {@code data.length}. Any bytes beyond
     * a whole number of words are zero-padded.
     */
    private static int[] packWords(final byte[] data) {
        final int wordCount = (data.length + 3) / 4;
        final int[] result = new int[wordCount + 1];
        for (int i = 0; i < data.length; i++) {
            result[i >>> 2] |= (data[i] & 0xFF) << ((i & 3) << 3);
        }
        result[wordCount] = data.length;
        return result;
    }

    /** Inverse of {@link #packWords}, but without dropping any padding. */
    private static byte[] unpackWords(final int[] words) {
        final byte[] result = new byte[words.length * 4];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (words[i >>> 2] >>> ((i & 3) << 3));
        }
        return result;
    }

    /** Zero-pads or truncates {@code key} to 16 bytes, then packs it into 4 little-endian uint32 words. */
    private static int[] packKey(final byte[] key) {
        final byte[] fixed;
        if (key.length == KEY_BYTES) {
            fixed = key;
        } else {
            fixed = new byte[KEY_BYTES];
            System.arraycopy(key, 0, fixed, 0, Math.min(key.length, KEY_BYTES));
        }
        final int[] result = new int[KEY_WORDS];
        for (int i = 0; i < KEY_BYTES; i++) {
            result[i >>> 2] |= (fixed[i] & 0xFF) << ((i & 3) << 3);
        }
        return result;
    }
}
