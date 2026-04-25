/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.miscale;

import org.bouncycastle.shaded.crypto.engines.AESEngine;
import org.bouncycastle.shaded.crypto.modes.CCMBlockCipher;
import org.bouncycastle.shaded.crypto.params.AEADParameters;
import org.bouncycastle.shaded.crypto.params.KeyParameter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MiScaleS400Decryptor {
    private static final int EXPECTED_DATA_LENGTH = 24;
    private static final int EXPECTED_DATA_LENGTH_WITH_HEADER = 26;
    private static final int BIND_KEY_HEX_LENGTH = 32;
    private static final int MAC_TAG_BITS = 32;
    private static final byte[] ASSOCIATED_DATA = new byte[]{0x11};

    public static class Measurement {
        private final float weightKg;
        private final Integer heartRate;

        public Measurement(final float weightKg, final Integer heartRate) {
            this.weightKg = weightKg;
            this.heartRate = heartRate;
        }

        public float getWeightKg() {
            return weightKg;
        }

        public Integer getHeartRate() {
            return heartRate;
        }
    }

    public static Measurement decrypt(final byte[] advertisementData,
                                      final String macAddress,
                                      final String bindKey) {
        if (!isValidBindKey(bindKey) || advertisementData == null) {
            return null;
        }

        final byte[] data;
        switch (advertisementData.length) {
            case EXPECTED_DATA_LENGTH:
                data = advertisementData;
                break;
            case EXPECTED_DATA_LENGTH_WITH_HEADER:
                data = new byte[EXPECTED_DATA_LENGTH];
                System.arraycopy(advertisementData, 2, data, 0, EXPECTED_DATA_LENGTH);
                break;
            default:
                return null;
        }

        try {
            final byte[] macBytes = GB.hexStringToByteArray(macAddress.replace(":", ""));
            final byte[] keyBytes = GB.hexStringToByteArray(bindKey);
            if (macBytes.length != 6 || keyBytes.length != 16) {
                return null;
            }

            final byte[] nonce = new byte[12];
            for (int i = 0; i < 6; i++) {
                nonce[i] = macBytes[5 - i];
            }
            // Xiaomi encrypted advertisements use the reversed MAC and packet counters as CCM nonce.
            System.arraycopy(data, 2, nonce, 6, 3);
            System.arraycopy(data, data.length - 7, nonce, 9, 3);

            final byte[] mic = new byte[4];
            System.arraycopy(data, data.length - 4, mic, 0, 4);
            final byte[] encryptedPayload = new byte[data.length - 12];
            System.arraycopy(data, 5, encryptedPayload, 0, encryptedPayload.length);

            final byte[] cipherText = new byte[encryptedPayload.length + mic.length];
            System.arraycopy(encryptedPayload, 0, cipherText, 0, encryptedPayload.length);
            System.arraycopy(mic, 0, cipherText, encryptedPayload.length, mic.length);

            final AESEngine aesEngine = new AESEngine();
            aesEngine.init(false, new KeyParameter(keyBytes));
            final CCMBlockCipher blockCipher = new CCMBlockCipher(aesEngine);
            blockCipher.init(false, new AEADParameters(new KeyParameter(keyBytes), MAC_TAG_BITS, nonce, ASSOCIATED_DATA));

            final byte[] decrypted = new byte[blockCipher.getOutputSize(cipherText.length)];
            final int outLength = blockCipher.processBytes(cipherText, 0, cipherText.length, decrypted, 0);
            blockCipher.doFinal(decrypted, outLength);

            return parseDecrypted(decrypted);
        } catch (final Exception ignored) {
            return null;
        }
    }

    private static Measurement parseDecrypted(final byte[] decrypted) {
        if (decrypted.length < 12) {
            return null;
        }

        final byte[] slice = new byte[4];
        System.arraycopy(decrypted, 4, slice, 0, 4);
        final int value = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN).getInt();

        final int weightRaw = value & 0x7ff;
        final int heartRateRaw = (value >> 11) & 0x7f;

        final float weightKg = weightRaw / 10.0f;
        if (weightKg <= 0) {
            return null;
        }

        final Integer heartRate = heartRateRaw >= 1 && heartRateRaw <= 126 ? heartRateRaw + 50 : null;

        return new Measurement(weightKg, heartRate);
    }

    public static boolean isValidBindKey(final String bindKey) {
        if (bindKey == null || bindKey.length() != BIND_KEY_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < bindKey.length(); i++) {
            if (Character.digit(bindKey.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
