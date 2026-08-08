/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo
 *
 *  This file is part of Gadgetbridge.
 *
 *  Gadgetbridge is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published
 *  by the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Gadgetbridge is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import org.concentus.OpusDecoder;
import org.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class HuamiVoiceAssistantHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HuamiVoiceAssistantHandler.class);

    private static final byte CMD_START = 0x01;
    private static final byte CMD_END = 0x02;
    private static final byte CMD_START_ACK = 0x03;
    private static final byte CMD_VOICE_DATA = 0x05;
    private static final byte CMD_END_ACK = 0x06;
    private static final byte CMD_REPLY_SIMPLE = 0x09;
    private static final byte CMD_REPLY_ERROR = 0x0F;
    private static final byte CMD_LANGUAGES_REQUEST = 0x10;
    private static final byte CMD_LANGUAGES_RESPONSE = 0x11;
    private static final byte CMD_SET_LANGUAGE_ACK = 0x13;
    private static final byte CMD_CAPABILITIES_REQUEST = 0x20;
    private static final byte CMD_CAPABILITIES_RESPONSE = 0x21;

    private static final int CHANNELS = 1;
    private static final int MAX_FRAME_SIZE = 6 * 960;

    // No explicit end-of-recording markers have been observed: the band streams audio
    // frames continuously while recording, and pressing stoprec emits no command, so
    // the end of the voice stream is detected via a timeout with no incoming VOICE_DATA.
    // Messages arrive roughly every 200ms, so the timeout is re-armed well before it
    // would fire mid-stream; 300ms leaves a comfortable margin over the observed
    // inter-message gaps.
    private static final long VOICE_STREAM_END_TIMEOUT_MS = 300;

    // The band's reply text buffer is byte-limited: replies near 650 bytes were cut
    // mid-text with stale-memory garbage after them, and a ~2KB reply crashed the band.
    // Total rendered budget (text + ellipsis) is capped at 600, a round safe margin
    // below the cut.
    private static final int MAX_REPLY_TEXT_BYTES = 600;

    /**
     * Predefined error replies rendered by the band itself (all hardware-verified
     * on a v2 band). The band carries its own localized strings for each ID, so
     * sending the ID is enough - no i18n is needed on our side.
     */
    public enum AssistantError {
        /** "Check the network connection and try again" - network connection failed */
        NETWORK(0x02),
        /** "Cannot use Amazon Alexa without a connected smartphone" - Alexa needs the phone */
        ALEXA_NO_PHONE(0x05),
        /** "Authorization failed, try again" - authorization failed */
        AUTH(0x06),
        /** Blank display */
        BLANK(0x07),
        /** "Sorry, could you repeat that?" - could not understand, ask the user to repeat */
        REPEAT(0x08),
        /** "Server is busy, try again later" */
        SERVER_BUSY(0x09),
        /** "Could not complete the action, try again" - action could not be completed */
        ACTION_FAILED(0x0B);

        private final byte id;

        AssistantError(final int id) {
            this.id = (byte) id;
        }
    }

    private final HuamiSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OpusDecoder opusDecoder;
    private AudioTrack audioTrack;
    private final ByteBuffer voiceBuffer = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
    private int mVersion = -1;

    private final Runnable voiceStreamEndTimeoutRunnable = () -> {
        LOG.info("Voice recording finished, sending reply");
        write(new byte[]{CMD_END_ACK});
        sendReply("Loud and clear!");
    };

    public HuamiVoiceAssistantHandler(final HuamiSupport support) {
        this.support = support;
    }

    public void handlePayload(final byte[] payload) {
        switch (payload[0]) {
            case CMD_START:
                LOG.info("Assistant starting");
                dispose();
                try {
                    opusDecoder = new OpusDecoder(16000, 1);
                } catch (final OpusException e) {
                    LOG.error("Failed to initialize opus decoder", e);
                    sendErrorReply(AssistantError.ACTION_FAILED);
                    break;
                }
                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        16000,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(
                                16000,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                        ),
                        AudioTrack.MODE_STREAM
                );
                audioTrack.play();
                handler.postDelayed(this::sendStartAck, 700);
                break;
            case CMD_END:
                LOG.info("Assistant ending");
                write(new byte[]{CMD_END_ACK});
                dispose();
                break;
            case CMD_VOICE_DATA:
                handleVoiceData(payload);
                handler.removeCallbacks(voiceStreamEndTimeoutRunnable);
                handler.postDelayed(voiceStreamEndTimeoutRunnable, VOICE_STREAM_END_TIMEOUT_MS);
                break;
            case CMD_CAPABILITIES_RESPONSE:
                mVersion = payload[1] & 0xFF;
                // Older bands reply with just {0x21, version, var1} (v2 verified as
                // 3 bytes on hardware), so guard reads of the optional var2 byte.
                final byte var1 = payload.length > 2 ? payload[2] : 0;
                final byte var2 = payload.length > 3 ? payload[3] : 0;
                LOG.info("Assistant capabilities version={}, var1={}, var2={}", mVersion, var1, var2);
                // The v3 language request and v5 frame-size layout below have not
                // been verified on real devices.
                if (mVersion == 3) {
                    requestLanguages();
                }
                break;
            case CMD_LANGUAGES_RESPONSE:
                int pos = 2;
                final String currentLanguage = StringUtils.untilNullTerminator(payload, pos);
                pos = pos + currentLanguage.length() + 1;
                final int numLanguages = payload[pos++] & 0xFF;
                final List<String> allLanguages = new ArrayList<>();
                for (int i = 0; i < numLanguages; i++) {
                    final String language = StringUtils.untilNullTerminator(payload, pos);
                    allLanguages.add(language);
                    pos = pos + language.length() + 1;
                }
                LOG.info("Got assistant language = {}, supported languages = {}", currentLanguage, allLanguages);
                break;
            case CMD_SET_LANGUAGE_ACK:
                LOG.info("Assistant set language ack, status = {}", payload[1]);
                break;
            default:
                LOG.warn("Unexpected assistant byte {}", String.format("0x%02x", payload[0]));
        }
    }

    private void handleVoiceData(final byte[] payload) {
        voiceBuffer.put(payload, 5, payload.length - 5);
        voiceBuffer.flip();

        while (voiceBuffer.remaining() > 0) {
            voiceBuffer.mark();
            // Frame-size layout: 1-byte lengths on older bands, 4-byte on v5+;
            // not verified on real devices.
            final int frameSizeBytes = mVersion >= 5 ? 4 : 1;

            if (voiceBuffer.remaining() < frameSizeBytes) {
                voiceBuffer.reset();
                break;
            }

            final int frameSize;
            if (frameSizeBytes == 1) {
                frameSize = voiceBuffer.get() & 0xff;
            } else {
                frameSize = voiceBuffer.getInt();
                voiceBuffer.getInt(); // skip 4 bytes
            }

            if (voiceBuffer.remaining() < frameSize) {
                voiceBuffer.reset();
                break;
            }

            if (frameSize == 0) continue;

            final byte[] frame = new byte[frameSize];
            voiceBuffer.get(frame);

            if (opusDecoder != null) {
                try {
                    final byte[] pcm = new byte[MAX_FRAME_SIZE * CHANNELS * 2];
                    final int decodedSamples = opusDecoder.decode(frame, 0, frame.length, pcm, 0, MAX_FRAME_SIZE, false);
                    if (audioTrack != null) {
                        audioTrack.write(pcm, 0, decodedSamples * 2);
                    }
                } catch (final Exception e) {
                    LOG.error("Failed to decode opus frame", e);
                }
            }
        }
        voiceBuffer.compact();
    }

    public void requestCapabilities() {
        write(new byte[]{CMD_CAPABILITIES_REQUEST});
    }

    public void requestLanguages() {
        write(new byte[]{CMD_LANGUAGES_REQUEST});
    }

    public void sendStartAck() {
        write(new byte[]{CMD_START_ACK});
    }

    public void sendReplyComplex(final String title, final String subtitle, final String text) {
        final byte[] titleBytes = StringUtils.ensureNotNull(title).getBytes(StandardCharsets.UTF_8);
        final byte[] subtitleBytes = StringUtils.ensureNotNull(subtitle).getBytes(StandardCharsets.UTF_8);
        final byte[] textBytes = StringUtils.ensureNotNull(text).getBytes(StandardCharsets.UTF_8);

        final int messageLength = titleBytes.length + subtitleBytes.length + textBytes.length + 3;
        final ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 4 + messageLength).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte)0x08);
        buf.putShort((short)0x06);
        buf.putInt(messageLength);
        buf.put(titleBytes);
        buf.put((byte) 0);
        buf.put(subtitleBytes);
        buf.put((byte) 0);
        buf.put(textBytes);
        buf.put((byte) 0);
        write(buf.array());
    }

    public void sendReply(final String text) {
        sendTextPayload(new byte[]{CMD_REPLY_SIMPLE}, text);
    }

    public void sendErrorReply(final AssistantError error) {
        write(new byte[]{CMD_REPLY_ERROR, error.id, 0x00});
    }

    private void sendTextPayload(final byte[] header, final String text) {
        final byte[] textBytes = StringUtils.ensureNotNull(text).getBytes(StandardCharsets.UTF_8);
        final byte[] ellipsis = "…".getBytes(StandardCharsets.UTF_8);
        final boolean truncated = textBytes.length > MAX_REPLY_TEXT_BYTES - ellipsis.length;
        int textLength = Math.min(textBytes.length, MAX_REPLY_TEXT_BYTES - ellipsis.length);
        if (truncated) {
            while (textLength > 0 && (textBytes[textLength] & 0xC0) == 0x80) {
                textLength--;
            }
            LOG.warn("Reply text truncated from {} to {} bytes (band display limit)", textBytes.length, textLength);
        }
        final ByteBuffer buf = ByteBuffer.allocate(header.length + textLength + (truncated ? ellipsis.length : 0) + 1).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(header);
        buf.put(textBytes, 0, textLength);
        if (truncated) {
            buf.put(ellipsis);
        }
        buf.put((byte) 0);
        write(buf.array());
    }

    public void dispose() {
        handler.removeCallbacksAndMessages(null);
        if (opusDecoder != null) opusDecoder = null;
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        voiceBuffer.clear();
    }

    private void write(byte[] data) {
        support.writeToChunked2021("voice assistant", HuamiSupport.CHUNKED2021_ENDPOINT_ALEXA, data, true);
    }
}