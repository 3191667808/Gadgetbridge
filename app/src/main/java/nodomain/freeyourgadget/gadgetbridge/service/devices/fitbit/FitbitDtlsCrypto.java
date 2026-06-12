/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import org.bouncycastle.shaded.crypto.InvalidCipherTextException;
import org.bouncycastle.shaded.crypto.engines.AESEngine;
import org.bouncycastle.shaded.crypto.modes.CCMBlockCipher;
import org.bouncycastle.shaded.crypto.params.AEADParameters;
import org.bouncycastle.shaded.crypto.params.KeyParameter;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class FitbitDtlsCrypto {
    private static final int AES_128_CCM_TAG_LENGTH_BYTES = 16;
    private static final int AES_128_CCM_TAG_LENGTH_BITS = AES_128_CCM_TAG_LENGTH_BYTES * 8;

    private FitbitDtlsCrypto() {
    }

    static byte[] encryptAes128CcmRecord(final byte[] key,
                                         final byte[] fixedIv,
                                         final int contentType,
                                         final int version,
                                         final int epoch,
                                         final long sequenceNumber,
                                         final byte[] plaintext) throws InvalidCipherTextException {
        final byte[] explicitNonce = buildExplicitNonce(epoch, sequenceNumber);
        final byte[] nonce = concat(fixedIv, explicitNonce);
        final byte[] aad = buildAeadAdditionalData(contentType, version, epoch, sequenceNumber, plaintext.length);
        final byte[] encrypted = ccmCrypt(true, key, nonce, aad, plaintext);
        return concat(explicitNonce, encrypted);
    }

    static byte[] decryptAes128CcmRecord(final byte[] key,
                                         final byte[] fixedIv,
                                         final int contentType,
                                         final int version,
                                         final int epoch,
                                         final long sequenceNumber,
                                         final byte[] encryptedBody,
                                         final int plaintextLength) throws InvalidCipherTextException {
        if (encryptedBody.length < 8 + AES_128_CCM_TAG_LENGTH_BYTES) {
            throw new InvalidCipherTextException("encrypted DTLS body is too short");
        }

        final byte[] explicitNonce = new byte[8];
        System.arraycopy(encryptedBody, 0, explicitNonce, 0, explicitNonce.length);
        final byte[] encrypted = new byte[encryptedBody.length - explicitNonce.length];
        System.arraycopy(encryptedBody, explicitNonce.length, encrypted, 0, encrypted.length);
        final byte[] nonce = concat(fixedIv, explicitNonce);
        final byte[] aad = buildAeadAdditionalData(contentType, version, epoch, sequenceNumber, plaintextLength);
        return ccmCrypt(false, key, nonce, aad, encrypted);
    }

    static byte[] buildPskPreMasterSecret(final byte[] psk) {
        final byte[] secret = new byte[2 + psk.length + 2 + psk.length];
        int offset = 0;
        writeU16(secret, offset, psk.length);
        offset += 2 + psk.length;
        writeU16(secret, offset, psk.length);
        offset += 2;
        System.arraycopy(psk, 0, secret, offset, psk.length);
        return secret;
    }

    static byte[] tlsPrf(final byte[] secret,
                         final String label,
                         final byte[] seed,
                         final int outputLength) throws NoSuchAlgorithmException, InvalidKeyException {
        final byte[] labelAndSeed = concat(label.getBytes(StandardCharsets.US_ASCII), seed);
        byte[] a = labelAndSeed;
        final byte[] output = new byte[outputLength];
        int outputOffset = 0;

        while (outputOffset < outputLength) {
            a = hmacSha256(secret, a);
            final byte[] round = hmacSha256(secret, concat(a, labelAndSeed));
            final int copyLength = Math.min(round.length, outputLength - outputOffset);
            System.arraycopy(round, 0, output, outputOffset, copyLength);
            outputOffset += copyLength;
        }

        return output;
    }

    static byte[] sha256(final byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] ccmCrypt(final boolean encrypt,
                                   final byte[] key,
                                   final byte[] nonce,
                                   final byte[] aad,
                                   final byte[] input) throws InvalidCipherTextException {
        final CCMBlockCipher cipher = new CCMBlockCipher(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), AES_128_CCM_TAG_LENGTH_BITS, nonce, aad));
        final byte[] output = new byte[cipher.getOutputSize(input.length)];
        final int length = cipher.processBytes(input, 0, input.length, output, 0);
        final int finalLength = cipher.doFinal(output, length);
        final byte[] result = new byte[length + finalLength];
        System.arraycopy(output, 0, result, 0, length + finalLength);
        return result;
    }

    private static byte[] buildExplicitNonce(final int epoch, final long sequenceNumber) {
        final byte[] explicitNonce = new byte[8];
        writeU16(explicitNonce, 0, epoch);
        writeU48(explicitNonce, 2, sequenceNumber);
        return explicitNonce;
    }

    private static byte[] buildAeadAdditionalData(final int contentType,
                                                  final int version,
                                                  final int epoch,
                                                  final long sequenceNumber,
                                                  final int plaintextLength) {
        final byte[] aad = new byte[13];
        writeU16(aad, 0, epoch);
        writeU48(aad, 2, sequenceNumber);
        aad[8] = (byte) contentType;
        writeU16(aad, 9, version);
        writeU16(aad, 11, plaintextLength);
        return aad;
    }

    private static byte[] hmacSha256(final byte[] key, final byte[] data)
            throws NoSuchAlgorithmException, InvalidKeyException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(final byte[] first, final byte[] second) {
        final byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void writeU16(final byte[] value, final int offset, final int number) {
        value[offset] = (byte) ((number >> 8) & 0xff);
        value[offset + 1] = (byte) (number & 0xff);
    }

    private static void writeU48(final byte[] value, final int offset, final long number) {
        value[offset] = (byte) ((number >> 40) & 0xff);
        value[offset + 1] = (byte) ((number >> 32) & 0xff);
        value[offset + 2] = (byte) ((number >> 24) & 0xff);
        value[offset + 3] = (byte) ((number >> 16) & 0xff);
        value[offset + 4] = (byte) ((number >> 8) & 0xff);
        value[offset + 5] = (byte) (number & 0xff);
    }
}
