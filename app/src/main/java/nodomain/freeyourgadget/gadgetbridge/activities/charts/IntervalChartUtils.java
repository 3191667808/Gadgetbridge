/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack.SegmentInfo;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack.SegmentIntensity;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

/**
 * Renders the per-workout interval summary from {@link ActivityTrack} segments. A segment becomes
 * one interval row; its intensity (ACTIVE/REST/UNKNOWN) drives the color and label. Per-segment
 * averages (HR, cadence, speed) are computed from the segment's {@link ActivityPoint}s.
 */
public final class IntervalChartUtils {

    private IntervalChartUtils() {
    }

    /** One interval row derived from a single {@link ActivityTrack} segment. */
    public static final class IntervalRow {
        public final SegmentIntensity type;
        public final int startSeconds;     // offset from track start
        public final int durationSeconds;
        public final int count;            // strokes (rowing); -1 when not encoded
        @Nullable public final Integer distanceMeters;
        @Nullable public final Integer avgHr;
        @Nullable public final Integer avgCadence;
        @Nullable public final Float avgSpeed;  // m/s

        public IntervalRow(final SegmentIntensity type, final int startSeconds, final int durationSeconds,
                           final int count, @Nullable final Integer distanceMeters, @Nullable final Integer avgHr,
                           @Nullable final Integer avgCadence, @Nullable final Float avgSpeed) {
            this.type = type != null ? type : SegmentIntensity.UNKNOWN;
            this.startSeconds = startSeconds;
            this.durationSeconds = durationSeconds;
            this.count = count;
            this.distanceMeters = distanceMeters;
            this.avgHr = avgHr;
            this.avgCadence = avgCadence;
            this.avgSpeed = avgSpeed;
        }
    }

    /**
     * Builds interval rows from a track's segment point-lists and their {@link SegmentInfo}s.
     * Duration is the span of point timestamps in the segment; strokes/distance come from the
     * segment header. HR/cadence/speed averages are computed over valid points in the segment.
     */
    @NonNull
    public static List<IntervalRow> deriveIntervals(@Nullable final List<List<ActivityPoint>> segments,
                                                    @Nullable final List<SegmentInfo> infos) {
        final List<IntervalRow> rows = new ArrayList<>();
        if (segments == null || infos == null) {
            return rows;
        }
        // Track start = first timestamp of the first non-empty segment; interval start offsets
        // are measured from it (matching the chart x-axis, which is zeroed at the track start).
        Long trackStartMs = null;
        for (final List<ActivityPoint> seg : segments) {
            if (seg != null && !seg.isEmpty()) {
                trackStartMs = seg.get(0).getTime().getTime();
                break;
            }
        }

        final int n = Math.min(segments.size(), infos.size());
        for (int i = 0; i < n; i++) {
            final List<ActivityPoint> pts = segments.get(i);
            final SegmentInfo info = infos.get(i);
            if (pts == null || pts.isEmpty()) {
                continue;
            }

            final long firstMs = pts.get(0).getTime().getTime();
            final long lastMs = pts.get(pts.size() - 1).getTime().getTime();
            final int startSeconds = trackStartMs != null
                    ? (int) Math.max(0, Math.round((firstMs - trackStartMs) / 1000.0)) : 0;
            final int durationSeconds = (int) Math.max(0, Math.round((lastMs - firstMs) / 1000.0));

            long hrSum = 0;
            int hrCount = 0;
            long cadenceSum = 0;
            int cadenceCount = 0;
            double speedSum = 0;
            int speedCount = 0;
            for (final ActivityPoint p : pts) {
                final int hr = p.getHeartRate();
                if (hr > 0) {
                    hrSum += hr;
                    hrCount++;
                }
                final int cad = p.getCadence();
                if (cad > 0) {
                    cadenceSum += cad;
                    cadenceCount++;
                }
                final float spd = p.getSpeed();
                if (spd > 0f) {
                    speedSum += spd;
                    speedCount++;
                }
            }

            final Integer avgHr = hrCount > 0 ? (int) Math.round((double) hrSum / hrCount) : null;
            final Integer avgCadence = cadenceCount > 0 ? (int) Math.round((double) cadenceSum / cadenceCount) : null;
            final Float avgSpeed = speedCount > 0 ? (float) (speedSum / speedCount) : null;
            final Integer strokes = info != null ? info.getStrokes() : null;
            final Integer distance = info != null ? info.getDistanceMeters() : null;

            rows.add(new IntervalRow(
                    info != null ? info.getIntensity() : SegmentIntensity.UNKNOWN,
                    startSeconds,
                    durationSeconds,
                    strokes != null ? strokes : -1,
                    distance,
                    avgHr,
                    avgCadence,
                    avgSpeed));
        }
        return rows;
    }

    public static int colorForType(final Context ctx, final SegmentIntensity type) {
        final int colorRes;
        switch (type) {
            case ACTIVE:
                colorRes = R.color.chart_interval_active;
                break;
            case REST:
                colorRes = R.color.chart_interval_rest;
                break;
            default:
                colorRes = R.color.chart_interval_unknown;
                break;
        }
        return ContextCompat.getColor(ctx, colorRes);
    }

    public static String labelForType(final Context ctx, final SegmentIntensity type) {
        switch (type) {
            case ACTIVE:
                return ctx.getString(R.string.interval_type_active);
            case REST:
                return ctx.getString(R.string.interval_type_rest);
            default:
                return ctx.getString(R.string.interval_type_unknown);
        }
    }

    public static String shortLabelForType(final Context ctx, final SegmentIntensity type) {
        return labelForType(ctx, type).toLowerCase(Locale.getDefault());
    }

    public static String totalLabelForType(final Context ctx, final SegmentIntensity type) {
        switch (type) {
            case ACTIVE:
                return ctx.getString(R.string.interval_total_active);
            case REST:
                return ctx.getString(R.string.interval_total_rest);
            default:
                return ctx.getString(R.string.interval_total_unknown);
        }
    }

    /**
     * Returns the unit suffix for the segment {@code count} field, derived from the workout type.
     * Rowing → "str" (strokes), else → null (no unit shown).
     */
    @Nullable
    public static String countUnitFor(final Context ctx, final ActivityKind activityKind) {
        if (activityKind == null) {
            return null;
        }
        if (ActivityKind.isRowingActivity(activityKind)) {
            return ctx.getString(R.string.strokes_unit);
        }
        return null;
    }

    /**
     * Appends an "Intervals" title, a stacked horizontal bar by type, per-type aggregate rows
     * (color swatch + label + duration with percent + count + avg HR), then per-interval rows
     * (#index + duration + count + avg HR). No-op when fewer than 2 intervals.
     */
    public static void populateIntervalSummary(final Context ctx,
                                               final LinearLayout container,
                                               final List<IntervalRow> intervals,
                                               @Nullable final ActivityKind activityKind) {
        if (intervals == null || intervals.size() < 2) {
            return;
        }
        final String countUnit = countUnitFor(ctx, activityKind);
        final String bpm = ctx.getString(R.string.bpm);

        long total = 0;
        final Map<SegmentIntensity, Long> durationByType = new EnumMap<>(SegmentIntensity.class);
        final Map<SegmentIntensity, Long> countByType = new EnumMap<>(SegmentIntensity.class);
        final Map<SegmentIntensity, Long> hrWeightedByType = new EnumMap<>(SegmentIntensity.class);
        final Map<SegmentIntensity, Long> hrWeightByType = new EnumMap<>(SegmentIntensity.class);
        for (final IntervalRow iv : intervals) {
            final long d = Math.max(0, iv.durationSeconds);
            total += d;
            durationByType.merge(iv.type, d, Long::sum);
            if (iv.count > 0) {
                countByType.merge(iv.type, (long) iv.count, Long::sum);
            }
            if (iv.avgHr != null && d > 0) {
                hrWeightedByType.merge(iv.type, (long) iv.avgHr * d, Long::sum);
                hrWeightByType.merge(iv.type, d, Long::sum);
            }
        }
        if (total <= 0) {
            return;
        }

        final int textColor = GBApplication.getTextColor(ctx);
        final int marginPx = dpToPx(ctx, 8);

        final TextView title = new TextView(ctx);
        title.setText(ctx.getString(R.string.workout_intervals));
        title.setTextColor(textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        final LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(marginPx, marginPx, marginPx, marginPx / 2);
        title.setLayoutParams(titleLp);
        container.addView(title);

        // Stacked bar by type, in fixed display order
        final LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(ctx, 14));
        barLp.setMargins(marginPx, 0, marginPx, marginPx / 2);
        bar.setLayoutParams(barLp);
        for (final SegmentIntensity t : SegmentIntensity.values()) {
            final long d = durationByType.getOrDefault(t, 0L);
            if (d <= 0) {
                continue;
            }
            final View seg = new View(ctx);
            seg.setBackgroundColor(colorForType(ctx, t));
            seg.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) d));
            bar.addView(seg);
        }
        container.addView(bar);

        // Per-type aggregate rows
        for (final SegmentIntensity t : SegmentIntensity.values()) {
            final long d = durationByType.getOrDefault(t, 0L);
            if (d <= 0) {
                continue;
            }
            final int percent = (int) Math.round(100.0 * d / total);
            final long c = countByType.getOrDefault(t, 0L);
            final long hrWeight = hrWeightByType.getOrDefault(t, 0L);
            final Integer avgHr = hrWeight > 0
                    ? (int) Math.round((double) hrWeightedByType.getOrDefault(t, 0L) / hrWeight)
                    : null;
            final StringBuilder value = new StringBuilder();
            value.append(String.format(Locale.getDefault(), "%s (%d%%)",
                    DateTimeUtils.formatDurationHoursMinutes(d, TimeUnit.SECONDS), percent));
            if (c > 0) {
                value.append(" · ").append(formatCount(c, countUnit));
            }
            if (avgHr != null) {
                value.append(String.format(Locale.getDefault(), " · %d %s", avgHr, bpm));
            }
            container.addView(buildRow(ctx, colorForType(ctx, t), totalLabelForType(ctx, t),
                    value.toString(), textColor, marginPx));
        }

        // Per-interval list
        for (int i = 0; i < intervals.size(); i++) {
            final IntervalRow iv = intervals.get(i);
            final String label = String.format(Locale.getDefault(), "#%d %s",
                    i + 1, labelForType(ctx, iv.type));
            final StringBuilder value = new StringBuilder(
                    DateTimeUtils.formatDurationHoursMinutes(iv.durationSeconds, TimeUnit.SECONDS));
            if (iv.count > 0) {
                value.append(" · ").append(formatCount(iv.count, countUnit));
            }
            if (iv.avgHr != null) {
                value.append(String.format(Locale.getDefault(), " · %d %s", iv.avgHr, bpm));
            }
            container.addView(buildRow(ctx, colorForType(ctx, iv.type), label,
                    value.toString(), textColor, marginPx));
        }
    }

    private static String formatCount(final long count, @Nullable final String unit) {
        if (unit == null) {
            return String.valueOf(count);
        }
        return String.format(Locale.getDefault(), "%d %s", count, unit);
    }

    private static View buildRow(final Context ctx, final int swatchColor, final String label,
                                 final String value, final int textColor, final int marginPx) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(marginPx, dpToPx(ctx, 2), marginPx, dpToPx(ctx, 2));
        row.setLayoutParams(rowLp);

        final View swatch = new View(ctx);
        final LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(dpToPx(ctx, 12), dpToPx(ctx, 12));
        swatchLp.rightMargin = dpToPx(ctx, 8);
        swatch.setLayoutParams(swatchLp);
        swatch.setBackgroundColor(swatchColor);
        row.addView(swatch);

        final TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextColor(textColor);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelView);

        final TextView valueView = new TextView(ctx);
        valueView.setText(value);
        valueView.setTextColor(textColor);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        valueView.setGravity(Gravity.END);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

        return row;
    }

    private static int dpToPx(final Context ctx, final int dp) {
        final float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
