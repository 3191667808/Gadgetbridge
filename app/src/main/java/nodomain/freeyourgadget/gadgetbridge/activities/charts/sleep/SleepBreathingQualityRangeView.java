/*  Copyright (C) 2026 d3vv3

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import nodomain.freeyourgadget.gadgetbridge.R;

public class SleepBreathingQualityRangeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pointer = new Path();
    private int score;

    public SleepBreathingQualityRangeView(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setScore(final int score) {
        this.score = Math.max(0, Math.min(100, score));
        setContentDescription(getContext().getString(
                R.string.withings_sleep_breathing_quality_range_content_description, this.score));
        invalidate();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int desiredHeight = Math.round(dp(34));
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final float left = getPaddingLeft();
        final float right = getWidth() - getPaddingRight();
        final float top = dp(12);
        final float bottom = top + dp(10);
        final float width = Math.max(0, right - left);

        drawBand(canvas, left, left + width * 0.30f, top, bottom, R.color.hrv_status_low);
        drawBand(canvas, left + width * 0.30f, left + width * 0.60f, top, bottom, R.color.hrv_status_unbalanced);
        drawBand(canvas, left + width * 0.60f, right, top, bottom, R.color.hrv_status_balanced);

        final float pointerX = Math.max(left + dp(4), Math.min(right - dp(4), left + width * score / 100f));
        paint.setColor(resolveTextColor());
        pointer.reset();
        pointer.moveTo(pointerX, top);
        pointer.lineTo(pointerX - dp(5), top - dp(8));
        pointer.lineTo(pointerX + dp(5), top - dp(8));
        pointer.close();
        canvas.drawPath(pointer, paint);
        canvas.drawRect(pointerX - dp(1), top, pointerX + dp(1), bottom + dp(3), paint);
    }

    private void drawBand(final Canvas canvas, final float left, final float right,
                          final float top, final float bottom, final int colorRes) {
        paint.setColor(ContextCompat.getColor(getContext(), colorRes));
        canvas.drawRect(left, top, right, bottom, paint);
    }

    private int resolveTextColor() {
        final TypedValue value = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true);
        return value.resourceId != 0 ? ContextCompat.getColor(getContext(), value.resourceId) : value.data;
    }

    private float dp(final int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
