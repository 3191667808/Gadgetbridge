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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class FitbitDtls {
    private static final Logger LOG = LoggerFactory.getLogger(FitbitDtls.class);

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
    private static final int COAP_TYPE_NON_CONFIRMABLE = 1;
    private static final int COAP_TYPE_ACKNOWLEDGEMENT = 2;
    private static final int COAP_CODE_GET = 0x01;
    private static final int COAP_CODE_POST = 0x02;
    private static final int COAP_CODE_CHANGED = 0x44;
    private static final int COAP_OPTION_URI_PATH = 11;
    private static final int COAP_PAYLOAD_MARKER = 0xff;

    private static final String MOBILE_DATA_IDENTITY_PREFIX = "MD-";
    private static final byte[] CHANGE_CIPHER_SPEC_BODY = new byte[]{0x01};
    private static final byte[] SERVER_HELLO_EXTENSIONS = new byte[]{
            (byte) 0xff, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x17, 0x00, 0x00,
    };

    private final SecureRandom secureRandom = new SecureRandom();
    private final PskResolver pskResolver;
    private final CoapResponseHandler coapResponseHandler;
    private final Map<String, String> pendingCoapRequests = new HashMap<>();
    private int ipIdentification = 1;
    private int nextCoapMessageId = secureRandom.nextInt(0x10000);
    private int nextCoapToken = secureRandom.nextInt();
    private HandshakeState handshakeState;
    private DtlsSession dtlsSession;

    FitbitDtls(final PskResolver pskResolver) {
        this(pskResolver, null);
    }

    FitbitDtls(final PskResolver pskResolver, final CoapResponseHandler coapResponseHandler) {
        this.pskResolver = pskResolver;
        this.coapResponseHandler = coapResponseHandler;
    }

    void reset() {
        ipIdentification = 1;
        nextCoapMessageId = secureRandom.nextInt(0x10000);
        nextCoapToken = secureRandom.nextInt();
        pendingCoapRequests.clear();
        handshakeState = null;
        dtlsSession = null;
    }

    boolean isSessionEstablished() {
        return dtlsSession != null;
    }

    byte[] buildCoapGetRequest(final String path) {
        return buildCoapRequest(path, COAP_CODE_GET, new byte[0]);
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
            LOG.info("Fitbit DTLS mobile-data session established for identity {}", clientFlight.identity);
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

        if (isCoapResponse(request)) {
            handleCoapResponse(request);
            return null;
        }

        final Integer responseCode = resolveCoapResponseCode(request);
        if (responseCode == null) {
            LOG.info("Fitbit CoAP request not handled yet: path={}, code={}, mid={}",
                    request.path,
                    formatCoapCode(request.code),
                    request.messageId);
            return null;
        }

        final byte[] coapResponse = buildCoapAckResponse(request, responseCode);
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
        int optionNumber = 0;
        int pos = 4 + tokenLength;
        byte[] payload = new byte[0];

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
            }
            pos += optionLength.value;
        }

        return new CoapMessage(type, code, messageId, token, uriPathSegments, payload);
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

    private static boolean isCoapResponse(final CoapMessage message) {
        return ((message.code >> 5) & 0x07) != 0;
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

        if (type == COAP_TYPE_NON_CONFIRMABLE) {
            return "NON";
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

    private static String describeIdentity(final String identity) {
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
        private long nextClientApplicationSequenceNumber = 1;
        private long nextServerApplicationSequenceNumber = 1;

        private DtlsSession(final Endpoint endpoint,
                            final int recordVersion,
                            final DtlsKeys keys) {
            this.endpoint = endpoint;
            this.recordVersion = recordVersion;
            this.keys = keys;
        }
    }

    private static String[] coapPathSegments(final String path) {
        final String normalized = normalizeOutgoingCoapPath(path);
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split("/");
    }

    private static String normalizeOutgoingCoapPath(final String path) {
        if (path == null) {
            return "";
        }

        String normalized = path.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String coapRequestKey(final int messageId, final byte[] token) {
        return messageId + ":" + toHex(token);
    }

    private byte[] buildCoapRequest(final String path, final int code, final byte[] payload) {
        if (dtlsSession == null) {
            LOG.debug("Unable to build Fitbit CoAP request for {}, DTLS session is not established", path);
            return null;
        }

        final int messageId = nextCoapMessageId++ & 0xffff;
        final byte[] token = nextCoapToken();
        final byte[] coapRequest = buildCoapRequestMessage(
                COAP_TYPE_CONFIRMABLE,
                code,
                messageId,
                token,
                path,
                payload
        );
        final long sequenceNumber = dtlsSession.nextServerApplicationSequenceNumber++;
        try {
            final byte[] encryptedRequest = encryptAes128CcmRecord(
                    dtlsSession.keys.serverWriteKey,
                    dtlsSession.keys.serverWriteIv,
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    dtlsSession.recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    sequenceNumber,
                    coapRequest
            );
            pendingCoapRequests.put(coapRequestKey(messageId, token), normalizeOutgoingCoapPath(path));

            final byte[] dtlsRecord = buildDtlsRecord(
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    dtlsSession.recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    sequenceNumber,
                    encryptedRequest
            );
            final byte[] ipv4Packet = buildIpv4UdpResponse(dtlsSession.endpoint, dtlsRecord);
            LOG.info("Fitbit CoAP outbound request: sequence={}, code={}, mid={}, token={}, path={}, payloadLen={}",
                    sequenceNumber,
                    formatCoapCode(code),
                    messageId,
                    toHex(token),
                    path,
                    payload.length);
            return ipv4Packet;
        } catch (final InvalidCipherTextException e) {
            LOG.warn("Unable to encrypt Fitbit CoAP request: path={}, mid={}", path, messageId, e);
            return null;
        }
    }

    private byte[] nextCoapToken() {
        final int token = nextCoapToken++;
        return new byte[]{
                (byte) ((token >> 24) & 0xff),
                (byte) ((token >> 16) & 0xff),
                (byte) ((token >> 8) & 0xff),
                (byte) (token & 0xff),
        };
    }

    private byte[] buildCoapRequestMessage(final int type,
                                           final int code,
                                           final int messageId,
                                           final byte[] token,
                                           final String path,
                                           final byte[] payload) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((1 << 6) | (type << 4) | token.length);
        output.write(code);
        output.write((messageId >> 8) & 0xff);
        output.write(messageId & 0xff);
        output.write(token, 0, token.length);

        int previousOptionNumber = 0;
        for (final String segment : coapPathSegments(path)) {
            final byte[] value = segment.getBytes(StandardCharsets.UTF_8);
            writeCoapOption(output, COAP_OPTION_URI_PATH - previousOptionNumber, value);
            previousOptionNumber = COAP_OPTION_URI_PATH;
        }

        if (payload.length > 0) {
            output.write(COAP_PAYLOAD_MARKER);
            output.write(payload, 0, payload.length);
        }

        return output.toByteArray();
    }

    private void handleCoapResponse(final CoapMessage response) {
        final String requestPath = pendingCoapRequests.remove(coapRequestKey(response.messageId, response.token));
        LOG.info("Fitbit CoAP inbound response: code={}, mid={}, token={}, requestPath={}, responsePath={}, payloadLen={}",
                formatCoapCode(response.code),
                response.messageId,
                toHex(response.token),
                requestPath,
                response.path,
                response.payloadLength);

        if (requestPath != null && coapResponseHandler != null) {
            coapResponseHandler.handleCoapResponse(requestPath, response.code, response.payload);
        }
    }

    private static final class CoapMessage {
        private final int type;
        private final int code;
        private final int messageId;
        private final byte[] token;
        private final List<String> uriPathSegments;
        private final String path;
        private final byte[] payload;
        private final int payloadLength;

        private CoapMessage(final int type,
                            final int code,
                            final int messageId,
                            final byte[] token,
                            final List<String> uriPathSegments,
                            final byte[] payload) {
            this.type = type;
            this.code = code;
            this.messageId = messageId;
            this.token = token;
            this.uriPathSegments = uriPathSegments;
            this.path = buildCoapPath(uriPathSegments);
            this.payload = payload;
            this.payloadLength = payload.length;
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

    interface CoapResponseHandler {
        void handleCoapResponse(String requestPath, int code, byte[] payload);
    }
}
