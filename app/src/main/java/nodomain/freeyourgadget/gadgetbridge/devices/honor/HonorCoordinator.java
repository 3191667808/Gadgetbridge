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
package nodomain.freeyourgadget.gadgetbridge.devices.honor;

/**
 * Marks a coordinator as speaking the newer Honor protocol variant, and is the place for any setting
 * that only exists there. Implemented by {@link HonorLECoordinator} and
 * {@link HonorBRCoordinator}, which live in the BTLE and BT classic coordinator hierarchies
 * respectively - hence an interface rather than a common base class.
 * <p>
 * Note that the authentication flow is deliberately <em>not</em> declared here: whether a connection
 * uses the simple handshake or the HiChain PAKE / STS flow is announced by the device per
 * connection, not fixed per model - see
 * {@code GetHonorSecurityNegotiationRequest#isPakeAuthentication()}.
 */
public interface HonorCoordinator {
}
