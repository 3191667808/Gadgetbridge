/*  Copyright (C) 2026 Vitaliy Tomin

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.honor.requests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.DeviceConfig;
import nodomain.freeyourgadget.gadgetbridge.service.devices.honor.HonorSupportProvider;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.Request;

/**
 * Security negotiation (service 0x01 / command 0x33) for Honor devices, and the point at which the
 * two Honor authentication flows are told apart.
 * <p>
 * Which flow a connection uses is decided by the device, per connection, in the link params (0x01)
 * response: when it announces a HiChain support type (tag 0x07) it wants the PAKE / STS flow and
 * this negotiation runs; when it does not, it wants the simple challenge/response auth (0x13) and
 * no negotiation goes on the wire at all - {@link #requestSupported()} suppresses the packet while
 * the finalize callback still runs, so the caller can branch on
 * {@link #isPakeAuthentication()}. Captures:
 * <ul>
 *     <li>Honor Watch 4: deviceSupportType 0, no 0x33 anywhere in the session, straight to 0x13;</li>
 *     <li>Honor Watch 5: deviceSupportType 1, 0x33 then the 0x28 PAKE exchange;</li>
 *     <li>Honor Watch 6: <em>both</em>, 26 s apart on the same watch with a byte-identical link
 *     params request - deviceSupportType 0 / bondState 0 while unbonded (0x13), then
 *     deviceSupportType 1 / bondState 1 once bonded (0x33 + PAKE).</li>
 * </ul>
 * So this must never be decided per device model.
 * <p>
 * The request itself is the MBB auth-type variant - authType, pairType and our authId in tags
 * 1/2/3 - as opposed to the Huawei variant (authType, authId, phone model) in
 * {@code GetSecurityNegotiationRequest}. Once a bind has registered our identity with the watch (we
 * have a stored peer authId) we request RECONNECT (pairType 2), which the watch grants and answers
 * with an STS PSK-SPEKE handshake (msg 17/18 &lt;-&gt; 32785/32786; see
 * {@link GetHiChainPakeRequest}). Before that first bind, or if the stored identity is stale, we
 * request FIRST_PAIR (pairType 1). If the watch declines STS it simply answers pairType 1 and we
 * fall back to a fresh (seamless) bind.
 */
public class GetHonorSecurityNegotiationRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(GetHonorSecurityNegotiationRequest.class);

    public int authType = 0x00;
    /**
     * Watch-dictated auth path (tag 0x02 of the 0x33 response): 1 = fresh bind, 2 = STS reconnect,
     * -1 = not reported. Drives whether HonorSupportProvider runs a PAKE bind or STS.
     */
    public int honorPairType = -1;
    public byte[] responseNonce;

    /** Same instance as {@code supportProvider}, typed for the Honor-only PAKE identity storage. */
    private final HonorSupportProvider honorSupport;

    public GetHonorSecurityNegotiationRequest(HonorSupportProvider support) {
        super(support);
        this.honorSupport = support;
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.SecurityNegotiation.id;
    }

    /**
     * Whether this connection authenticates with the HiChain PAKE / STS flow, i.e. whether the
     * device announced a HiChain support type in the link params. False means simple auth, and then
     * nothing is sent for this request.
     */
    public boolean isPakeAuthentication() {
        return paramsProvider.getDeviceSupportType() != 0x00;
    }

    @Override
    protected boolean requestSupported() {
        // Simple-auth connections never negotiate: the vendor app sends no 0x33 at all there.
        return isPakeAuthentication();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            final int pairType = (honorSupport.getPakePeerAuthId() != null) ? 2 : 1;
            return new DeviceConfig.SecurityNegotiation.Request(
                    paramsProvider,
                    paramsProvider.getAuthMode(),
                    pairType,
                    honorSupport.getAndroidId()
            ).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() {
        LOG.debug("handle Honor Security and Negotiation");

        if (!(receivedPacket instanceof DeviceConfig.SecurityNegotiation.Response)) {
            // TODO: exception
            return;
        }

        this.authType = ((DeviceConfig.SecurityNegotiation.Response) receivedPacket).authType;
        this.honorPairType = ((DeviceConfig.SecurityNegotiation.Response) receivedPacket).honorPairType;
        this.responseNonce = ((DeviceConfig.SecurityNegotiation.Response) receivedPacket).responseNonce;
    }
}
