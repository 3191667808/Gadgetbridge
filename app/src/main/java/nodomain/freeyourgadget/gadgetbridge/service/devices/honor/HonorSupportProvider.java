/*  Copyright (C) 2025 Vitalii Tomin

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.honor;

import android.content.SharedPreferences;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.honor.HonorConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.honor.HonorCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiCrypto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.honor.requests.GetHiChainPakeRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.honor.requests.GetHonorSecurityNegotiationRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiBRSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiLESupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiSupportProvider;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.Request;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.Request.RequestCallback;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/**
 * Protocol implementation for Honor devices speaking the newer Honor protocol, i.e. those whose
 * coordinator implements {@link HonorCoordinator}. It differs from {@link HuaweiSupportProvider} in
 * the transport UUIDs and in authentication: an Honor connection runs either the simple
 * challenge/response handshake or the HiChain PSK-SPEKE PAKE bind / STS fast reconnect, and which
 * one is decided per connection by the device - see
 * {@link GetHonorSecurityNegotiationRequest#isPakeAuthentication()}, which is where the two are
 * told apart.
 * <p>
 * HiChain and HiChain Lite are Huawei-only and are never attempted here. Conversely, the PAKE stack
 * is reachable only from this class, so {@link HuaweiSupportProvider} carries none of it.
 */
public class HonorSupportProvider extends HuaweiSupportProvider {
    private static final Logger LOG = LoggerFactory.getLogger(HonorSupportProvider.class);

    public HonorSupportProvider(HuaweiBRSupport support) {
        super(support);
    }

    public HonorSupportProvider(HuaweiLESupport support) {
        super(support);
    }

    @Override
    public UUID getReadCharacteristicUuid() {
        return HonorConstants.UUID_CHARACTERISTIC_HONOR_READ;
    }

    @Override
    public UUID getWriteCharacteristicUuid() {
        return HonorConstants.UUID_CHARACTERISTIC_HONOR_WRITE;
    }

    /**
     * An Honor connection authenticates either with the HiChain PAKE / STS flow or with the simple
     * handshake - never with HiChain or HiChain Lite. The device picks, per connection, by whether
     * it announces a HiChain support type in the link params; the negotiation request reports that
     * as {@link GetHonorSecurityNegotiationRequest#isPakeAuthentication()} and suppresses itself on
     * the simple path, so its finalize callback runs either way and branches here.
     */
    @Override
    protected void initializeDeviceAuthentication(final Request linkParamsReq) {
        try {
            final GetHonorSecurityNegotiationRequest securityNegoReq = new GetHonorSecurityNegotiationRequest(this);
            if (securityNegoReq.isPakeAuthentication()) {
                // Mirrors the Huawei auth mode mapping, as the negotiation carries it on the wire.
                getParamsProvider().setAuthMode(getParamsProvider().getDeviceSupportType() == 4 ? (byte) 4 : (byte) 2);
            }
            securityNegoReq.setFinalizeReq(new RequestCallback(this) {
                @Override
                public void call() {
                    if (!securityNegoReq.isPakeAuthentication()) {
                        LOG.debug("Honor simple authentication mode (no HiChain support announced)");
                        initializeDeviceNormalMode(linkParamsReq);
                        return;
                    }
                    // The watch dictates the flow via the negotiated pairType (tag 0x02 of the 0x33
                    // response): 2 = it trusts us -> STS fast reconnect; anything else (incl.
                    // FIRST_PAIR=1) -> a full PAKE bind. We request pairType=2 ourselves once we
                    // hold a stored peer identity (see GetHonorSecurityNegotiationRequest); the
                    // watch downgrades us to FIRST_PAIR if it has not persisted our trust, and the
                    // (PIN-less, DH-derived) re-bind then connects reliably. authType is
                    // intentionally not consulted: on reconnect the watch echoes authType=2, which
                    // on Huawei means HiChain Lite.
                    boolean stsReconnect = (securityNegoReq.honorPairType == 0x02);
                    LOG.debug("HiChain PAKE mode (authType={}, honorPairType={} -> {})",
                            securityNegoReq.authType, securityNegoReq.honorPairType,
                            stsReconnect ? "STS reconnect" : "PAKE bind");
                    initializeDevicePakeMode(securityNegoReq.responseNonce, stsReconnect);
                }
            });
            securityNegoReq.doPerform();
        } catch (IOException e) {
            GB.toast(getContext(), "Authentication of Honor device failed", Toast.LENGTH_SHORT, GB.ERROR, e);
            LOG.error("Authentication of Honor device failed", e);
        }
    }

    /** HiChain PAKE bind / STS reconnect auth path. */
    protected void initializeDevicePakeMode(byte[] securityNonce, boolean stsReconnect) {
        try {
            GetHiChainPakeRequest hiChainReq = new GetHiChainPakeRequest(this, stsReconnect);
            hiChainReq.serverNonce = securityNonce;
            hiChainReq.setFinalizeReq(configureReq);
            hiChainReq.doPerform();
        } catch (IOException e) {
            GB.toast(getContext(), "HiChain PAKE Mode init of Honor device failed", Toast.LENGTH_SHORT, GB.ERROR, e);
            LOG.error("HiChain PAKE Mode init of Honor device failed", e);
        }
    }

    @Override
    protected void initializeDeviceConfigure() {
        // The PAKE/STS path authenticates with its own session keys and never populates the packet
        // secret key. AsynchronousResponse takes that key being non-null as its "auth has finished"
        // signal and silently drops every unsolicited packet while it is null, which kills all
        // device-initiated flows - notably the whole 0x28 file upload state machine, so a watchface
        // install stalls right after the file info request. We are past auth here, so open the
        // async path. These devices don't encrypt transactions, so the key value itself is unused.
        if (getParamsProvider().getSecretKey() == null)
            createSecretKey();

        super.initializeDeviceConfigure();
    }

    /**
     * Persistent 32-byte Ed25519 identity seed for the HiChain PAKE bind. Generated once per device
     * and reused so the peer keeps trusting our long-term key on reconnect. Stored per-device so a
     * re-pair of the same watch keeps the same identity.
     */
    public byte[] getPakeIdentitySeed() {
        SharedPreferences sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(getDeviceMac());
        String seedHex = sharedPrefs.getString("huawei_pake_ed25519_seed", null);
        if (seedHex == null || seedHex.isEmpty()) {
            seedHex = StringUtils.bytesToHex(HuaweiCrypto.generateNonce()); // 16 bytes
            seedHex += StringUtils.bytesToHex(HuaweiCrypto.generateNonce()); // -> 32 bytes
            sharedPrefs.edit().putString("huawei_pake_ed25519_seed", seedHex).apply();
        }
        return GB.hexStringToByteArray(seedHex);
    }

    /**
     * Persists the peer (watch) identity learned during the HiChain PAKE bind exchange: its
     * authId and its Ed25519 public key. These are needed on reconnect for the STS mutual-auth
     * (operationCode 2), which proves possession of the identity keys instead of a fresh PIN.
     */
    public void savePakePeerIdentity(byte[] peerAuthId, byte[] peerAuthPk) {
        SharedPreferences sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(getDeviceMac());
        sharedPrefs.edit()
                .putString("huawei_pake_peer_auth_id", StringUtils.bytesToHex(peerAuthId))
                .putString("huawei_pake_peer_auth_pk", StringUtils.bytesToHex(peerAuthPk))
                .apply();
    }

    /** Watch authId stored at bind, or null if we have never completed a bind with this device. */
    public byte[] getPakePeerAuthId() {
        SharedPreferences sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(getDeviceMac());
        String hex = sharedPrefs.getString("huawei_pake_peer_auth_id", null);
        return (hex == null || hex.isEmpty()) ? null : GB.hexStringToByteArray(hex);
    }

    /** Watch Ed25519 identity public key stored at bind, or null if none. */
    public byte[] getPakePeerAuthPk() {
        SharedPreferences sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(getDeviceMac());
        String hex = sharedPrefs.getString("huawei_pake_peer_auth_pk", null);
        return (hex == null || hex.isEmpty()) ? null : GB.hexStringToByteArray(hex);
    }
}
