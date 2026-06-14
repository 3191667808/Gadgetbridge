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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FitbitDtls {
    private static final Logger LOG = LoggerFactory.getLogger(FitbitDtls.class);

    private static final int DTLS_RECORD_HEADER_LENGTH = 13;
    private static final int DTLS_HANDSHAKE_HEADER_LENGTH = 12;

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
    private static final int AES_128_CCM_KEY_LENGTH = 16;
    private static final int AES_128_CCM_FIXED_IV_LENGTH = 4;
    private static final int TLS_MASTER_SECRET_LENGTH = 48;
    private static final int TLS_FINISHED_VERIFY_DATA_LENGTH = 12;
    private static final String MOBILE_DATA_IDENTITY_PREFIX = "MD-";
    private static final byte[] CHANGE_CIPHER_SPEC_BODY = new byte[]{0x01};
    private static final byte[] SERVER_HELLO_EXTENSIONS = new byte[]{
            (byte) 0xff, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x17, 0x00, 0x00,
    };

    private final SecureRandom secureRandom = new SecureRandom();
    private final PskResolver pskResolver;
    private final CoapResponseHandler coapResponseHandler;
    private final Map<String, PendingCoapRequest> pendingCoapRequests = new HashMap<>();
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
        return buildCoapGetRequest(path, Collections.<FitbitCoap.Option>emptyList());
    }

    byte[] buildCoapGetRequest(final String path, final List<FitbitCoap.Option> options) {
        return buildCoapRequest(path, FitbitCoap.CODE_GET, options, new byte[0]);
    }

    byte[] buildCoapPostRequest(final String path, final byte[] payload) {
        return buildCoapRequest(path, FitbitCoap.CODE_POST, Collections.<FitbitCoap.Option>emptyList(), payload);
    }

    byte[] buildCoapPutRequest(final String path, final byte[] payload) {
        return buildCoapRequest(path, FitbitCoap.CODE_PUT, Collections.<FitbitCoap.Option>emptyList(), payload);
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
            final byte[] masterSecret = FitbitDtlsCrypto.tlsPrf(
                    FitbitDtlsCrypto.buildPskPreMasterSecret(psk),
                    "extended master secret",
                    FitbitDtlsCrypto.sha256(handshakeTranscript),
                    TLS_MASTER_SECRET_LENGTH
            );
            final byte[] keyBlock = FitbitDtlsCrypto.tlsPrf(
                    masterSecret,
                    "key expansion",
                    concat(handshakeState.serverRandom, handshakeState.clientHello.clientRandom),
                    AES_128_CCM_KEY_LENGTH * 2 + AES_128_CCM_FIXED_IV_LENGTH * 2
            );
            final DtlsKeys keys = new DtlsKeys(keyBlock);

            final byte[] decryptedClientFinished = FitbitDtlsCrypto.decryptAes128CcmRecord(
                    keys.clientWriteKey,
                    keys.clientWriteIv,
                    DTLS_CONTENT_TYPE_HANDSHAKE,
                    clientFlight.recordVersion,
                    1,
                    0,
                    clientFlight.encryptedFinishedBody,
                    DTLS_HANDSHAKE_HEADER_LENGTH + TLS_FINISHED_VERIFY_DATA_LENGTH
            );
            final byte[] expectedClientVerifyData = FitbitDtlsCrypto.tlsPrf(
                    masterSecret,
                    "client finished",
                    FitbitDtlsCrypto.sha256(handshakeTranscript),
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

            final byte[] serverFinishedVerifyData = FitbitDtlsCrypto.tlsPrf(
                    masterSecret,
                    "server finished",
                    FitbitDtlsCrypto.sha256(concat(handshakeTranscript, decryptedClientFinished)),
                    TLS_FINISHED_VERIFY_DATA_LENGTH
            );
            final byte[] serverFinished = buildHandshakeMessage(
                    DTLS_HANDSHAKE_TYPE_FINISHED,
                    2,
                    serverFinishedVerifyData
            );
            final byte[] encryptedServerFinished = FitbitDtlsCrypto.encryptAes128CcmRecord(
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

        final FitbitIpv4Udp.Packet udpPacket = FitbitIpv4Udp.parse(packet);
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
            plaintext = FitbitDtlsCrypto.decryptAes128CcmRecord(
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

        final FitbitCoap.Message request = FitbitCoap.parseMessage(plaintext);
        if (request == null) {
            LOG.warn("Unable to parse Fitbit CoAP request: sequence={}, len={}",
                    sequenceNumber,
                    plaintext.length);
            return null;
        }

        LOG.info("Fitbit CoAP request: sequence={}, type={}, code={}, mid={}, token={}, path={}, payloadLen={}",
                sequenceNumber,
                FitbitCoap.formatType(request.type),
                FitbitCoap.formatCode(request.code),
                request.messageId,
                toHex(request.token),
                request.path,
                request.payloadLength);

        if (FitbitCoap.isResponse(request)) {
            handleCoapResponse(request);
            return null;
        }

        final Integer responseCode = FitbitCoap.resolveResponseCode(request);
        if (responseCode == null) {
            LOG.info("Fitbit CoAP request not handled yet: path={}, code={}, mid={}",
                    request.path,
                    FitbitCoap.formatCode(request.code),
                    request.messageId);
            return null;
        }

        final byte[] coapResponse = FitbitCoap.buildAckResponse(request, responseCode);
        final long responseSequenceNumber = dtlsSession.nextServerApplicationSequenceNumber++;
        try {
            final byte[] encryptedResponse = FitbitDtlsCrypto.encryptAes128CcmRecord(
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
                    FitbitCoap.formatCode(responseCode),
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
        final FitbitIpv4Udp.Packet udpPacket = FitbitIpv4Udp.parse(packet);
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
                                               final FitbitIpv4Udp.Endpoint endpoint) {
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
                                             final FitbitIpv4Udp.Endpoint endpoint) {
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
        final FitbitIpv4Udp.Packet udpPacket = FitbitIpv4Udp.parse(packet);
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

    private byte[] buildIpv4UdpResponse(final FitbitIpv4Udp.Endpoint endpoint, final byte[] udpPayload) {
        return FitbitIpv4Udp.buildResponse(endpoint, udpPayload, ipIdentification++ & 0xffff);
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

    private static String toHex(final byte[] value) {
        final StringBuilder builder = new StringBuilder(value.length * 2);
        for (final byte b : value) {
            builder.append(String.format("%02x", b & 0xff));
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

    private static final class ClientHello {
        private final int recordVersion;
        private final int clientVersion;
        private final FitbitIpv4Udp.Endpoint endpoint;
        private final byte[] clientRandom;
        private final int[] cipherSuites;
        private final byte[] handshakeMessage;

        private ClientHello(final int recordVersion,
                            final int clientVersion,
                            final FitbitIpv4Udp.Endpoint endpoint,
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
        private final FitbitIpv4Udp.Endpoint endpoint;
        private final int recordVersion;
        private final byte[] clientKeyExchangeMessage;
        private final String identity;
        private final byte[] encryptedFinishedBody;

        private ClientKeyExchangeFlight(final FitbitIpv4Udp.Endpoint endpoint,
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
        private final FitbitIpv4Udp.Endpoint endpoint;
        private final int recordVersion;
        private final DtlsKeys keys;
        private long nextClientApplicationSequenceNumber = 1;
        private long nextServerApplicationSequenceNumber = 1;

        private DtlsSession(final FitbitIpv4Udp.Endpoint endpoint,
                            final int recordVersion,
                            final DtlsKeys keys) {
            this.endpoint = endpoint;
            this.recordVersion = recordVersion;
            this.keys = keys;
        }
    }

    private byte[] buildCoapRequest(final String path,
                                    final int code,
                                    final List<FitbitCoap.Option> options,
                                    final byte[] payload) {
        if (dtlsSession == null) {
            LOG.debug("Unable to build Fitbit CoAP request for {}, DTLS session is not established", path);
            return null;
        }

        final int messageId = nextCoapMessageId++ & 0xffff;
        final byte[] token = nextCoapToken();
        final byte[] coapRequest = FitbitCoap.buildRequestMessage(
                FitbitCoap.TYPE_CONFIRMABLE,
                code,
                messageId,
                token,
                path,
                options,
                payload
        );
        final long sequenceNumber = dtlsSession.nextServerApplicationSequenceNumber++;
        try {
            final byte[] encryptedRequest = FitbitDtlsCrypto.encryptAes128CcmRecord(
                    dtlsSession.keys.serverWriteKey,
                    dtlsSession.keys.serverWriteIv,
                    DTLS_CONTENT_TYPE_APPLICATION_DATA,
                    dtlsSession.recordVersion,
                    DTLS_EPOCH_APPLICATION,
                    sequenceNumber,
                    coapRequest
            );
            pendingCoapRequests.put(
                    FitbitCoap.requestKey(messageId, token),
                    new PendingCoapRequest(FitbitCoap.normalizePath(path), code)
            );

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
                    FitbitCoap.formatCode(code),
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

    private void handleCoapResponse(final FitbitCoap.Message response) {
        final PendingCoapRequest request = pendingCoapRequests.remove(FitbitCoap.requestKey(response.messageId, response.token));
        final String requestPath = request != null ? request.path : null;
        LOG.info("Fitbit CoAP inbound response: code={}, mid={}, token={}, requestPath={}, responsePath={}, payloadLen={}",
                FitbitCoap.formatCode(response.code),
                response.messageId,
                toHex(response.token),
                requestPath,
                response.path,
                response.payloadLength);

        if (request != null && coapResponseHandler != null) {
            coapResponseHandler.handleCoapResponse(request.path, request.code, response.code, response.payload);
        }
    }

    private static final class PendingCoapRequest {
        private final String path;
        private final int code;

        private PendingCoapRequest(final String path, final int code) {
            this.path = path;
            this.code = code;
        }
    }

    interface PskResolver {
        byte[] resolvePsk(String identity);
    }

    interface CoapResponseHandler {
        void handleCoapResponse(String requestPath, int requestCode, int responseCode, byte[] payload);
    }
}
