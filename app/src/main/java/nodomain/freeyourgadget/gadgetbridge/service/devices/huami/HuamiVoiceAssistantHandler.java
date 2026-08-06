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
    private static final byte CMD_REPLY_SIMPLE = 0x09;
    private static final byte CMD_LANGUAGES_REQUEST = 0x10;
    private static final byte CMD_LANGUAGES_RESPONSE = 0x11;
    private static final byte CMD_SET_LANGUAGE_ACK = 0x13;
    private static final byte CMD_CAPABILITIES_REQUEST = 0x20;
    private static final byte CMD_CAPABILITIES_RESPONSE = 0x21;

    private static final int CHANNELS = 1;
    private static final int MAX_FRAME_SIZE = 6 * 960;

    private final HuamiSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OpusDecoder opusDecoder;
    private AudioTrack audioTrack;
    private final ByteBuffer voiceBuffer = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
    private int mVersion = -1;

    public HuamiVoiceAssistantHandler(final HuamiSupport support) {
        this.support = support;
    }

    public void handlePayload(final byte[] payload) {
        switch (payload[0]) {
            case CMD_START:
                LOG.info("Assistant starting");
                try {
                    opusDecoder = new OpusDecoder(16000, 1);
                } catch (final OpusException e) {
                    LOG.error("Failed to initialize opus decoder", e);
                    return;
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
                dispose();
                break;
            case CMD_VOICE_DATA:
                handleVoiceData(payload);
                break;
            case CMD_CAPABILITIES_RESPONSE:
                mVersion = payload[1] & 0xFF;
                final byte var1 = payload[2];
                final byte var2 = payload[3];
                LOG.info("Assistant capabilities version={}, var1={}, var2={}", mVersion, var1, var2);
                if (mVersion != 3 && mVersion != 5) {
                    LOG.warn("Unsupported assistant service version {}", mVersion);
                    return;
                }
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
        write(new byte[]{CMD_START_ACK, 0x00});
    }

    public void sendReply(final String text) {
        final byte[] textBytes = StringUtils.ensureNotNull(text).getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(textBytes.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_REPLY_SIMPLE);
        buf.put(textBytes);
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