/*  Copyright (C) 2026 Ariel Saghiv

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.devices.r20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

/**
 * No-op aggregate {@link GenericActivitySample} provider for the R20 ring.
 *
 * <p>The R20 firmware streams discrete metrics (heart rate, SpO2, blood pressure, sleep)
 * to dedicated time-sample providers; it does not produce aggregate ActivitySample rows.
 * However several Gadgetbridge UI surfaces (chart fragments, dashboard widgets,
 * HRVStatusFragment) call {@code getSampleProvider().getAllActivitySamples} unconditionally
 * before checking {@code supportsActivityTracking()}, which previously NPE'd when we
 * returned {@code null}. This stub returns empty lists so those screens render a clean
 * "no data" state instead of crashing.
 */
public final class R20EmptyActivitySampleProvider implements SampleProvider<GenericActivitySample> {

    @Override public ActivityKind normalizeType(int rawType) { return ActivityKind.UNKNOWN; }
    @Override public int toRawActivityKind(ActivityKind activityKind) { return 0; }
    @Override public float normalizeIntensity(int rawIntensity) { return 0f; }

    @NonNull
    @Override public List<GenericActivitySample> getAllActivitySamples(int tFrom, int tTo) { return Collections.emptyList(); }
    @Override public List<GenericActivitySample> getAllActivitySamplesHighRes(int tFrom, int tTo) { return Collections.emptyList(); }
    @Override public boolean hasHighResData() { return false; }
    @NonNull
    @Override public List<GenericActivitySample> getActivitySamples(int tFrom, int tTo) { return Collections.emptyList(); }

    @Override public void addGBActivitySample(GenericActivitySample activitySample) { /* no-op */ }
    @Override public void addGBActivitySamples(@NonNull List<GenericActivitySample> activitySamples) { /* no-op */ }

    @Override public GenericActivitySample createActivitySample() { return new GenericActivitySample(); }
    @Nullable @Override public GenericActivitySample getLatestActivitySample() { return null; }
    @Nullable @Override public GenericActivitySample getLatestActivitySample(int until) { return null; }
    @Nullable @Override public GenericActivitySample getFirstActivitySample() { return null; }
}
