/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YcbtSessionNegotiationTest {
    @Test
    public void becomesReadyOnlyAfterSupportedModelBatteryAndCapabilities() {
        final YcbtSessionNegotiation negotiation = new YcbtSessionNegotiation(2);

        assertEquals(YcbtSessionNegotiation.Stage.MODEL, negotiation.begin());
        assertFalse(negotiation.isReady());
        assertEquals(YcbtSessionNegotiation.Result.REQUEST_BATTERY, negotiation.handleModel("R11M"));
        assertEquals(YcbtSessionNegotiation.Result.REQUEST_CAPABILITIES, negotiation.handleBattery(68));
        assertEquals(YcbtSessionNegotiation.Result.READY, negotiation.handleCapabilities());
        assertTrue(negotiation.isReady());
    }

    @Test
    public void rejectsModelsOutsideTheSupportedR10mR11mFamily() {
        final YcbtSessionNegotiation negotiation = new YcbtSessionNegotiation(2);

        negotiation.begin();
        assertEquals(YcbtSessionNegotiation.Result.FAILED, negotiation.handleModel("R12M"));
        assertEquals(YcbtSessionNegotiation.Stage.FAILED, negotiation.getStage());
    }

    @Test
    public void retriesEachStageOnceThenFails() {
        final YcbtSessionNegotiation negotiation = new YcbtSessionNegotiation(2);

        negotiation.begin();
        assertEquals(YcbtSessionNegotiation.Timeout.RETRY, negotiation.onTimeout());
        assertEquals(YcbtSessionNegotiation.Timeout.FAILED, negotiation.onTimeout());
        assertEquals(YcbtSessionNegotiation.Stage.FAILED, negotiation.getStage());
    }

    @Test
    public void ignoresOutOfOrderResponses() {
        final YcbtSessionNegotiation negotiation = new YcbtSessionNegotiation(2);

        negotiation.begin();
        assertEquals(YcbtSessionNegotiation.Result.IGNORED, negotiation.handleBattery(68));
        assertEquals(YcbtSessionNegotiation.Result.IGNORED, negotiation.handleCapabilities());
        assertEquals(YcbtSessionNegotiation.Stage.MODEL, negotiation.getStage());
    }
}
