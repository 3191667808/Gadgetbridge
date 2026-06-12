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

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

import org.bouncycastle.shaded.crypto.InvalidCipherTextException;
import org.bouncycastle.shaded.crypto.engines.AESEngine;
import org.bouncycastle.shaded.crypto.modes.CCMBlockCipher;
import org.bouncycastle.shaded.crypto.params.AEADParameters;
import org.bouncycastle.shaded.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import nodomain.freeyourgadget.gadgetbridge.devices.fitbit.FitbitConstants;

final class FitbitDtls {
    private static final Logger LOG = LoggerFactory.getLogger(FitbitDtls.class);
    // Development-only plaintext diagnostics. Remove before making Fitbit support non-experimental.
    private static final boolean LOG_DEVELOPMENT_PLAINTEXT = true;

    private static final int IPV4_MIN_HEADER_LENGTH = 20;
    private static final int UDP_HEADER_LENGTH = 8;
    private static final int UDP_PROTOCOL = 17;
    private static final int DTLS_RECORD_HEADER_LENGTH = 13;
    private static final int DTLS_HANDSHAKE_HEADER_LENGTH = 12;
    private static final int COAPS_PORT = 5684;

    private static final int DTLS_CONTENT_TYPE_CHANGE_CIPHER_SPEC = 0x14;
    private static final int DTLS_CONTENT_TYPE_ALERT = 0x15;
    private static final int DTLS_CONTENT_TYPE_HANDSHAKE = 0x16;
    private static final int DTLS_CONTENT_TYPE_APPLICATION_DATA = 0x17;
    private static final int DTLS_EPOCH_APPLICATION = 1;
    private static final int DTLS_HANDSHAKE_TYPE_CLIENT_HELLO = 0x01;
    private static final int DTLS_HANDSHAKE_TYPE_SERVER_HELLO = 0x02;
    private static final int DTLS_HANDSHAKE_TYPE_SERVER_HELLO_DONE = 0x0e;
    private static final int DTLS_HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE = 0x10;
    private static final int DTLS_HANDSHAKE_TYPE_FINISHED = 0x14;
    private static final int DTLS_ALERT_LEVEL_FATAL = 0x02;
    private static final int DTLS_ALERT_DESCRIPTION_UNKNOWN_PSK_IDENTITY = 0x73;

    private static final int FITBIT_DTLS_CIPHER_SUITE = 0xc0a4;
    private static final int AES_128_CCM_TAG_LENGTH_BYTES = 16;
    private static final int AES_128_CCM_TAG_LENGTH_BITS = AES_128_CCM_TAG_LENGTH_BYTES * 8;
    private static final int AES_128_CCM_KEY_LENGTH = 16;
    private static final int AES_128_CCM_FIXED_IV_LENGTH = 4;
    private static final int TLS_MASTER_SECRET_LENGTH = 48;
    private static final int TLS_FINISHED_VERIFY_DATA_LENGTH = 12;
    private static final int COAP_TYPE_CONFIRMABLE = 0;
    private static final int COAP_TYPE_ACKNOWLEDGEMENT = 2;
    private static final int COAP_CODE_GET = 0x01;
    private static final int COAP_CODE_POST = 0x02;
    private static final int COAP_CODE_PUT = 0x03;
    private static final int COAP_CODE_CHANGED = 0x44;
    private static final int COAP_CODE_CONTENT = 0x45;
    private static final int COAP_CODE_CONTINUE = 0x5f;
    private static final int COAP_CODE_UNAUTHORIZED = 0x81;
    private static final int COAP_OPTION_URI_PATH = 11;
    private static final int COAP_OPTION_URI_QUERY = 15;
    private static final int COAP_OPTION_BLOCK2 = 23;
    private static final int COAP_OPTION_BLOCK1 = 27;
    private static final int COAP_PAYLOAD_MARKER = 0xff;
    private static final int FITBIT_COAP_BLOCK_SIZE_1024 = 0x06;
    private static final int FITBIT_COAP_BLOCK_SIZE_1024_BYTES = 1024;
    private static final int FITBIT_ONBOARDING_TOKEN_BASE = 0x456723c6;
    private static final int FITBIT_ONBOARDING_MAX_SYNC_DUMP_BLOCKS = 64;
    private static final int FITBIT_PAIR_SYNC_ENVELOPE_LENGTH = 21;
    private static final int FITBIT_SYNC_RESPONSE_FIXTURE_MAX_LENGTH = 256 * 1024;
    private static final int FITBIT_PAIRING_CODE_FILE_MAX_LENGTH = 32;
    private static final int FITBIT_DEVELOPMENT_LOG_HEX_CHUNK_BYTES = 1024;
    private static final int PROTOBUF_WIRE_TYPE_VARINT = 0;
    private static final int PROTOBUF_WIRE_TYPE_64_BIT = 1;
    private static final int PROTOBUF_WIRE_TYPE_LENGTH_DELIMITED = 2;
    private static final int PROTOBUF_WIRE_TYPE_32_BIT = 5;

    private static final String BOOTSTRAP_IDENTITY = "BOOTSTRAP";
    private static final String MOBILE_DATA_IDENTITY_PREFIX = "MD-";
    private static final String FITBIT_SYNC_RESPONSE_FIXTURE_DIRECTORY = FitbitConstants.SYNC_RESPONSE_FIXTURE_DIRECTORY;
    private static final String FITBIT_PAIRING_CODE_FILE = FitbitConstants.PAIRING_CODE_FILE;
    private static final String FITBIT_ONBOARDING_DEBUG_REVISION = "official-sequence-response-envelope-extended-error-20260612";
    private static final byte[] CHANGE_CIPHER_SPEC_BODY = new byte[]{0x01};
    private static final byte[] SERVER_HELLO_EXTENSIONS = new byte[]{
            (byte) 0xff, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x17, 0x00, 0x00,
    };

    private final SecureRandom secureRandom = new SecureRandom();
    private final PskResolver pskResolver;
    private int ipIdentification = 1;
    private HandshakeState handshakeState;
    private DtlsSession dtlsSession;
    private String pendingPairingCode;

    FitbitDtls(final PskResolver pskResolver) {
        this.pskResolver = pskResolver;
    }

    void reset() {
        ipIdentification = 1;
        handshakeState = null;
        dtlsSession = null;
        pendingPairingCode = null;
    }

    boolean setPairingCode(final String pairingCode) {
        if (pairingCode == null || !pairingCode.matches("\\d{4}")) {
            LOG.warn("Fitbit onboarding script: ignoring invalid live pairing code '{}'", pairingCode);
            return false;
        }

        pendingPairingCode = pairingCode;
        LOG.warn("Fitbit onboarding script: DEBUG RAW live Fitbit pairing code={}", pairingCode);
        LOG.warn("Fitbit onboarding script: live Fitbit pairing code accepted, codeSha256={}",
                sha256Hex(pairingCode.getBytes(StandardCharsets.US_ASCII)));

        if (dtlsSession == null) {
            LOG.info("Fitbit onboarding script: stored live pairing code before DTLS session exists");
            return true;
        }

        dtlsSession.pairingCode = pairingCode;
        queueSyncDumpPairIfReady();
        if (dtlsSession.syncDumpComplete && !dtlsSession.syncResponseQueued) {
            LOG.info("Fitbit onboarding script: live pairing code received after sync/dump; checking sync/response queue state now");
            queueSyncResponseAfterDumpComplete();
        } else {
            LOG.info("Fitbit onboarding script: live pairing code stored; waiting for syncDumpComplete={} pendingSyncRequestInfoPresent={} syncResponseQueued={}",
                    dtlsSession.syncDumpComplete,
                    dtlsSession.pendingSyncRequestInfo != null,
                    dtlsSession.syncResponseQueued);
        }
        return true;
    }

    byte[] maybeBuildResponse(final byte[] ipv4Packet) {
        final ClientHello clientHello = parseClientHello(ipv4Packet);
        if (clientHello != null) {
            return buildServerHelloResponse(clientHello);
        }

        final ClientKeyExchangeFlight clientFlight = parseClientKeyExchangeFlight(ipv4Packet);
        if (clientFlight != null) {
            return buildServerFinishedResponse(clientFlight);
        }

        return buildApplicationDataResponse(ipv4Packet);
    }

    byte[] pollOutboundPacket() {
        if (dtlsSession == null || dtlsSession.pendingApplicationRecords.isEmpty()) {
            return null;
        }

        final byte[] dtlsPayload = drainPendingApplicationRecords();
        final byte[] response = buildIpv4UdpResponse(dtlsSession.endpoint, dtlsPayload);
        LOG.info("Fitbit DTLS outbound application packet: dtlsLen={}, ipLen={}",
                dtlsPayload.length,
                response.length);
        return response;
    }

    private byte[] buildServerHelloResponse(final ClientHello clientHello) {
        LOG.info("Fitbit DTLS ClientHello: recordVersion={}, clientVersion={}, cipherSuites={}, udp={}->{}",
                formatU16(clientHello.recordVersion),
                formatU16(clientHello.clientVersion),
                formatCipherSuites(clientHello.cipherSuites),
                clientHello.endpoint.sourcePort,
                clientHello.endpoint.destinationPort);

        if (!clientHello.offersCipherSuite(FITBIT_DTLS_CIPHER_SUITE)) {
            LOG.warn("Fitbit DTLS ClientHello does not offer expected cipher suite {}",
                    formatU16(FITBIT_DTLS_CIPHER_SUITE));
            return null;
        }

        dtlsSession = null;

        final byte[] serverHelloBody = buildServerHelloBody(clientHello);
        final byte[] serverHello = buildHandshakeMessage(
                DTLS_HANDSHAKE_TYPE_SERVER_HELLO,
                0,
                serverHelloBody
        );
        final byte[] serverHelloRecord = buildDtlsRecord(
                DTLS_CONTENT_TYPE_HANDSHAKE,
                clientHello.recordVersion,
                0,
                0,
                serverHello
        );

        final byte[] serverHelloDone = buildHandshakeMessage(
                DTLS_HANDSHAKE_TYPE_SERVER_HELLO_DONE,
                1,
                new byte[0]
        );
        final byte[] serverHelloDoneRecord = buildDtlsRecord(
                DTLS_CONTENT_TYPE_HANDSHAKE,
                clientHello.recordVersion,
                0,
                1,
                serverHelloDone
        );

        handshakeState = new HandshakeState(
                clientHello,
                Arrays.copyOfRange(serverHelloBody, 2, 34),
                serverHello,
                serverHelloDone
        );

        final byte[] dtlsPayload = concat(serverHelloRecord, serverHelloDoneRecord);
        final byte[] response = buildIpv4UdpResponse(clientHello.endpoint, dtlsPayload);
        LOG.info("Fitbit DTLS ServerHello flight: dtlsLen={}, ipLen={}",
                dtlsPayload.length,
                response.length);

        return response;
    }

    private byte[] buildServerFinishedResponse(final ClientKeyExchangeFlight clientFlight) {
        if (handshakeState == null) {
            LOG.warn("Fitbit DTLS ClientKeyExchange received without handshake state");
            return null;
        }

        LOG.info("Fitbit DTLS ClientKeyExchange identity={} {}, encryptedFinishedLen={}",
                clientFlight.identity,
                describeIdentity(clientFlight.identity),
                clientFlight.encryptedFinishedBody.length);

        final byte[] psk = pskResolver.resolvePsk(clientFlight.identity);
        if (psk == null) {
            LOG.warn("Fitbit DTLS PSK unavailable for identity {} {}", clientFlight.identity,
                    describeIdentity(clientFlight.identity));
            if (isMobileDataIdentity(clientFlight.identity)) {
                return buildUnknownPskIdentityAlertResponse(clientFlight);
            }
            return null;
        }

        try {
            final byte[] handshakeTranscript = concat(
                    handshakeState.clientHello.handshakeMessage,
                    handshakeState.serverHello,
                    handshakeState.serverHelloDone,
                    clientFlight.clientKeyExchangeMessage
            );
            final byte[] masterSecret = tlsPrf(
                    buildPskPreMasterSecret(psk),
                    "extended master secret",
                    sha256(handshakeTranscript),
                    TLS_MASTER_SECRET_LENGTH
            );
            final byte[] keyBlock = tlsPrf(
                    masterSecret,
                    "key expansion",
                    concat(handshakeState.serverRandom, handshakeState.clientHello.clientRandom),
                    AES_128_CCM_KEY_LENGTH * 2 + AES_128_CCM_FIXED_IV_LENGTH * 2
            );
            final DtlsKeys keys = new DtlsKeys(keyBlock);

            final byte[] decryptedClientFinished = decryptAes128CcmRecord(
                    keys.clientWriteKey,
                    keys.clientWriteIv,
                    DTLS_CONTENT_TYPE_HANDSHAKE,
                    clientFlight.recordVersion,
                    1,
                    0,
                    clientFlight.encryptedFinishedBody,
                    DTLS_HANDSHAKE_HEADER_LENGTH + TLS_FINISHED_VERIFY_DATA_LENGTH
            );
            final byte[] expectedClientVerifyData = tlsPrf(
                    masterSecret,
                    "client finished",
                    sha256(handshakeTranscript),
                    TLS_FINISHED_VERIFY_DATA_LENGTH
            );

            final byte[] actualClientVerifyData = Arrays.copyOfRange(
                    decryptedClientFinished,
                    DTLS_HANDSHAKE_HEADER_LENGTH,
                    decryptedClientFinished.length
            );
            if (!Arrays.equals(expectedClientVerifyData, actualClientVerifyData)) {
                LOG.warn("Fitbit DTLS client Finished verify_data mismatch for identity {} {}",
                        clientFlight.identity,
                        describeIdentity(clientFlight.identity));
                return null;
            }

            final byte[] serverFinishedVerifyData = tlsPrf(
                    masterSecret,
                    "server finished",
                    sha256(concat(handshakeTranscript, decryptedClientFinished)),
                    TLS_FINISHED_VERIFY_DATA_LENGTH
            );
            final byte[] serverFinished = buildHandshakeMessage(
                    DTLS_HANDSHAKE_TYPE_FINISHED,
                    2,
                    serverFinishedVerifyData
            );
            final byte[] encryptedServerFinished = encryptAes128CcmRecord(
                    keys.serverWriteKey,
                    keys.serverWriteIv,
                    DTLS_CONTENT_TYPE_HANDSHAKE,
                    clientFlight.recordVersion,
                    1,
                    0,
                    serverFinished
            );
            final byte[] serverCcsRecord = buildDtlsRecord(
                    DTLS_CONTENT_TYPE_CHANGE_CIPHER_SPEC,
                    clientFlight.recordVersion,
                    0,
                    2,
                    CHANGE_CIPHER_SPEC_BODY
            );
            final byte[] serverFinishedRecord = buildDtlsRecord(
                    DTLS_CONTENT_TYPE_HANDSHAKE,
                    clientFlight.recordVersion,
                    1,
                    0,
                    encryptedServerFinished
            );

            final byte[] dtlsPayload = concat(serverCcsRecord, serverFinishedRecord);
            final byte[] response = buildIpv4UdpResponse(clientFlight.endpoint, dtlsPayload);
            handshakeState = null;
            dtlsSession = new DtlsSession(clientFlight.endpoint, clientFlight.recordVersion, keys);
            dtlsSession.pairingCode = pendingPairingCode;
            startBootstrapOnboardingScript();
            LOG.info("Fitbit DTLS Server Finished flight: identity={}, dtlsLen={}, ipLen={}",
                    clientFlight.identity,
                    dtlsPayload.length,
                    response.length);
            return response;
        } catch (final Exception e) {
            LOG.warn("Unable to build Fitbit DTLS Server Finished for identity {} {}",
                    clientFlight.identity,
                    describeIdentity(clientFlight.identity),
                    e);
            return null;
        }
    }

    private byte[] buildUnknownPskIdentityAlertResponse(final ClientKeyExchangeFlight clientFlight) {
        final byte[] alertRecord = buildDtlsRecord(
                DTLS_CONTENT_TYPE_ALERT,
                clientFlight.recordVersion,
                0,
                2,
                new byte[]{DTLS_ALERT_LEVEL_FATAL, DTLS_ALERT_DESCRIPTION_UNKNOWN_PSK_IDENTITY}
        );
        final byte[] response = buildIpv4UdpResponse(clientFlight.endpoint, alertRecord);
        handshakeState = null;
        dtlsSession = null;

        LOG.info("Fitbit DTLS sent fatal unknown_psk_identity alert for identity {} {}, dtlsLen={}, ipLen={}",
                clientFlight.identity,
                describeIdentity(clientFlight.identity),
                alertRecord.length,
                response.length);

        return response;
    }

    private byte[] buildApplicationDataResponse(final byte[] packet) {
        if (dtlsSession == null) {
            return null;
        }

        final UdpPacket udpPacket = parseUdpPacket(packet);
        if (udpPacket == null) {
            return null;
        }

        final List<byte[]> responseRecords = new ArrayList<>();
        int recordOffset = udpPacket.dtlsOffset;
        while (recordOffset + DTLS_RECORD_HEADER_LENGTH <= udpPacket.dtlsEnd) {
            final int contentType = packet[recordOffset] & 0xff;
            final int recordVersion = readU16(packet, recordOffset + 1);
            final int epoch = readU16(packet, recordOffset + 3);
            final long sequenceNumber = readU48Long(packet, recordOffset + 5);
            final int recordLength = readU16(packet, recordOffset + 11);
            final int recordBodyOffset = recordOffset + DTLS_RECORD_HEADER_LENGTH;
            final int recordEnd = recordBodyOffset + recordLength;
            if (recordEnd > udpPacket.dtlsEnd) {
                return null;
            }

            if (contentType == DTLS_CONTENT_TYPE_APPLICATION_DATA && epoch == DTLS_EPOCH_APPLICATION) {
                final byte[] responseRecord = buildApplicationDataRecordResponse(
                        packet,
                        recordBodyOffset,
                        recordEnd,
                        recordVersion,
                        sequenceNumber
                );
                if (responseRecord != null) {
                    responseRecords.add(responseRecord);
                }
            } else if (contentType == DTLS_CONTENT_TYPE_ALERT) {
                LOG.info("Fitbit DTLS alert record received: epoch={}, sequence={}, len={}",
                        epoch,
                        sequenceNumber,
                        recordLength);
            }

            recordOffset = recordEnd;
        }

        appendPendingApplicationRecords(responseRecords);
        if (responseRecords.isEmpty()) {
            return null;
        }

        final byte[] dtlsPayload = concat(responseRecords);
        final byte[] response = buildIpv4UdpResponse(udpPacket.endpoint, dtlsPayload);
        LOG.info("Fitbit DTLS application response: records={}, dtlsLen={}, ipLen={}",
                responseRecords.size(),
                dtlsPayload.length,
                response.length);
        return response;
    }

    private byte[] buildApplicationDataRecordResponse(final byte[] packet,
                                                       final int recordBodyOffset,
                                                       final int recordEnd,
                                                       final int recordVersion,
                                                       final long sequenceNumber) {
        final byte[] encryptedBody = Arrays.copyOfRange(packet, recordBodyOffset, recordEnd);
        final int plaintextLength = encryptedBody.length - 8 - AES_128_CCM_TAG_LENGTH_BYTES;
        if (plaintextLength < 0) {
            LOG.warn("Fitbit DTLS application record is too short: sequence={}, encryptedLen={}",
                    sequenceNumber,
                    encryptedBody.length);
            return null;
        }

        final byte[] plaintext;
        try {
            plaintext = decryptAes128CcmRecord(
                    dtlsSession.keys.clientWriteKey,
                    dtlsSession.keys.clientWriteIv,
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    sequenceNumber,
                    encryptedBody,
                    plaintextLength
            );
        } catch (final InvalidCipherTextException e) {
            LOG.warn("Unable to decrypt Fitbit DTLS application data: sequence={}, encryptedLen={}",
                    sequenceNumber,
                    encryptedBody.length,
                    e);
            return null;
        }

        if (sequenceNumber >= dtlsSession.nextClientApplicationSequenceNumber) {
            dtlsSession.nextClientApplicationSequenceNumber = sequenceNumber + 1;
        }

        final CoapMessage request = parseCoapMessage(plaintext);
        if (request == null) {
            LOG.warn("Unable to parse Fitbit CoAP request: sequence={}, len={}",
                    sequenceNumber,
                    plaintext.length);
            return null;
        }

        LOG.info("Fitbit CoAP request: sequence={}, type={}, code={}, mid={}, token={}, path={}, payloadLen={}",
                sequenceNumber,
                formatCoapType(request.type),
                formatCoapCode(request.code),
                request.messageId,
                toHex(request.token),
                request.path,
                request.payloadLength);
        if (LOG_DEVELOPMENT_PLAINTEXT) {
            LOG.info("Fitbit CoAP request plaintext: sequence={}, coap={}, payload={}",
                    sequenceNumber,
                    toHex(plaintext),
                    toHex(request.payload));
        }
        if (isCoapErrorResponse(request.code)) {
            final GoldenGateExtendedError extendedError = decodeGoldenGateExtendedError(request.payload);
            if (extendedError != null) {
                LOG.warn("Fitbit CoAP ExtendedError response: sequence={}, code={}, mid={}, token={}, path={}, namespace={}, code={}, rawCode={}, message={}, payload={}",
                        sequenceNumber,
                        formatCoapCode(request.code),
                        request.messageId,
                        toHex(request.token),
                        request.path,
                        extendedError.namespace,
                        extendedError.code,
                        extendedError.rawCode,
                        extendedError.message,
                        toHex(request.payload));
            }
        }
        handleBootstrapOnboardingMessage(request);

        final Integer responseCode = resolveCoapResponseCode(request);
        if (responseCode == null) {
            LOG.info("Fitbit CoAP request not handled yet: path={}, code={}, mid={}",
                    request.path,
                    formatCoapCode(request.code),
                    request.messageId);
            return null;
        }

        final byte[] coapResponse = buildCoapAckResponse(request, responseCode);
        if (LOG_DEVELOPMENT_PLAINTEXT) {
            LOG.info("Fitbit CoAP response plaintext: sequence={}, code={}, mid={}, path={}, coap={}",
                    dtlsSession.nextServerApplicationSequenceNumber,
                    formatCoapCode(responseCode),
                    request.messageId,
                    request.path,
                    toHex(coapResponse));
        }
        final long responseSequenceNumber = dtlsSession.nextServerApplicationSequenceNumber++;
        try {
            final byte[] encryptedResponse = encryptAes128CcmRecord(
                    dtlsSession.keys.serverWriteKey,
                    dtlsSession.keys.serverWriteIv,
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    dtlsSession.recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    responseSequenceNumber,
                    coapResponse
            );

            LOG.info("Fitbit CoAP response: sequence={}, code={}, mid={}, token={}, path={}",
                    responseSequenceNumber,
                    formatCoapCode(responseCode),
                    request.messageId,
                    toHex(request.token),
                    request.path);
            return buildDtlsRecord(
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    dtlsSession.recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    responseSequenceNumber,
                    encryptedResponse
            );
        } catch (final InvalidCipherTextException e) {
            LOG.warn("Unable to encrypt Fitbit CoAP response: path={}, mid={}",
                    request.path,
                    request.messageId,
                    e);
            return null;
        }
    }

    private void startBootstrapOnboardingScript() {
        if (dtlsSession == null) {
            return;
        }

        LOG.info("Fitbit onboarding script: queue pair/display on");
        LOG.warn("Fitbit onboarding script: DEBUG REVISION {}", FITBIT_ONBOARDING_DEBUG_REVISION);
        queuePairDisplayRequest(true);
    }

    private void handleBootstrapOnboardingMessage(final CoapMessage message) {
        if (dtlsSession == null) {
            return;
        }

        if (message.type == COAP_TYPE_CONFIRMABLE && message.code == COAP_CODE_POST) {
            handleWatchPostForOnboarding(message);
            return;
        }

        if (message.type != COAP_TYPE_ACKNOWLEDGEMENT) {
            return;
        }

        if (message.messageId == dtlsSession.pairDisplayOnMessageId
                && !dtlsSession.pairDisplayOnAcknowledged) {
            dtlsSession.pairDisplayOnAcknowledged = true;
            LOG.info("Fitbit onboarding script: pair/display on acknowledged with code {}, path={}",
                    formatCoapCode(message.code),
                    message.path);
            queueSyncConfigRequest();
            return;
        }

        if (message.messageId == dtlsSession.pairDisplayOffMessageId
                && !dtlsSession.pairDisplayOffAcknowledged) {
            dtlsSession.pairDisplayOffAcknowledged = true;
            LOG.info("Fitbit onboarding script: pair/display off acknowledged with code {}, path={}",
                    formatCoapCode(message.code),
                    message.path);
            return;
        }

        if (message.messageId == dtlsSession.syncConfigMessageId
                && !dtlsSession.syncConfigAcknowledged) {
            dtlsSession.syncConfigAcknowledged = true;
            LOG.info("Fitbit onboarding script: sync/config completed with code {}, path={}",
                    formatCoapCode(message.code),
                    message.path);
            queueSyncDumpPairIfReady();
            return;
        }

        if (message.code == COAP_CODE_CONTENT
                && message.messageId == dtlsSession.syncDumpPairMessageId
                && !dtlsSession.syncDumpPairAcknowledged) {
            dtlsSession.syncDumpPairAcknowledged = true;
            LOG.info("Fitbit onboarding script: sync/dump?t=pair returned {} bytes, path={}",
                    message.payloadLength,
                    message.path);
            LOG.info("Fitbit onboarding script: sync/dump?t=pair payload prefix={}",
                    toHexPrefix(message.payload, 32));
            logDevelopmentHexChunks("sync/dump?t=pair payload", message.payload);
            LOG.warn("Fitbit onboarding script: keeping pair/display visible until sync/response completes");
            queueNextSyncDumpBlockRequest(0);
            return;
        }

        if (message.code == COAP_CODE_CONTENT
                && dtlsSession.syncDumpMessageIds.remove(message.messageId)) {
            LOG.info("Fitbit onboarding script: sync/dump response mid={} returned {} bytes, path={}, block2={}/{} more={}",
                    message.messageId,
                    message.payloadLength,
                    message.path,
                    message.block2Number,
                    message.block2SizeExponent,
                    message.block2More);
            dtlsSession.syncDumpPayload.write(message.payload, 0, message.payload.length);
            dtlsSession.syncDumpPayloadLength = dtlsSession.syncDumpPayload.size();
            logDevelopmentHexChunks("sync/dump block " + message.block2Number + " payload", message.payload);
            if (message.hasBlock2 && message.block2More) {
                queueNextSyncDumpBlockRequest(message.block2Number + 1);
            } else {
                dtlsSession.syncDumpComplete = true;
                logCompletedSyncDumpPayload();
                queueSyncResponseAfterDumpComplete();
            }
            return;
        }

        if (dtlsSession.syncResponseMessageIds.remove(message.messageId)) {
            LOG.info("Fitbit onboarding script: sync/response block reply code={}, mid={}, path={}, block1={}/{} more={}, payloadLen={}",
                    formatCoapCode(message.code),
                    message.messageId,
                    message.path,
                    message.block1Number,
                    message.block1SizeExponent,
                    message.block1More,
                    message.payloadLength);
            if (message.code == COAP_CODE_CONTINUE) {
                queueNextSyncResponseBlock();
            } else if (message.code == COAP_CODE_CHANGED) {
                dtlsSession.syncResponseComplete = true;
                dtlsSession.syncResponseFailed = false;
                LOG.info("Fitbit onboarding script: sync/response transfer complete, payloadLen={}, sha256={}",
                        dtlsSession.syncResponsePayload == null ? 0 : dtlsSession.syncResponsePayload.length,
                        dtlsSession.syncResponsePayload == null ? "" : sha256Hex(dtlsSession.syncResponsePayload));
                queuePairDisplayRequest(false);
            } else {
                dtlsSession.syncResponseFailed = true;
                final GoldenGateExtendedError extendedError = decodeGoldenGateExtendedError(message.payload);
                if (extendedError != null) {
                    LOG.warn("Fitbit onboarding script: sync/response block returned unexpected code {}, mid={}, extendedErrorNamespace={}, extendedErrorCode={}, extendedErrorRawCode={}, extendedErrorMessage={}, payload={}",
                            formatCoapCode(message.code),
                            message.messageId,
                            extendedError.namespace,
                            extendedError.code,
                            extendedError.rawCode,
                            extendedError.message,
                            toHex(message.payload));
                } else {
                    LOG.warn("Fitbit onboarding script: sync/response block returned unexpected code {}, mid={}, payload={}",
                            formatCoapCode(message.code),
                            message.messageId,
                            toHex(message.payload));
                }
            }
        }
    }

    private void handleWatchPostForOnboarding(final CoapMessage message) {
        if ("/md/3d02".equals(message.path) && dtlsSession.handledMetadataMessageIds.add(message.messageId)) {
            final AppLifecycleInfo appLifecycleInfo = parseAppLifecycleInfo(message.payload);
            LOG.info("Fitbit onboarding script: app lifecycle request {}/4 mid={} appUuid={} appBuildId={} eventType={}/{} errorType={}/{} payload={}",
                    dtlsSession.handledMetadataMessageIds.size(),
                    message.messageId,
                    appLifecycleInfo.appUuidText,
                    appLifecycleInfo.appBuildId,
                    appLifecycleInfo.eventType,
                    appLifecycleEventName(appLifecycleInfo.eventType),
                    appLifecycleInfo.errorType,
                    appLifecycleErrorName(appLifecycleInfo.errorType),
                    toHex(message.payload));
            queueSyncDumpPairIfReady();
            return;
        }

        if ("/sync/request".equals(message.path) && dtlsSession.handledSyncRequestMessageIds.add(message.messageId)) {
            final SyncRequestInfo syncRequestInfo = parseSyncRequestInfo(message.payload);
            dtlsSession.pendingSyncRequestInfo = syncRequestInfo;
            LOG.info("Fitbit onboarding script: sync/request mid={} eventType={} requestUuid={} retryCount={} payload={}",
                    message.messageId,
                    syncRequestInfo.eventType,
                    syncRequestInfo.requestUuidText,
                    syncRequestInfo.retryCount,
                    toHex(message.payload));
            queueSyncResponse(syncRequestInfo);
        }
    }

    private void queueSyncResponseAfterDumpComplete() {
        if (dtlsSession == null || !dtlsSession.syncDumpComplete) {
            return;
        }

        if (dtlsSession.pendingSyncRequestInfo != null) {
            queueSyncResponse(dtlsSession.pendingSyncRequestInfo);
            return;
        }

        if (!BuildConfig.DEBUG) {
            LOG.info("Fitbit onboarding script: no /sync/request observed before sync/dump completed; waiting for /sync/request before sending sync/response");
            return;
        }

        final SyncRequestInfo syntheticSyncRequestInfo = new SyncRequestInfo("", new byte[0], 0);
        dtlsSession.pendingSyncRequestInfo = syntheticSyncRequestInfo;
        LOG.warn("Fitbit onboarding script: no /sync/request observed before sync/dump completed; DEBUG synthesizing empty sync/request metadata so /sync/response envelope and Block1 mechanics can be tested");
        queueSyncResponse(syntheticSyncRequestInfo);
    }

    private void logCompletedSyncDumpPayload() {
        final byte[] syncDumpPayload = dtlsSession.syncDumpPayload.toByteArray();
        LOG.info("Fitbit onboarding script: sync/dump block transfer complete, totalPayloadLen={}, dumpSha256={}, prefix={}",
                syncDumpPayload.length,
                sha256Hex(syncDumpPayload),
                toHexPrefix(syncDumpPayload, 64));
        logDevelopmentHexChunks("sync/dump complete payload", syncDumpPayload);
    }

    private void queueSyncDumpPairIfReady() {
        if (dtlsSession == null || dtlsSession.syncDumpPairQueued) {
            return;
        }

        if (!dtlsSession.syncConfigAcknowledged || dtlsSession.handledMetadataMessageIds.size() < 4) {
            return;
        }

        if (dtlsSession.pairingCode == null) {
            LOG.info("Fitbit onboarding script: waiting for 4-digit pairing code before sync/dump?t=pair; syncConfigAcknowledged={}, metadataRequests={}",
                    dtlsSession.syncConfigAcknowledged,
                    dtlsSession.handledMetadataMessageIds.size());
            return;
        }

        dtlsSession.syncDumpPairQueued = true;
        dtlsSession.syncDumpPairMessageId = queueCoapRequest(
                "sync/dump?t=pair",
                COAP_CODE_GET,
                new String[]{"sync", "dump"},
                new String[]{"t=pair"},
                null,
                null,
                new byte[0]
        );
    }

    private void queuePairDisplayRequest(final boolean enabled) {
        final int messageId = queueCoapRequest(
                enabled ? "pair/display on" : "pair/display off",
                COAP_CODE_PUT,
                new String[]{"pair", "display"},
                new String[0],
                null,
                FITBIT_COAP_BLOCK_SIZE_1024,
                new byte[]{0x08, (byte) (enabled ? 0x01 : 0x00)}
        );
        if (enabled) {
            dtlsSession.pairDisplayOnMessageId = messageId;
        } else {
            dtlsSession.pairDisplayOffMessageId = messageId;
        }
    }

    private void queueSyncConfigRequest() {
        if (dtlsSession.syncConfigQueued) {
            return;
        }

        dtlsSession.syncConfigQueued = true;
        dtlsSession.syncConfigMessageId = queueCoapRequest(
                "sync/config",
                COAP_CODE_GET,
                new String[]{"sync", "config"},
                new String[0],
                null,
                null,
                new byte[0]
        );
    }

    private void queueNextSyncDumpBlockRequest(final int blockNumber) {
        if (dtlsSession.syncDumpComplete) {
            return;
        }

        if (blockNumber >= FITBIT_ONBOARDING_MAX_SYNC_DUMP_BLOCKS) {
            LOG.info("Fitbit onboarding script: reached sync/dump block limit {}",
                    FITBIT_ONBOARDING_MAX_SYNC_DUMP_BLOCKS);
            return;
        }

        final Integer block2 = blockNumber == 0
                ? null
                : encodeCoapBlockOption(blockNumber, false, FITBIT_COAP_BLOCK_SIZE_1024);
        final int messageId = queueCoapRequest(
                "sync/dump block " + blockNumber,
                COAP_CODE_GET,
                new String[]{"sync", "dump"},
                new String[0],
                block2,
                null,
                new byte[0]
        );
        dtlsSession.syncDumpMessageIds.add(messageId);
        dtlsSession.nextSyncDumpBlockNumber = blockNumber + 1;
    }

    private void queueSyncResponse(final SyncRequestInfo syncRequestInfo) {
        if (dtlsSession == null) {
            return;
        }

        if (dtlsSession.syncResponseQueued) {
            if (!dtlsSession.syncResponseFailed) {
                LOG.info("Fitbit onboarding script: sync/response already queued; ignoring duplicate sync/request eventType={} requestUuid={}",
                        syncRequestInfo.eventType,
                        syncRequestInfo.requestUuidText);
                return;
            }

            LOG.info("Fitbit onboarding script: retrying sync/response after previous failure, eventType={} requestUuid={}",
                    syncRequestInfo.eventType,
                    syncRequestInfo.requestUuidText);
            dtlsSession.syncResponseMessageIds.clear();
            dtlsSession.syncResponsePayload = null;
            dtlsSession.syncResponseRequestInfo = null;
            dtlsSession.syncResponseQueued = false;
            dtlsSession.syncResponseComplete = false;
            dtlsSession.nextSyncResponseBlockNumber = 0;
            dtlsSession.syncResponseFailed = false;
        }

        if (!dtlsSession.syncDumpComplete) {
            LOG.info("Fitbit onboarding script: deferring sync/response until sync/dump is complete");
            return;
        }

        try {
            dtlsSession.syncResponsePayload = buildSyncResponsePayload(syncRequestInfo);
            if (dtlsSession.syncResponsePayload == null) {
                LOG.warn("Fitbit onboarding script: sync/response payload not queued; waiting for required debug inputs before answering eventType={} requestUuid={}",
                        syncRequestInfo.eventType,
                        syncRequestInfo.requestUuidText);
                return;
            }

            dtlsSession.syncResponseRequestInfo = syncRequestInfo;
            dtlsSession.syncResponseQueued = true;
            dtlsSession.nextSyncResponseBlockNumber = 0;
            LOG.info("Fitbit onboarding script: generated sync/response payload len={}, sha256={}, prefix={}, eventType={}, requestUuid={}, dumpSha256={}",
                    dtlsSession.syncResponsePayload.length,
                    sha256Hex(dtlsSession.syncResponsePayload),
                    toHexPrefix(dtlsSession.syncResponsePayload, 64),
                    syncRequestInfo.eventType,
                    syncRequestInfo.requestUuidText,
                    sha256Hex(dtlsSession.syncDumpPayload.toByteArray()));
            logDevelopmentHexChunks("sync/response complete payload queued", dtlsSession.syncResponsePayload);
            LOG.info("Fitbit onboarding script: Fitbit app JADX maps /sync/response to the decoded backend pair response; debug fixtures are analysis specimens until a local generator is implemented");
            queueNextSyncResponseBlock();
        } catch (final NoSuchAlgorithmException e) {
            LOG.warn("Unable to generate Fitbit sync/response body", e);
        }
    }

    private byte[] buildSyncResponsePayload(final SyncRequestInfo syncRequestInfo)
            throws NoSuchAlgorithmException {
        final byte[] dumpPayload = dtlsSession.syncDumpPayload.toByteArray();
        final byte[] dumpHash = sha256(dumpPayload);
        final String dumpHashHex = toHex(dumpHash);
        final String pairingCode = dtlsSession.pairingCode != null
                ? dtlsSession.pairingCode
                : loadDebugPairingCode();
        if (pairingCode == null) {
            LOG.warn("Fitbit onboarding script: Fitbit pairing code is required before /sync/response; enter the 4-digit watch code in the Fitbit pairing screen or create {}/{} in Gadgetbridge external files for debug replay",
                    FITBIT_SYNC_RESPONSE_FIXTURE_DIRECTORY,
                    FITBIT_PAIRING_CODE_FILE);
            return null;
        }

        final byte[] fixtureResponse = loadDebugSyncResponseFixture(dumpHashHex, pairingCode);
        if (fixtureResponse != null) {
            return buildSyncResponseEnvelopeFromCandidate(dumpPayload, fixtureResponse, "debug fixture");
        }

        final byte[] generatedPayload = buildOfflineSyncResponsePayloadAfterEnvelope(
                dumpPayload,
                dumpHashHex,
                pairingCode,
                syncRequestInfo);
        if (generatedPayload != null) {
            return buildSyncResponseEnvelope(dumpPayload, generatedPayload, "offline generator");
        }

        LOG.warn("Fitbit onboarding script: no sync/response payload available for dumpSha256={} pairingCode={} eventType={} requestUuid={}; not sending fabricated response",
                dumpHashHex,
                pairingCode,
                syncRequestInfo.eventType,
                syncRequestInfo.requestUuidText);
        return null;
    }

    private byte[] buildOfflineSyncResponsePayloadAfterEnvelope(final byte[] dumpPayload,
                                                               final String dumpHashHex,
                                                               final String pairingCode,
                                                               final SyncRequestInfo syncRequestInfo) {
        LOG.warn("Fitbit onboarding script: offline /sync/response payload generator is not implemented yet; need to reverse payload after byte {} from official /pair responses. dumpLen={}, dumpSha256={}, pairingCode={}, eventType={}, requestUuid={}",
                FITBIT_PAIR_SYNC_ENVELOPE_LENGTH,
                dumpPayload.length,
                dumpHashHex,
                pairingCode,
                syncRequestInfo.eventType,
                syncRequestInfo.requestUuidText);
        return null;
    }

    private byte[] buildSyncResponseEnvelopeFromCandidate(final byte[] dumpPayload,
                                                          final byte[] responseCandidate,
                                                          final String source) {
        if (isFitbitPairSyncEnvelope(responseCandidate)) {
            final byte[] responsePayload = Arrays.copyOfRange(
                    responseCandidate,
                    FITBIT_PAIR_SYNC_ENVELOPE_LENGTH,
                    responseCandidate.length);
            final byte[] normalized = buildSyncResponseEnvelope(dumpPayload, responsePayload, source);
            if (normalized == null) {
                return null;
            }
            LOG.info("Fitbit onboarding script: normalized {} response envelope, candidateCounter={}, normalizedCounter={}, candidateSha256={}, normalizedSha256={}",
                    source,
                    responseCandidate[5] & 0xff,
                    normalized[5] & 0xff,
                    sha256Hex(responseCandidate),
                    sha256Hex(normalized));
            return normalized;
        }

        LOG.warn("Fitbit onboarding script: treating {} as payload-after-envelope because it does not have Fitbit pair-sync envelope prefix; candidateLen={}, candidateSha256={}",
                source,
                responseCandidate.length,
                sha256Hex(responseCandidate));
        return buildSyncResponseEnvelope(dumpPayload, responseCandidate, source);
    }

    private byte[] buildSyncResponseEnvelope(final byte[] dumpPayload,
                                             final byte[] payloadAfterEnvelope,
                                             final String payloadSource) {
        if (!isFitbitPairSyncEnvelope(dumpPayload)) {
            LOG.warn("Fitbit onboarding script: cannot build sync/response envelope from malformed pair dump; dumpLen={}, prefix={}",
                    dumpPayload.length,
                    toHexPrefix(dumpPayload, FITBIT_PAIR_SYNC_ENVELOPE_LENGTH));
            return null;
        }

        final byte[] response = new byte[FITBIT_PAIR_SYNC_ENVELOPE_LENGTH + payloadAfterEnvelope.length];
        System.arraycopy(dumpPayload, 0, response, 0, FITBIT_PAIR_SYNC_ENVELOPE_LENGTH);
        response[5] = (byte) ((dumpPayload[5] + 1) & 0xff);
        System.arraycopy(payloadAfterEnvelope, 0, response, FITBIT_PAIR_SYNC_ENVELOPE_LENGTH, payloadAfterEnvelope.length);
        LOG.info("Fitbit onboarding script: built sync/response envelope from {}, pairCounter={}, responseCounter={}, dwid={}, payloadLen={}, responseLen={}, responseSha256={}",
                payloadSource,
                dumpPayload[5] & 0xff,
                response[5] & 0xff,
                toHex(Arrays.copyOfRange(response, 9, 15)),
                payloadAfterEnvelope.length,
                response.length,
                sha256Hex(response));
        return response;
    }

    private boolean isFitbitPairSyncEnvelope(final byte[] payload) {
        return payload != null
                && payload.length > FITBIT_PAIR_SYNC_ENVELOPE_LENGTH
                && payload[0] == 0x03
                && payload[1] == 0x04
                && payload[2] == 0x00
                && payload[3] == 0x00
                && payload[4] == 0x02;
    }

    private String loadDebugPairingCode() {
        if (!BuildConfig.DEBUG) {
            return null;
        }

        try {
            final File fixtureDirectory = new File(FileUtils.getExternalFilesDir(), FITBIT_SYNC_RESPONSE_FIXTURE_DIRECTORY);
            if (!fixtureDirectory.exists() && !fixtureDirectory.mkdirs()) {
                LOG.warn("Fitbit onboarding script: unable to create sync/response fixture directory {}",
                        fixtureDirectory.getAbsolutePath());
                return null;
            }

            final File codeFile = new File(fixtureDirectory, FITBIT_PAIRING_CODE_FILE);
            if (!codeFile.isFile()) {
                LOG.info("Fitbit onboarding script: no debug Fitbit pairing code file {}, code-specific sync/response fixtures disabled",
                        codeFile.getAbsolutePath());
                return null;
            }

            if (dtlsSession != null && codeFile.lastModified() < dtlsSession.startedAtMillis) {
                LOG.warn("Fitbit onboarding script: ignoring stale debug Fitbit pairing code file {} lastModified={} sessionStarted={}",
                        codeFile.getAbsolutePath(),
                        codeFile.lastModified(),
                        dtlsSession.startedAtMillis);
                return null;
            }

            if (codeFile.length() <= 0 || codeFile.length() > FITBIT_PAIRING_CODE_FILE_MAX_LENGTH) {
                LOG.warn("Fitbit onboarding script: ignoring debug Fitbit pairing code file {} because len={} is invalid",
                        codeFile.getAbsolutePath(),
                        codeFile.length());
                return null;
            }

            final String pairingCode = new String(readFileFully(codeFile), StandardCharsets.US_ASCII).trim();
            if (!pairingCode.matches("\\d{4}")) {
                LOG.warn("Fitbit onboarding script: ignoring debug Fitbit pairing code file {} because it must contain exactly 4 digits",
                        codeFile.getAbsolutePath());
                return null;
            }

            LOG.warn("Fitbit onboarding script: using debug Fitbit pairing code from {}, codeSha256={}",
                    codeFile.getAbsolutePath(),
                    sha256Hex(pairingCode.getBytes(StandardCharsets.US_ASCII)));
            LOG.warn("Fitbit onboarding script: DEBUG RAW Fitbit pairing code={}", pairingCode);
            return pairingCode;
        } catch (final IOException e) {
            LOG.warn("Fitbit onboarding script: unable to load debug Fitbit pairing code", e);
            return null;
        }
    }

    private byte[] loadDebugSyncResponseFixture(final String dumpSha256, final String pairingCode) {
        if (!BuildConfig.DEBUG) {
            return null;
        }

        try {
            final File fixtureDirectory = new File(FileUtils.getExternalFilesDir(), FITBIT_SYNC_RESPONSE_FIXTURE_DIRECTORY);
            if (!fixtureDirectory.exists() && !fixtureDirectory.mkdirs()) {
                LOG.warn("Fitbit onboarding script: unable to create sync/response fixture directory {}",
                        fixtureDirectory.getAbsolutePath());
                return null;
            }

            final File fixtureFile = findDebugSyncResponseFixture(fixtureDirectory, dumpSha256, pairingCode);
            if (fixtureFile == null) {
                LOG.info("Fitbit onboarding script: no debug sync/response fixture for dumpSha256={}, pairingCodePresent={}, fixtureDir={}",
                        dumpSha256,
                        pairingCode != null,
                        fixtureDirectory.getAbsolutePath());
                return null;
            }

            if (fixtureFile.length() <= 0) {
                LOG.warn("Fitbit onboarding script: ignoring empty sync/response fixture {}",
                        fixtureFile.getAbsolutePath());
                return null;
            }

            if (fixtureFile.length() > FITBIT_SYNC_RESPONSE_FIXTURE_MAX_LENGTH) {
                LOG.warn("Fitbit onboarding script: ignoring sync/response fixture {} because len={} exceeds max={}",
                        fixtureFile.getAbsolutePath(),
                        fixtureFile.length(),
                        FITBIT_SYNC_RESPONSE_FIXTURE_MAX_LENGTH);
                return null;
            }

            final byte[] fixturePayload = readFileFully(fixtureFile);
            LOG.warn("Fitbit onboarding script: using debug sync/response fixture dumpSha256={}, pairingCodePresent={}, responseLen={}, responseSha256={}, path={}",
                    dumpSha256,
                    pairingCode != null,
                    fixturePayload.length,
                    sha256Hex(fixturePayload),
                    fixtureFile.getAbsolutePath());
            logDevelopmentHexChunks("sync/response fixture payload", fixturePayload);
            return fixturePayload;
        } catch (final IOException e) {
            LOG.warn("Fitbit onboarding script: unable to load debug sync/response fixture for dumpSha256={}",
                    dumpSha256,
                    e);
            return null;
        }
    }

    private static File findDebugSyncResponseFixture(final File fixtureDirectory,
                                                    final String dumpSha256,
                                                    final String pairingCode) {
        if (pairingCode != null) {
            final File codeSpecificFixture = new File(new File(new File(fixtureDirectory, dumpSha256), pairingCode), "response.bin");
            logDebugFixtureCandidate(codeSpecificFixture);
            if (codeSpecificFixture.isFile()) {
                return codeSpecificFixture;
            }

            final File codeFlatFixture = new File(new File(fixtureDirectory, dumpSha256), pairingCode + ".response.bin");
            logDebugFixtureCandidate(codeFlatFixture);
            if (codeFlatFixture.isFile()) {
                return codeFlatFixture;
            }

            final File codeOnlyFixture = new File(new File(fixtureDirectory, pairingCode), "response.bin");
            logDebugFixtureCandidate(codeOnlyFixture);
            if (codeOnlyFixture.isFile()) {
                LOG.warn("Fitbit onboarding script: using DEBUG code-only sync/response fixture for pairingCode={} without dumpSha256 match; this is unsafe and only for live perturbation tests",
                        pairingCode);
                return codeOnlyFixture;
            }

            final File codeOnlyFlatFixture = new File(fixtureDirectory, pairingCode + ".response.bin");
            logDebugFixtureCandidate(codeOnlyFlatFixture);
            if (codeOnlyFlatFixture.isFile()) {
                LOG.warn("Fitbit onboarding script: using DEBUG code-only flat sync/response fixture for pairingCode={} without dumpSha256 match; this is unsafe and only for live perturbation tests",
                        pairingCode);
                return codeOnlyFlatFixture;
            }

            LOG.warn("Fitbit onboarding script: no code-specific or code-only fixture found for dumpSha256={} pairingCode={}; refusing dump-only fallback fixtures",
                    dumpSha256,
                    pairingCode);
            return null;
        }

        final File mappedFixture = new File(new File(fixtureDirectory, dumpSha256), "response.bin");
        logDebugFixtureCandidate(mappedFixture);
        if (mappedFixture.isFile()) {
            return mappedFixture;
        }

        final File flatFixture = new File(fixtureDirectory, dumpSha256 + ".bin");
        logDebugFixtureCandidate(flatFixture);
        if (flatFixture.isFile()) {
            return flatFixture;
        }

        final File namedFixture = new File(fixtureDirectory, dumpSha256 + ".response.bin");
        logDebugFixtureCandidate(namedFixture);
        if (namedFixture.isFile()) {
            return namedFixture;
        }

        final File fallbackFixture = new File(fixtureDirectory, "response.bin");
        logDebugFixtureCandidate(fallbackFixture);
        if (fallbackFixture.isFile()) {
            return fallbackFixture;
        }

        return null;
    }

    private static byte[] readFileFully(final File file) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
        final byte[] buffer = new byte[4096];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static void logDebugFixtureCandidate(final File fixtureFile) {
        if (!BuildConfig.DEBUG) {
            return;
        }

        LOG.info("Fitbit onboarding script: checking debug sync/response fixture candidate path={}, exists={}, isFile={}, len={}",
                fixtureFile.getAbsolutePath(),
                fixtureFile.exists(),
                fixtureFile.isFile(),
                fixtureFile.exists() ? fixtureFile.length() : -1);
    }

    private static void logDevelopmentHexChunks(final String label, final byte[] value) {
        if (!LOG_DEVELOPMENT_PLAINTEXT || value == null) {
            return;
        }

        if (value.length == 0) {
            LOG.warn("Fitbit DEBUG HEX {}: len=0 sha256={} chunks=0", label, sha256Hex(value));
            return;
        }

        final int totalChunks = (value.length + FITBIT_DEVELOPMENT_LOG_HEX_CHUNK_BYTES - 1)
                / FITBIT_DEVELOPMENT_LOG_HEX_CHUNK_BYTES;
        LOG.warn("Fitbit DEBUG HEX {}: len={} sha256={} chunks={}",
                label,
                value.length,
                sha256Hex(value),
                totalChunks);
        for (int offset = 0, chunk = 0; offset < value.length; offset += FITBIT_DEVELOPMENT_LOG_HEX_CHUNK_BYTES, chunk++) {
            final int length = Math.min(FITBIT_DEVELOPMENT_LOG_HEX_CHUNK_BYTES, value.length - offset);
            LOG.warn("Fitbit DEBUG HEX {} chunk={}/{} offset={} len={} data={}",
                    label,
                    chunk + 1,
                    totalChunks,
                    offset,
                    length,
                    toHex(Arrays.copyOfRange(value, offset, offset + length)));
        }
    }

    private void queueNextSyncResponseBlock() {
        if (dtlsSession == null || dtlsSession.syncResponsePayload == null) {
            return;
        }

        final int blockNumber = dtlsSession.nextSyncResponseBlockNumber;
        final int offset = blockNumber * FITBIT_COAP_BLOCK_SIZE_1024_BYTES;
        if (offset >= dtlsSession.syncResponsePayload.length) {
            LOG.warn("Fitbit onboarding script: sync/response requested next block past payload end, blockNumber={}, payloadLen={}",
                    blockNumber,
                    dtlsSession.syncResponsePayload.length);
            return;
        }

        final int length = Math.min(FITBIT_COAP_BLOCK_SIZE_1024_BYTES,
                dtlsSession.syncResponsePayload.length - offset);
        final boolean more = offset + length < dtlsSession.syncResponsePayload.length;
        final byte[] blockPayload = Arrays.copyOfRange(dtlsSession.syncResponsePayload, offset, offset + length);
        final int messageId = queueCoapRequest(
                "sync/response block " + blockNumber,
                COAP_CODE_PUT,
                new String[]{"sync", "response"},
                buildSyncResponseQuerySegments(dtlsSession.syncResponseRequestInfo),
                null,
                encodeCoapBlockOption(blockNumber, more, FITBIT_COAP_BLOCK_SIZE_1024),
                blockPayload
        );
        dtlsSession.syncResponseMessageIds.add(messageId);
        dtlsSession.nextSyncResponseBlockNumber = blockNumber + 1;
        LOG.info("Fitbit onboarding script: queued sync/response Block1 block={} more={} payloadLen={} mid={} prefix={}",
                blockNumber,
                more,
                blockPayload.length,
                messageId,
                toHexPrefix(blockPayload, 32));
        logDevelopmentHexChunks("sync/response Block1 block " + blockNumber + " payload", blockPayload);
    }

    private static String[] buildSyncResponseQuerySegments(final SyncRequestInfo syncRequestInfo) {
        if (syncRequestInfo == null
                || syncRequestInfo.eventType.isEmpty()
                || syncRequestInfo.requestUuidText.isEmpty()) {
            return new String[0];
        }

        return new String[]{
                "event=" + syncRequestInfo.eventType,
                "req=" + syncRequestInfo.requestUuidText,
        };
    }

    private int queueCoapRequest(final String label,
                                 final int code,
                                 final String[] pathSegments,
                                 final String[] querySegments,
                                 final Integer block2,
                                 final Integer block1,
                                 final byte[] payload) {
        if (dtlsSession == null) {
            return -1;
        }

        final int messageId = dtlsSession.nextCoapMessageId++ & 0xffff;
        final byte[] token = buildCoapToken(dtlsSession.nextCoapTokenValue++);
        final byte[] coapRequest = buildCoapRequest(
                code,
                messageId,
                token,
                pathSegments,
                querySegments,
                block2,
                block1,
                payload
        );
        queueEncryptedApplicationRecord("request " + label, coapRequest, messageId, token, buildCoapPath(Arrays.asList(pathSegments)));
        return messageId;
    }

    private void queueEncryptedApplicationRecord(final String label,
                                                 final byte[] coapMessage,
                                                 final int messageId,
                                                 final byte[] token,
                                                 final String path) {
        final long sequenceNumber = dtlsSession.nextServerApplicationSequenceNumber++;
        try {
            final byte[] encryptedResponse = encryptAes128CcmRecord(
                    dtlsSession.keys.serverWriteKey,
                    dtlsSession.keys.serverWriteIv,
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    dtlsSession.recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    sequenceNumber,
                    coapMessage
            );
            dtlsSession.pendingApplicationRecords.add(buildDtlsRecord(
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    dtlsSession.recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    sequenceNumber,
                    encryptedResponse
            ));

            LOG.info("Fitbit CoAP outbound {}: sequence={}, code={}, mid={}, token={}, path={}, payloadLen={}",
                    label,
                    sequenceNumber,
                    formatCoapCode(coapMessage[1] & 0xff),
                    messageId,
                    toHex(token),
                    path,
                    getCoapPayloadLength(coapMessage));
            if (LOG_DEVELOPMENT_PLAINTEXT) {
                LOG.info("Fitbit CoAP outbound plaintext: sequence={}, label={}, coap={}",
                        sequenceNumber,
                        label,
                        toHex(coapMessage));
            }
        } catch (final InvalidCipherTextException e) {
            LOG.warn("Unable to encrypt Fitbit CoAP outbound {}: mid={}, path={}",
                    label,
                    messageId,
                    path,
                    e);
        }
    }

    private void appendPendingApplicationRecords(final List<byte[]> records) {
        if (dtlsSession == null || dtlsSession.pendingApplicationRecords.isEmpty()) {
            return;
        }

        records.addAll(dtlsSession.pendingApplicationRecords);
        dtlsSession.pendingApplicationRecords.clear();
    }

    private byte[] drainPendingApplicationRecords() {
        final byte[] dtlsPayload = concat(dtlsSession.pendingApplicationRecords);
        dtlsSession.pendingApplicationRecords.clear();
        return dtlsPayload;
    }

    private ClientHello parseClientHello(final byte[] packet) {
        final UdpPacket udpPacket = parseUdpPacket(packet);
        if (udpPacket == null) {
            return null;
        }

        int recordOffset = udpPacket.dtlsOffset;
        while (recordOffset + DTLS_RECORD_HEADER_LENGTH <= udpPacket.dtlsEnd) {
            final int contentType = packet[recordOffset] & 0xff;
            final int recordVersion = readU16(packet, recordOffset + 1);
            final int recordLength = readU16(packet, recordOffset + 11);
            final int recordBodyOffset = recordOffset + DTLS_RECORD_HEADER_LENGTH;
            final int recordEnd = recordBodyOffset + recordLength;
            if (recordEnd > udpPacket.dtlsEnd) {
                return null;
            }

            if (contentType == DTLS_CONTENT_TYPE_HANDSHAKE) {
                final ClientHello clientHello = parseClientHelloRecord(
                        packet,
                        recordBodyOffset,
                        recordEnd,
                        recordVersion,
                        udpPacket.endpoint
                );
                if (clientHello != null) {
                    return clientHello;
                }
            }

            recordOffset = recordEnd;
        }

        return null;
    }

    private ClientHello parseClientHelloRecord(final byte[] packet,
                                               final int bodyOffset,
                                               final int bodyEnd,
                                               final int recordVersion,
                                               final Endpoint endpoint) {
        int handshakeOffset = bodyOffset;
        while (handshakeOffset + DTLS_HANDSHAKE_HEADER_LENGTH <= bodyEnd) {
            final int handshakeType = packet[handshakeOffset] & 0xff;
            final int messageLength = readU24(packet, handshakeOffset + 1);
            final int fragmentOffset = readU24(packet, handshakeOffset + 6);
            final int fragmentLength = readU24(packet, handshakeOffset + 9);
            final int messageOffset = handshakeOffset + DTLS_HANDSHAKE_HEADER_LENGTH;
            final int messageEnd = messageOffset + fragmentLength;
            if (messageEnd > bodyEnd) {
                return null;
            }

            if (handshakeType == DTLS_HANDSHAKE_TYPE_CLIENT_HELLO
                    && fragmentOffset == 0
                    && fragmentLength == messageLength) {
                return parseClientHelloBody(
                        packet,
                        handshakeOffset,
                        messageOffset,
                        messageEnd,
                        recordVersion,
                        endpoint
                );
            }

            handshakeOffset = messageEnd;
        }

        return null;
    }

    private ClientHello parseClientHelloBody(final byte[] packet,
                                             final int handshakeOffset,
                                             final int bodyOffset,
                                             final int bodyEnd,
                                             final int recordVersion,
                                             final Endpoint endpoint) {
        int pos = bodyOffset;
        if (pos + 34 > bodyEnd) {
            return null;
        }

        final int clientVersion = readU16(packet, pos);
        pos += 2;
        final byte[] clientRandom = Arrays.copyOfRange(packet, pos, pos + 32);
        pos += 32;

        if (pos + 1 > bodyEnd) {
            return null;
        }
        final int sessionIdLength = packet[pos] & 0xff;
        pos += 1 + sessionIdLength;

        if (pos + 1 > bodyEnd) {
            return null;
        }
        final int cookieLength = packet[pos] & 0xff;
        pos += 1 + cookieLength;

        if (pos + 2 > bodyEnd) {
            return null;
        }
        final int cipherSuitesLength = readU16(packet, pos);
        pos += 2;
        if ((cipherSuitesLength & 0x01) != 0 || pos + cipherSuitesLength > bodyEnd) {
            return null;
        }

        final int[] cipherSuites = new int[cipherSuitesLength / 2];
        for (int i = 0; i < cipherSuites.length; i++) {
            cipherSuites[i] = readU16(packet, pos + i * 2);
        }

        return new ClientHello(
                recordVersion,
                clientVersion,
                endpoint,
                clientRandom,
                cipherSuites,
                Arrays.copyOfRange(packet, handshakeOffset, bodyEnd)
        );
    }

    private ClientKeyExchangeFlight parseClientKeyExchangeFlight(final byte[] packet) {
        final UdpPacket udpPacket = parseUdpPacket(packet);
        if (udpPacket == null) {
            return null;
        }

        int recordOffset = udpPacket.dtlsOffset;
        byte[] clientKeyExchangeMessage = null;
        String identity = null;
        byte[] encryptedFinishedBody = null;
        int encryptedFinishedRecordVersion = -1;

        while (recordOffset + DTLS_RECORD_HEADER_LENGTH <= udpPacket.dtlsEnd) {
            final int contentType = packet[recordOffset] & 0xff;
            final int recordVersion = readU16(packet, recordOffset + 1);
            final int epoch = readU16(packet, recordOffset + 3);
            final int sequenceNumber = readU48(packet, recordOffset + 5);
            final int recordLength = readU16(packet, recordOffset + 11);
            final int recordBodyOffset = recordOffset + DTLS_RECORD_HEADER_LENGTH;
            final int recordEnd = recordBodyOffset + recordLength;
            if (recordEnd > udpPacket.dtlsEnd) {
                return null;
            }

            if (contentType == DTLS_CONTENT_TYPE_HANDSHAKE && epoch == 0) {
                int handshakeOffset = recordBodyOffset;
                while (handshakeOffset + DTLS_HANDSHAKE_HEADER_LENGTH <= recordEnd) {
                    final int handshakeType = packet[handshakeOffset] & 0xff;
                    final int fragmentLength = readU24(packet, handshakeOffset + 9);
                    final int messageOffset = handshakeOffset + DTLS_HANDSHAKE_HEADER_LENGTH;
                    final int messageEnd = messageOffset + fragmentLength;
                    if (messageEnd > recordEnd) {
                        return null;
                    }

                    if (handshakeType == DTLS_HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE) {
                        clientKeyExchangeMessage = Arrays.copyOfRange(packet, handshakeOffset, messageEnd);
                        identity = parseClientKeyExchangeIdentity(packet, messageOffset, messageEnd);
                    }

                    handshakeOffset = messageEnd;
                }
            } else if (contentType == DTLS_CONTENT_TYPE_HANDSHAKE && epoch == 1 && sequenceNumber == 0) {
                encryptedFinishedRecordVersion = recordVersion;
                encryptedFinishedBody = Arrays.copyOfRange(packet, recordBodyOffset, recordEnd);
            }

            recordOffset = recordEnd;
        }

        if (clientKeyExchangeMessage == null || identity == null || encryptedFinishedBody == null) {
            return null;
        }

        return new ClientKeyExchangeFlight(
                udpPacket.endpoint,
                encryptedFinishedRecordVersion,
                clientKeyExchangeMessage,
                identity,
                encryptedFinishedBody
        );
    }

    private String parseClientKeyExchangeIdentity(final byte[] packet,
                                                  final int offset,
                                                  final int end) {
        if (offset + 2 > end) {
            return null;
        }

        final int identityLength = readU16(packet, offset);
        if (offset + 2 + identityLength > end) {
            return null;
        }

        return new String(packet, offset + 2, identityLength, StandardCharsets.UTF_8);
    }

    private UdpPacket parseUdpPacket(final byte[] packet) {
        if (packet.length < IPV4_MIN_HEADER_LENGTH || (packet[0] & 0xf0) != 0x40) {
            return null;
        }

        final int headerLength = (packet[0] & 0x0f) * 4;
        final int totalLength = readU16(packet, 2);
        if (headerLength < IPV4_MIN_HEADER_LENGTH
                || totalLength < headerLength + UDP_HEADER_LENGTH
                || totalLength > packet.length
                || (packet[9] & 0xff) != UDP_PROTOCOL) {
            return null;
        }

        final int udpOffset = headerLength;
        final int sourcePort = readU16(packet, udpOffset);
        final int destinationPort = readU16(packet, udpOffset + 2);
        final int udpLength = readU16(packet, udpOffset + 4);
        if (udpLength < UDP_HEADER_LENGTH
                || udpOffset + udpLength > totalLength
                || (sourcePort != COAPS_PORT && destinationPort != COAPS_PORT)) {
            return null;
        }

        return new UdpPacket(
                new Endpoint(
                        Arrays.copyOfRange(packet, 12, 16),
                        Arrays.copyOfRange(packet, 16, 20),
                        sourcePort,
                        destinationPort
                ),
                udpOffset + UDP_HEADER_LENGTH,
                udpOffset + udpLength
        );
    }

    private byte[] buildServerHelloBody(final ClientHello clientHello) {
        final byte[] serverRandomSuffix = new byte[28];
        final byte[] sessionId = new byte[32];
        secureRandom.nextBytes(serverRandomSuffix);
        secureRandom.nextBytes(sessionId);

        final byte[] body = new byte[2 + 32 + 1 + sessionId.length + 2 + 1 + 2 + SERVER_HELLO_EXTENSIONS.length];
        int offset = 0;
        writeU16(body, offset, clientHello.clientVersion);
        offset += 2;

        writeU32(body, offset, System.currentTimeMillis() / 1000L);
        offset += 4;
        System.arraycopy(serverRandomSuffix, 0, body, offset, serverRandomSuffix.length);
        offset += serverRandomSuffix.length;

        body[offset++] = (byte) sessionId.length;
        System.arraycopy(sessionId, 0, body, offset, sessionId.length);
        offset += sessionId.length;

        writeU16(body, offset, FITBIT_DTLS_CIPHER_SUITE);
        offset += 2;
        body[offset++] = 0x00;

        writeU16(body, offset, SERVER_HELLO_EXTENSIONS.length);
        offset += 2;
        System.arraycopy(SERVER_HELLO_EXTENSIONS, 0, body, offset, SERVER_HELLO_EXTENSIONS.length);

        return body;
    }

    private byte[] encryptAes128CcmRecord(final byte[] key,
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

    private byte[] decryptAes128CcmRecord(final byte[] key,
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

        final byte[] explicitNonce = Arrays.copyOfRange(encryptedBody, 0, 8);
        final byte[] encrypted = Arrays.copyOfRange(encryptedBody, 8, encryptedBody.length);
        final byte[] nonce = concat(fixedIv, explicitNonce);
        final byte[] aad = buildAeadAdditionalData(contentType, version, epoch, sequenceNumber, plaintextLength);
        return ccmCrypt(false, key, nonce, aad, encrypted);
    }

    private byte[] ccmCrypt(final boolean encrypt,
                            final byte[] key,
                            final byte[] nonce,
                            final byte[] aad,
                            final byte[] input) throws InvalidCipherTextException {
        final CCMBlockCipher cipher = new CCMBlockCipher(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(
                new KeyParameter(key),
                AES_128_CCM_TAG_LENGTH_BITS,
                nonce,
                aad
        ));

        final byte[] output = new byte[cipher.getOutputSize(input.length)];
        int outputLength = cipher.processBytes(input, 0, input.length, output, 0);
        outputLength += cipher.doFinal(output, outputLength);
        return Arrays.copyOf(output, outputLength);
    }

    private byte[] buildExplicitNonce(final int epoch, final long sequenceNumber) {
        final byte[] nonce = new byte[8];
        writeU16(nonce, 0, epoch);
        writeU48(nonce, 2, sequenceNumber);
        return nonce;
    }

    private byte[] buildAeadAdditionalData(final int contentType,
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

    private byte[] buildHandshakeMessage(final int handshakeType,
                                         final int messageSequence,
                                         final byte[] body) {
        final byte[] message = new byte[DTLS_HANDSHAKE_HEADER_LENGTH + body.length];
        message[0] = (byte) handshakeType;
        writeU24(message, 1, body.length);
        writeU16(message, 4, messageSequence);
        writeU24(message, 6, 0);
        writeU24(message, 9, body.length);
        System.arraycopy(body, 0, message, DTLS_HANDSHAKE_HEADER_LENGTH, body.length);
        return message;
    }

    private byte[] buildDtlsRecord(final int contentType,
                                   final int version,
                                   final int epoch,
                                   final long sequenceNumber,
                                   final byte[] body) {
        final byte[] record = new byte[DTLS_RECORD_HEADER_LENGTH + body.length];
        record[0] = (byte) contentType;
        writeU16(record, 1, version);
        writeU16(record, 3, epoch);
        writeU48(record, 5, sequenceNumber);
        writeU16(record, 11, body.length);
        System.arraycopy(body, 0, record, DTLS_RECORD_HEADER_LENGTH, body.length);
        return record;
    }

    private byte[] buildIpv4UdpResponse(final Endpoint endpoint, final byte[] udpPayload) {
        final int udpLength = UDP_HEADER_LENGTH + udpPayload.length;
        final int totalLength = IPV4_MIN_HEADER_LENGTH + udpLength;
        final byte[] packet = new byte[totalLength];

        packet[0] = 0x45;
        writeU16(packet, 2, totalLength);
        writeU16(packet, 4, ipIdentification++ & 0xffff);
        packet[8] = (byte) 0xff;
        packet[9] = UDP_PROTOCOL;
        System.arraycopy(endpoint.destinationAddress, 0, packet, 12, 4);
        System.arraycopy(endpoint.sourceAddress, 0, packet, 16, 4);
        writeU16(packet, 10, checksum(packet, 0, IPV4_MIN_HEADER_LENGTH));

        final int udpOffset = IPV4_MIN_HEADER_LENGTH;
        writeU16(packet, udpOffset, endpoint.destinationPort);
        writeU16(packet, udpOffset + 2, endpoint.sourcePort);
        writeU16(packet, udpOffset + 4, udpLength);
        System.arraycopy(udpPayload, 0, packet, udpOffset + UDP_HEADER_LENGTH, udpPayload.length);
        writeU16(packet, udpOffset + 6, udpChecksum(packet, udpOffset, udpLength));

        return packet;
    }

    private CoapMessage parseCoapMessage(final byte[] packet) {
        if (packet.length < 4) {
            return null;
        }

        final int version = (packet[0] >> 6) & 0x03;
        final int type = (packet[0] >> 4) & 0x03;
        final int tokenLength = packet[0] & 0x0f;
        if (version != 1 || tokenLength > 8 || 4 + tokenLength > packet.length) {
            return null;
        }

        final int code = packet[1] & 0xff;
        final int messageId = readU16(packet, 2);
        final byte[] token = Arrays.copyOfRange(packet, 4, 4 + tokenLength);
        final List<String> uriPathSegments = new ArrayList<>();
        final List<String> uriQuerySegments = new ArrayList<>();
        int optionNumber = 0;
        int pos = 4 + tokenLength;
        byte[] payload = new byte[0];
        int block2Number = -1;
        boolean block2More = false;
        int block2SizeExponent = -1;
        int block1Number = -1;
        boolean block1More = false;
        int block1SizeExponent = -1;

        while (pos < packet.length) {
            if ((packet[pos] & 0xff) == COAP_PAYLOAD_MARKER) {
                pos++;
                payload = Arrays.copyOfRange(packet, pos, packet.length);
                break;
            }

            final int optionHeader = packet[pos++] & 0xff;
            final int optionDeltaNibble = (optionHeader >> 4) & 0x0f;
            final int optionLengthNibble = optionHeader & 0x0f;
            final CoapOptionField optionDelta = readCoapOptionField(packet, pos, optionDeltaNibble);
            if (optionDelta == null) {
                return null;
            }
            pos = optionDelta.nextOffset;

            final CoapOptionField optionLength = readCoapOptionField(packet, pos, optionLengthNibble);
            if (optionLength == null || optionLength.nextOffset + optionLength.value > packet.length) {
                return null;
            }
            pos = optionLength.nextOffset;

            optionNumber += optionDelta.value;
            if (optionNumber == COAP_OPTION_URI_PATH) {
                uriPathSegments.add(new String(packet, pos, optionLength.value, StandardCharsets.UTF_8));
            } else if (optionNumber == COAP_OPTION_URI_QUERY) {
                uriQuerySegments.add(new String(packet, pos, optionLength.value, StandardCharsets.UTF_8));
            } else if (optionNumber == COAP_OPTION_BLOCK2) {
                final int block2 = readCoapOptionValue(packet, pos, optionLength.value);
                block2Number = block2 >> 4;
                block2More = (block2 & 0x08) != 0;
                block2SizeExponent = block2 & 0x07;
            } else if (optionNumber == COAP_OPTION_BLOCK1) {
                final int block1 = readCoapOptionValue(packet, pos, optionLength.value);
                block1Number = block1 >> 4;
                block1More = (block1 & 0x08) != 0;
                block1SizeExponent = block1 & 0x07;
            }
            pos += optionLength.value;
        }

        return new CoapMessage(type, code, messageId, token, uriPathSegments, uriQuerySegments,
                block2Number, block2More, block2SizeExponent,
                block1Number, block1More, block1SizeExponent,
                payload);
    }

    private static SyncRequestInfo parseSyncRequestInfo(final byte[] payload) {
        String eventType = "";
        byte[] requestUuid = new byte[0];
        int retryCount = 0;
        int pos = 0;

        while (pos < payload.length) {
            final ProtoVarint tag = readProtoVarint(payload, pos);
            if (tag == null) {
                break;
            }
            pos = tag.nextOffset;
            final int fieldNumber = (int) (tag.value >> 3);
            final int wireType = (int) (tag.value & 0x07);

            if (wireType == 2) {
                final ProtoVarint lengthVarint = readProtoVarint(payload, pos);
                if (lengthVarint == null || lengthVarint.value > Integer.MAX_VALUE) {
                    break;
                }
                pos = lengthVarint.nextOffset;
                final int length = (int) lengthVarint.value;
                if (length < 0 || pos + length > payload.length) {
                    break;
                }
                if (fieldNumber == 1) {
                    eventType = new String(payload, pos, length, StandardCharsets.UTF_8);
                } else if (fieldNumber == 2) {
                    requestUuid = Arrays.copyOfRange(payload, pos, pos + length);
                }
                pos += length;
            } else if (wireType == 0) {
                final ProtoVarint value = readProtoVarint(payload, pos);
                if (value == null) {
                    break;
                }
                pos = value.nextOffset;
                if (fieldNumber == 3) {
                    retryCount = (int) value.value;
                }
            } else if (wireType == 5) {
                if (pos + 4 > payload.length) {
                    break;
                }
                pos += 4;
            } else if (wireType == 1) {
                if (pos + 8 > payload.length) {
                    break;
                }
                pos += 8;
            } else {
                break;
            }
        }

        return new SyncRequestInfo(eventType, requestUuid, retryCount);
    }

    private static AppLifecycleInfo parseAppLifecycleInfo(final byte[] payload) {
        byte[] appUuid = new byte[0];
        long appBuildId = 0;
        int eventType = 0;
        int errorType = 0;
        int pos = 0;

        while (pos < payload.length) {
            final ProtoVarint tag = readProtoVarint(payload, pos);
            if (tag == null) {
                break;
            }
            pos = tag.nextOffset;
            final int fieldNumber = (int) (tag.value >> 3);
            final int wireType = (int) (tag.value & 0x07);

            if (wireType == 2) {
                final ProtoVarint lengthVarint = readProtoVarint(payload, pos);
                if (lengthVarint == null || lengthVarint.value > Integer.MAX_VALUE) {
                    break;
                }
                pos = lengthVarint.nextOffset;
                final int length = (int) lengthVarint.value;
                if (length < 0 || pos + length > payload.length) {
                    break;
                }
                if (fieldNumber == 1) {
                    appUuid = Arrays.copyOfRange(payload, pos, pos + length);
                }
                pos += length;
            } else if (wireType == 0) {
                final ProtoVarint value = readProtoVarint(payload, pos);
                if (value == null) {
                    break;
                }
                pos = value.nextOffset;
                if (fieldNumber == 2) {
                    appBuildId = value.value;
                } else if (fieldNumber == 3) {
                    eventType = (int) value.value;
                } else if (fieldNumber == 4) {
                    errorType = (int) value.value;
                }
            } else if (wireType == 5) {
                if (pos + 4 > payload.length) {
                    break;
                }
                pos += 4;
            } else if (wireType == 1) {
                if (pos + 8 > payload.length) {
                    break;
                }
                pos += 8;
            } else {
                break;
            }
        }

        return new AppLifecycleInfo(appUuid, appBuildId, eventType, errorType);
    }

    private static GoldenGateExtendedError decodeGoldenGateExtendedError(final byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        String namespace = null;
        String message = null;
        long rawCode = 0;
        int code = 0;
        boolean sawKnownField = false;
        int pos = 0;

        while (pos < payload.length) {
            final ProtoVarint tag = readProtoVarint(payload, pos);
            if (tag == null || tag.nextOffset <= pos) {
                return null;
            }

            pos = tag.nextOffset;
            final int fieldNumber = (int) (tag.value >> 3);
            final int wireType = (int) (tag.value & 0x07);

            if ((fieldNumber == 1 || fieldNumber == 3) && wireType == PROTOBUF_WIRE_TYPE_LENGTH_DELIMITED) {
                final ProtoVarint lengthVarint = readProtoVarint(payload, pos);
                if (lengthVarint == null || lengthVarint.value > Integer.MAX_VALUE) {
                    return null;
                }

                pos = lengthVarint.nextOffset;
                final int length = (int) lengthVarint.value;
                if (length < 0 || pos + length > payload.length) {
                    return null;
                }

                final String value = new String(payload, pos, length, StandardCharsets.UTF_8);
                if (fieldNumber == 1) {
                    namespace = value;
                } else {
                    message = value;
                }
                sawKnownField = true;
                pos += length;
            } else if (fieldNumber == 2 && wireType == PROTOBUF_WIRE_TYPE_VARINT) {
                final ProtoVarint value = readProtoVarint(payload, pos);
                if (value == null) {
                    return null;
                }

                pos = value.nextOffset;
                rawCode = value.value;
                code = decodeProtoZigZag32(rawCode);
                sawKnownField = true;
            } else {
                final int nextOffset = skipProtoValue(payload, pos, wireType);
                if (nextOffset < 0 || nextOffset < pos) {
                    return null;
                }
                pos = nextOffset;
            }
        }

        if (!sawKnownField) {
            return null;
        }

        return new GoldenGateExtendedError(namespace, code, rawCode, message);
    }

    private static int skipProtoValue(final byte[] payload, final int offset, final int wireType) {
        switch (wireType) {
            case PROTOBUF_WIRE_TYPE_VARINT:
                final ProtoVarint value = readProtoVarint(payload, offset);
                return value == null ? -1 : value.nextOffset;
            case PROTOBUF_WIRE_TYPE_64_BIT:
                return offset + 8 <= payload.length ? offset + 8 : -1;
            case PROTOBUF_WIRE_TYPE_LENGTH_DELIMITED:
                final ProtoVarint lengthVarint = readProtoVarint(payload, offset);
                if (lengthVarint == null || lengthVarint.value > Integer.MAX_VALUE) {
                    return -1;
                }
                final int length = (int) lengthVarint.value;
                final int valueOffset = lengthVarint.nextOffset;
                return length >= 0 && valueOffset + length <= payload.length ? valueOffset + length : -1;
            case PROTOBUF_WIRE_TYPE_32_BIT:
                return offset + 4 <= payload.length ? offset + 4 : -1;
            default:
                return -1;
        }
    }

    private static int decodeProtoZigZag32(final long value) {
        return (int) ((value >>> 1) ^ -(value & 1L));
    }

    private static String appLifecycleEventName(final int eventType) {
        switch (eventType) {
            case 1:
                return "LAUNCH";
            case 2:
                return "TERMINATE";
            case 3:
                return "SESSION_OPEN";
            case 4:
                return "SESSION_OPEN_RESPONSE";
            case 5:
                return "SESSION_CLOSE";
            default:
                return "UNKNOWN";
        }
    }

    private static String appLifecycleErrorName(final int errorType) {
        switch (errorType) {
            case 0:
                return "OK";
            case 1:
                return "COMPANION_ERROR";
            default:
                return "UNKNOWN";
        }
    }

    private static ProtoVarint readProtoVarint(final byte[] payload, final int offset) {
        long value = 0;
        int shift = 0;
        int pos = offset;
        while (pos < payload.length && shift <= 63) {
            final int b = payload[pos++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new ProtoVarint(value, pos);
            }
            shift += 7;
        }
        return null;
    }

    private static boolean isCoapErrorResponse(final int code) {
        final int codeClass = (code >> 5) & 0x07;
        return codeClass == 4 || codeClass == 5;
    }

    private Integer resolveCoapResponseCode(final CoapMessage request) {
        if (request.type != COAP_TYPE_CONFIRMABLE || request.code != COAP_CODE_POST) {
            return null;
        }

        if ("/events".equals(request.path) || "/sync/request".equals(request.path)) {
            return COAP_CODE_CHANGED;
        }

        if ("/md/3d02".equals(request.path)) {
            return COAP_CODE_CHANGED;
        }

        return null;
    }

    private byte[] buildCoapAckResponse(final CoapMessage request, final int responseCode) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((1 << 6) | (COAP_TYPE_ACKNOWLEDGEMENT << 4) | request.token.length);
        output.write(responseCode);
        output.write((request.messageId >> 8) & 0xff);
        output.write(request.messageId & 0xff);
        output.write(request.token, 0, request.token.length);

        int previousOptionNumber = 0;
        for (final String segment : request.uriPathSegments) {
            final byte[] value = segment.getBytes(StandardCharsets.UTF_8);
            writeCoapOption(output, COAP_OPTION_URI_PATH - previousOptionNumber, value);
            previousOptionNumber = COAP_OPTION_URI_PATH;
        }

        return output.toByteArray();
    }

    private byte[] buildCoapRequest(final int code,
                                    final int messageId,
                                    final byte[] token,
                                    final String[] pathSegments,
                                    final String[] querySegments,
                                    final Integer block2,
                                    final Integer block1,
                                    final byte[] payload) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((1 << 6) | (COAP_TYPE_CONFIRMABLE << 4) | token.length);
        output.write(code);
        output.write((messageId >> 8) & 0xff);
        output.write(messageId & 0xff);
        output.write(token, 0, token.length);

        int previousOptionNumber = 0;
        for (final String segment : pathSegments) {
            final byte[] value = segment.getBytes(StandardCharsets.UTF_8);
            writeCoapOption(output, COAP_OPTION_URI_PATH - previousOptionNumber, value);
            previousOptionNumber = COAP_OPTION_URI_PATH;
        }

        for (final String query : querySegments) {
            final byte[] value = query.getBytes(StandardCharsets.UTF_8);
            writeCoapOption(output, COAP_OPTION_URI_QUERY - previousOptionNumber, value);
            previousOptionNumber = COAP_OPTION_URI_QUERY;
        }

        if (block2 != null) {
            writeCoapOption(output, COAP_OPTION_BLOCK2 - previousOptionNumber, encodeCoapOptionValue(block2));
            previousOptionNumber = COAP_OPTION_BLOCK2;
        }

        if (block1 != null) {
            writeCoapOption(output, COAP_OPTION_BLOCK1 - previousOptionNumber, encodeCoapOptionValue(block1));
        }

        if (payload.length > 0) {
            output.write(COAP_PAYLOAD_MARKER);
            output.write(payload, 0, payload.length);
        }

        return output.toByteArray();
    }

    private static byte[] buildCoapToken(final int tokenValue) {
        final byte[] token = new byte[4];
        writeU32(token, 0, tokenValue & 0xffffffffL);
        return token;
    }

    private static int getCoapPayloadLength(final byte[] coapMessage) {
        for (int i = 4 + (coapMessage[0] & 0x0f); i < coapMessage.length; i++) {
            if ((coapMessage[i] & 0xff) == COAP_PAYLOAD_MARKER) {
                return coapMessage.length - i - 1;
            }
        }
        return 0;
    }

    private static int encodeCoapBlockOption(final int blockNumber,
                                             final boolean more,
                                             final int sizeExponent) {
        return (blockNumber << 4) | (more ? 0x08 : 0x00) | (sizeExponent & 0x07);
    }

    private static int readCoapOptionValue(final byte[] packet,
                                           final int offset,
                                           final int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (packet[offset + i] & 0xff);
        }
        return value;
    }

    private static byte[] encodeCoapOptionValue(final int value) {
        if (value == 0) {
            return new byte[0];
        }

        int length = 4;
        while (length > 1 && ((value >> ((length - 1) * 8)) & 0xff) == 0) {
            length--;
        }

        final byte[] encoded = new byte[length];
        for (int i = 0; i < length; i++) {
            encoded[i] = (byte) ((value >> ((length - i - 1) * 8)) & 0xff);
        }
        return encoded;
    }

    private static CoapOptionField readCoapOptionField(final byte[] packet,
                                                       final int offset,
                                                       final int nibble) {
        if (nibble < 13) {
            return new CoapOptionField(nibble, offset);
        }

        if (nibble == 13) {
            if (offset >= packet.length) {
                return null;
            }
            return new CoapOptionField(13 + (packet[offset] & 0xff), offset + 1);
        }

        if (nibble == 14) {
            if (offset + 2 > packet.length) {
                return null;
            }
            return new CoapOptionField(269 + readU16(packet, offset), offset + 2);
        }

        return null;
    }

    private static void writeCoapOption(final ByteArrayOutputStream output,
                                        final int delta,
                                        final byte[] value) {
        final int deltaNibble = coapOptionNibble(delta);
        final int lengthNibble = coapOptionNibble(value.length);
        output.write((deltaNibble << 4) | lengthNibble);
        writeCoapOptionExtendedValue(output, delta, deltaNibble);
        writeCoapOptionExtendedValue(output, value.length, lengthNibble);
        output.write(value, 0, value.length);
    }

    private static int coapOptionNibble(final int value) {
        if (value < 13) {
            return value;
        }

        if (value < 269 + 0x10000) {
            return value < 269 ? 13 : 14;
        }

        throw new IllegalArgumentException("CoAP option value too large: " + value);
    }

    private static void writeCoapOptionExtendedValue(final ByteArrayOutputStream output,
                                                     final int value,
                                                     final int nibble) {
        if (nibble == 13) {
            output.write(value - 13);
        } else if (nibble == 14) {
            output.write(((value - 269) >> 8) & 0xff);
            output.write((value - 269) & 0xff);
        }
    }

    private static byte[] buildPskPreMasterSecret(final byte[] psk) {
        final byte[] secret = new byte[2 + psk.length + 2 + psk.length];
        int offset = 0;
        writeU16(secret, offset, psk.length);
        offset += 2 + psk.length;
        writeU16(secret, offset, psk.length);
        offset += 2;
        System.arraycopy(psk, 0, secret, offset, psk.length);
        return secret;
    }

    private static byte[] tlsPrf(final byte[] secret,
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

    private static byte[] hmacSha256(final byte[] key, final byte[] data)
            throws NoSuchAlgorithmException, InvalidKeyException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] sha256(final byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static int checksum(final byte[] packet, final int offset, final int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 2) {
            final int hi = packet[offset + i] & 0xff;
            final int lo = i + 1 < length ? packet[offset + i + 1] & 0xff : 0;
            sum += (hi << 8) | lo;
        }
        return finishChecksum(sum);
    }

    private static int udpChecksum(final byte[] packet, final int udpOffset, final int udpLength) {
        long sum = 0;
        sum = addAddress(sum, packet, 12);
        sum = addAddress(sum, packet, 16);
        sum += UDP_PROTOCOL;
        sum += udpLength;

        for (int i = 0; i < udpLength; i += 2) {
            final int hi = packet[udpOffset + i] & 0xff;
            final int lo = i + 1 < udpLength ? packet[udpOffset + i + 1] & 0xff : 0;
            sum += (hi << 8) | lo;
        }

        final int checksum = finishChecksum(sum);
        return checksum != 0 ? checksum : 0xffff;
    }

    private static long addAddress(long sum, final byte[] packet, final int offset) {
        sum += ((packet[offset] & 0xff) << 8) | (packet[offset + 1] & 0xff);
        sum += ((packet[offset + 2] & 0xff) << 8) | (packet[offset + 3] & 0xff);
        return sum;
    }

    private static int finishChecksum(long sum) {
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static byte[] concat(final byte[] first, final byte[] second) {
        final byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] concat(final byte[] first, final byte[] second, final byte[] third) {
        return concat(concat(first, second), third);
    }

    private static byte[] concat(final byte[] first,
                                 final byte[] second,
                                 final byte[] third,
                                 final byte[] fourth) {
        return concat(concat(first, second), concat(third, fourth));
    }

    private static byte[] concat(final List<byte[]> values) {
        int length = 0;
        for (final byte[] value : values) {
            length += value.length;
        }

        final byte[] result = new byte[length];
        int offset = 0;
        for (final byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }

        return result;
    }

    private static int readU16(final byte[] value, final int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    private static int readU24(final byte[] value, final int offset) {
        return ((value[offset] & 0xff) << 16)
                | ((value[offset + 1] & 0xff) << 8)
                | (value[offset + 2] & 0xff);
    }

    private static int readU48(final byte[] value, final int offset) {
        return (int) (((long) (value[offset] & 0xff) << 40)
                | ((long) (value[offset + 1] & 0xff) << 32)
                | ((long) (value[offset + 2] & 0xff) << 24)
                | ((long) (value[offset + 3] & 0xff) << 16)
                | ((long) (value[offset + 4] & 0xff) << 8)
                | (long) (value[offset + 5] & 0xff));
    }

    private static long readU48Long(final byte[] value, final int offset) {
        return ((long) (value[offset] & 0xff) << 40)
                | ((long) (value[offset + 1] & 0xff) << 32)
                | ((long) (value[offset + 2] & 0xff) << 24)
                | ((long) (value[offset + 3] & 0xff) << 16)
                | ((long) (value[offset + 4] & 0xff) << 8)
                | (long) (value[offset + 5] & 0xff);
    }

    private static void writeU16(final byte[] value, final int offset, final int number) {
        value[offset] = (byte) ((number >> 8) & 0xff);
        value[offset + 1] = (byte) (number & 0xff);
    }

    private static void writeU24(final byte[] value, final int offset, final int number) {
        value[offset] = (byte) ((number >> 16) & 0xff);
        value[offset + 1] = (byte) ((number >> 8) & 0xff);
        value[offset + 2] = (byte) (number & 0xff);
    }

    private static void writeU32(final byte[] value, final int offset, final long number) {
        value[offset] = (byte) ((number >> 24) & 0xff);
        value[offset + 1] = (byte) ((number >> 16) & 0xff);
        value[offset + 2] = (byte) ((number >> 8) & 0xff);
        value[offset + 3] = (byte) (number & 0xff);
    }

    private static void writeU32(final ByteArrayOutputStream output, final long number) {
        output.write((int) ((number >> 24) & 0xff));
        output.write((int) ((number >> 16) & 0xff));
        output.write((int) ((number >> 8) & 0xff));
        output.write((int) (number & 0xff));
    }

    private static void writeU48(final byte[] value, final int offset, final long number) {
        value[offset] = (byte) ((number >> 40) & 0xff);
        value[offset + 1] = (byte) ((number >> 32) & 0xff);
        value[offset + 2] = (byte) ((number >> 24) & 0xff);
        value[offset + 3] = (byte) ((number >> 16) & 0xff);
        value[offset + 4] = (byte) ((number >> 8) & 0xff);
        value[offset + 5] = (byte) (number & 0xff);
    }

    private static String formatCipherSuites(final int[] cipherSuites) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cipherSuites.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(formatU16(cipherSuites[i]));
        }
        return builder.toString();
    }

    private static String formatU16(final int value) {
        return String.format("0x%04x", value & 0xffff);
    }

    private static String formatCoapType(final int type) {
        if (type == COAP_TYPE_CONFIRMABLE) {
            return "CON";
        }

        if (type == COAP_TYPE_ACKNOWLEDGEMENT) {
            return "ACK";
        }

        return Integer.toString(type);
    }

    private static String formatCoapCode(final int code) {
        return String.format("%d.%02d", (code >> 5) & 0x07, code & 0x1f);
    }

    private static String toHex(final byte[] value) {
        final StringBuilder builder = new StringBuilder(value.length * 2);
        for (final byte b : value) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static String sha256Hex(final byte[] value) {
        try {
            return toHex(sha256(value));
        } catch (final NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String toHexPrefix(final byte[] value, final int maxBytes) {
        final int length = Math.min(value.length, maxBytes);
        final String prefix = toHex(Arrays.copyOf(value, length));
        if (value.length <= maxBytes) {
            return prefix;
        }

        return prefix + "...";
    }

    private static String buildCoapPath(final List<String> uriPathSegments) {
        if (uriPathSegments.isEmpty()) {
            return "/";
        }

        final StringBuilder builder = new StringBuilder();
        for (final String segment : uriPathSegments) {
            builder.append('/').append(segment);
        }
        return builder.toString();
    }

    private static String buildCoapQuery(final List<String> uriQuerySegments) {
        if (uriQuerySegments.isEmpty()) {
            return "";
        }

        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < uriQuerySegments.size(); i++) {
            if (i > 0) {
                builder.append('&');
            }
            builder.append(uriQuerySegments.get(i));
        }
        return builder.toString();
    }

    private static String describeIdentity(final String identity) {
        if (BOOTSTRAP_IDENTITY.equals(identity)) {
            return "(bootstrap)";
        }

        if (!isMobileDataIdentity(identity)) {
            return "(unknown)";
        }

        final String[] parts = identity.split("-");
        if (parts.length != 3) {
            return "(malformed mobile-data identity)";
        }

        try {
            final long keyId = Long.parseUnsignedLong(parts[1], 16);
            final long keyExpiration = Long.parseUnsignedLong(parts[2], 16);
            return String.format("(mobile-data keyId=%s/%d keyExpiration=%s/%d)",
                    parts[1],
                    keyId,
                    parts[2],
                    keyExpiration);
        } catch (final NumberFormatException e) {
            return "(malformed mobile-data identity)";
        }
    }

    private static boolean isMobileDataIdentity(final String identity) {
        return identity != null && identity.startsWith(MOBILE_DATA_IDENTITY_PREFIX);
    }

    private static String uuidText(final byte[] value) {
        if (value.length != 16) {
            return toHex(value);
        }

        long mostSignificantBits = 0;
        long leastSignificantBits = 0;
        for (int i = 0; i < 8; i++) {
            mostSignificantBits = (mostSignificantBits << 8) | (value[i] & 0xffL);
        }
        for (int i = 8; i < 16; i++) {
            leastSignificantBits = (leastSignificantBits << 8) | (value[i] & 0xffL);
        }
        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }

    private static final class UdpPacket {
        private final Endpoint endpoint;
        private final int dtlsOffset;
        private final int dtlsEnd;

        private UdpPacket(final Endpoint endpoint, final int dtlsOffset, final int dtlsEnd) {
            this.endpoint = endpoint;
            this.dtlsOffset = dtlsOffset;
            this.dtlsEnd = dtlsEnd;
        }
    }

    private static final class Endpoint {
        private final byte[] sourceAddress;
        private final byte[] destinationAddress;
        private final int sourcePort;
        private final int destinationPort;

        private Endpoint(final byte[] sourceAddress,
                         final byte[] destinationAddress,
                         final int sourcePort,
                         final int destinationPort) {
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
        }
    }

    private static final class ClientHello {
        private final int recordVersion;
        private final int clientVersion;
        private final Endpoint endpoint;
        private final byte[] clientRandom;
        private final int[] cipherSuites;
        private final byte[] handshakeMessage;

        private ClientHello(final int recordVersion,
                            final int clientVersion,
                            final Endpoint endpoint,
                            final byte[] clientRandom,
                            final int[] cipherSuites,
                            final byte[] handshakeMessage) {
            this.recordVersion = recordVersion;
            this.clientVersion = clientVersion;
            this.endpoint = endpoint;
            this.clientRandom = clientRandom;
            this.cipherSuites = cipherSuites;
            this.handshakeMessage = handshakeMessage;
        }

        private boolean offersCipherSuite(final int cipherSuite) {
            for (final int offeredCipherSuite : cipherSuites) {
                if (offeredCipherSuite == cipherSuite) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ClientKeyExchangeFlight {
        private final Endpoint endpoint;
        private final int recordVersion;
        private final byte[] clientKeyExchangeMessage;
        private final String identity;
        private final byte[] encryptedFinishedBody;

        private ClientKeyExchangeFlight(final Endpoint endpoint,
                                        final int recordVersion,
                                        final byte[] clientKeyExchangeMessage,
                                        final String identity,
                                        final byte[] encryptedFinishedBody) {
            this.endpoint = endpoint;
            this.recordVersion = recordVersion;
            this.clientKeyExchangeMessage = clientKeyExchangeMessage;
            this.identity = identity;
            this.encryptedFinishedBody = encryptedFinishedBody;
        }
    }

    private static final class HandshakeState {
        private final ClientHello clientHello;
        private final byte[] serverRandom;
        private final byte[] serverHello;
        private final byte[] serverHelloDone;

        private HandshakeState(final ClientHello clientHello,
                               final byte[] serverRandom,
                               final byte[] serverHello,
                               final byte[] serverHelloDone) {
            this.clientHello = clientHello;
            this.serverRandom = serverRandom;
            this.serverHello = serverHello;
            this.serverHelloDone = serverHelloDone;
        }
    }

    private static final class DtlsKeys {
        private final byte[] clientWriteKey;
        private final byte[] serverWriteKey;
        private final byte[] clientWriteIv;
        private final byte[] serverWriteIv;

        private DtlsKeys(final byte[] keyBlock) {
            int offset = 0;
            clientWriteKey = Arrays.copyOfRange(keyBlock, offset, offset + AES_128_CCM_KEY_LENGTH);
            offset += AES_128_CCM_KEY_LENGTH;
            serverWriteKey = Arrays.copyOfRange(keyBlock, offset, offset + AES_128_CCM_KEY_LENGTH);
            offset += AES_128_CCM_KEY_LENGTH;
            clientWriteIv = Arrays.copyOfRange(keyBlock, offset, offset + AES_128_CCM_FIXED_IV_LENGTH);
            offset += AES_128_CCM_FIXED_IV_LENGTH;
            serverWriteIv = Arrays.copyOfRange(keyBlock, offset, offset + AES_128_CCM_FIXED_IV_LENGTH);
        }
    }

    private static final class DtlsSession {
        private final Endpoint endpoint;
        private final int recordVersion;
        private final DtlsKeys keys;
        private final List<byte[]> pendingApplicationRecords = new ArrayList<>();
        private final Set<Integer> handledMetadataMessageIds = new HashSet<>();
        private final Set<Integer> handledSyncRequestMessageIds = new HashSet<>();
        private final Set<Integer> syncDumpMessageIds = new HashSet<>();
        private final Set<Integer> syncResponseMessageIds = new HashSet<>();
        private final ByteArrayOutputStream syncDumpPayload = new ByteArrayOutputStream();
        private final long startedAtMillis;
        private long nextClientApplicationSequenceNumber = 1;
        private long nextServerApplicationSequenceNumber = 1;
        private int nextCoapMessageId = 0;
        private int nextCoapTokenValue = FITBIT_ONBOARDING_TOKEN_BASE;
        private String pairingCode;
        private int pairDisplayOnMessageId = -1;
        private int pairDisplayOffMessageId = -1;
        private int syncConfigMessageId = -1;
        private int syncDumpPairMessageId = -1;
        private int nextSyncDumpBlockNumber = 0;
        private int syncDumpPayloadLength = 0;
        private byte[] syncResponsePayload;
        private SyncRequestInfo pendingSyncRequestInfo;
        private SyncRequestInfo syncResponseRequestInfo;
        private int nextSyncResponseBlockNumber = 0;
        private boolean pairDisplayOnAcknowledged;
        private boolean pairDisplayOffAcknowledged;
        private boolean syncConfigQueued;
        private boolean syncConfigAcknowledged;
        private boolean syncDumpPairQueued;
        private boolean syncDumpPairAcknowledged;
        private boolean syncDumpComplete;
        private boolean syncResponseQueued;
        private boolean syncResponseComplete;
        private boolean syncResponseFailed;

        private DtlsSession(final Endpoint endpoint,
                            final int recordVersion,
                            final DtlsKeys keys) {
            this.endpoint = endpoint;
            this.recordVersion = recordVersion;
            this.keys = keys;
            this.startedAtMillis = System.currentTimeMillis();
        }
    }

    private static final class CoapMessage {
        private final int type;
        private final int code;
        private final int messageId;
        private final byte[] token;
        private final List<String> uriPathSegments;
        private final List<String> uriQuerySegments;
        private final String path;
        private final String query;
        private final boolean hasBlock2;
        private final int block2Number;
        private final boolean block2More;
        private final int block2SizeExponent;
        private final boolean hasBlock1;
        private final int block1Number;
        private final boolean block1More;
        private final int block1SizeExponent;
        private final byte[] payload;
        private final int payloadLength;

        private CoapMessage(final int type,
                            final int code,
                            final int messageId,
                            final byte[] token,
                            final List<String> uriPathSegments,
                            final List<String> uriQuerySegments,
                            final int block2Number,
                            final boolean block2More,
                            final int block2SizeExponent,
                            final int block1Number,
                            final boolean block1More,
                            final int block1SizeExponent,
                            final byte[] payload) {
            this.type = type;
            this.code = code;
            this.messageId = messageId;
            this.token = token;
            this.uriPathSegments = uriPathSegments;
            this.uriQuerySegments = uriQuerySegments;
            this.path = buildCoapPath(uriPathSegments);
            this.query = buildCoapQuery(uriQuerySegments);
            this.hasBlock2 = block2Number >= 0;
            this.block2Number = block2Number;
            this.block2More = block2More;
            this.block2SizeExponent = block2SizeExponent;
            this.hasBlock1 = block1Number >= 0;
            this.block1Number = block1Number;
            this.block1More = block1More;
            this.block1SizeExponent = block1SizeExponent;
            this.payload = payload;
            this.payloadLength = payload.length;
        }
    }

    private static final class SyncRequestInfo {
        private final String eventType;
        private final byte[] requestUuid;
        private final String requestUuidText;
        private final int retryCount;

        private SyncRequestInfo(final String eventType,
                                final byte[] requestUuid,
                                final int retryCount) {
            this.eventType = eventType == null ? "" : eventType;
            this.requestUuid = requestUuid == null ? new byte[0] : requestUuid;
            this.requestUuidText = uuidText(this.requestUuid);
            this.retryCount = retryCount;
        }
    }

    private static final class AppLifecycleInfo {
        private final byte[] appUuid;
        private final String appUuidText;
        private final long appBuildId;
        private final int eventType;
        private final int errorType;

        private AppLifecycleInfo(final byte[] appUuid,
                                 final long appBuildId,
                                 final int eventType,
                                 final int errorType) {
            this.appUuid = appUuid == null ? new byte[0] : appUuid;
            this.appUuidText = uuidText(this.appUuid);
            this.appBuildId = appBuildId;
            this.eventType = eventType;
            this.errorType = errorType;
        }
    }

    private static final class GoldenGateExtendedError {
        private final String namespace;
        private final int code;
        private final long rawCode;
        private final String message;

        private GoldenGateExtendedError(final String namespace,
                                        final int code,
                                        final long rawCode,
                                        final String message) {
            this.namespace = namespace;
            this.code = code;
            this.rawCode = rawCode;
            this.message = message;
        }
    }

    private static final class ProtoVarint {
        private final long value;
        private final int nextOffset;

        private ProtoVarint(final long value, final int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }

    private static final class CoapOptionField {
        private final int value;
        private final int nextOffset;

        private CoapOptionField(final int value, final int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }

    interface PskResolver {
        byte[] resolvePsk(String identity);
    }
}
