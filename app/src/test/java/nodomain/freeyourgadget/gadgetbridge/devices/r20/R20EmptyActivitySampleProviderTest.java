/*  Copyright (C) 2026 Ariel Saghiv

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.devices.r20;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class R20EmptyActivitySampleProviderTest {

    private final R20EmptyActivitySampleProvider P = new R20EmptyActivitySampleProvider();

    @Test public void allSampleQueriesReturnEmptyNonNull() {
        List<GenericActivitySample> a = P.getAllActivitySamples(0, Integer.MAX_VALUE);
        List<GenericActivitySample> b = P.getAllActivitySamplesHighRes(0, Integer.MAX_VALUE);
        List<GenericActivitySample> c = P.getActivitySamples(0, Integer.MAX_VALUE);
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertTrue(a.isEmpty());
        assertTrue(b.isEmpty());
        assertTrue(c.isEmpty());
    }

    @Test public void singletonQueriesReturnNullCleanly() {
        assertNull(P.getLatestActivitySample());
        assertNull(P.getLatestActivitySample(Integer.MAX_VALUE));
        assertNull(P.getFirstActivitySample());
    }

    @Test public void factoryProducesNonNullSampleForCallers() {
        assertNotNull(P.createActivitySample());
    }

    @Test public void writesAreSilentlyDropped() {
        P.addGBActivitySample(new GenericActivitySample());
        P.addGBActivitySamples(Collections.emptyList());
        P.addGBActivitySamples(Collections.singletonList(new GenericActivitySample()));
    }

    @Test public void activityKindNormalizesToUnknown() {
        assertEquals(ActivityKind.UNKNOWN, P.normalizeType(123));
        assertEquals(0, P.toRawActivityKind(ActivityKind.RUNNING));
        assertEquals(0f, P.normalizeIntensity(50), 0.0001);
    }

    @Test public void hasNoHighResData() {
        assertFalse(P.hasHighResData());
    }
}
