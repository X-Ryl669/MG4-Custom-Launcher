package com.custom.launcher.energy;

import java.util.List;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Compact time graph of consumption in kWh/100km.
 *
 * <p>
 * Drawn rather than charted: the tile is ~300dp wide, so this is a filled area
 * plot with an average line and an emphasised endpoint.
 *
 * <h3>Scale</h3>
 * The y axis is anchored at zero and topped at the next round number above the
 * highest sample, and the gridlines are labelled in kWh/100km. It used to
 * auto-scale between the observed min and max with no axis at all, which made a
 * steady 15 kWh/100km cruise and a hard-driven 40 look identical — every trace
 * filled the box. A fixed origin means the height of the trace now means
 * something at a glance.
 */
public class ConsumptionGraphView extends View {

    private static final int MIN_SAMPLES_TO_DRAW = 2;
    /** Round ceilings for the y axis, so the gridline labels stay readable. */
    private static final float[] NICE_CEILINGS = { 10f, 15f, 20f, 30f, 40f, 60f, 80f, 100f };

    /** Gridlines at these fractions of the ceiling, plus the zero baseline. */
    private static final float[] GRID_FRACTIONS = { 1f / 3f, 2f / 3f, 1f };

    private static final float LABEL_GUTTER_DP = 26f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint averagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endpointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final Path averagePath = new Path();

    private List<ConsumptionHistory.Sample> samples;
    private float average;

    public ConsumptionGraphView(Context context) {
        super(context);
        init();
    }

    public ConsumptionGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ConsumptionGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(Color.parseColor("#30d158"));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor("#3030d158"));

        averagePaint.setStyle(Paint.Style.STROKE);
        averagePaint.setStrokeWidth(1f * density);
        averagePaint.setColor(Color.parseColor("#59636366"));
        averagePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[] { 4f * density, 4f * density }, 0f));

        endpointPaint.setStyle(Paint.Style.FILL);
        endpointPaint.setColor(Color.parseColor("#30d158"));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, 0.5f * density));
        gridPaint.setColor(0x26FFFFFF);

        gridLabelPaint.setColor(0x73FFFFFF);
        gridLabelPaint.setTextSize(9f * getResources().getDisplayMetrics().scaledDensity);
        gridLabelPaint.setTextAlign(Paint.Align.RIGHT);

        labelGutter = LABEL_GUTTER_DP * density;
    }

    private float labelGutter;

    /**
     * Smallest round ceiling that still contains {@code max}. Falls back to a
     * multiple of 100 for the absurd case, so the axis is never smaller than the
     * data.
     */
    private static float niceCeiling(float max) {
        for (float candidate : NICE_CEILINGS) {
            if (max <= candidate) {
                return candidate;
            }
        }
        return (float) (Math.ceil(max / 100f) * 100f);
    }

    public void setSamples(List<ConsumptionHistory.Sample> samples) {
        this.samples = samples;
        this.average = computeAverage(samples);
        invalidate();
    }

    /** Mean of the plotted samples, or NaN when there is nothing to plot. */
    public float getAverage() {
        return average;
    }

    private static float computeAverage(List<ConsumptionHistory.Sample> samples) {
        if (samples == null || samples.isEmpty()) {
            return Float.NaN;
        }
        float sum = 0f;
        for (ConsumptionHistory.Sample s : samples) {
            sum += s.value;
        }
        return sum / samples.size();
    }

    /** Lowest plotted value, or NaN if nothing is plotted. */
    public float getMin() {
        if (samples == null || samples.isEmpty()) {
            return Float.NaN;
        }
        float min = Float.MAX_VALUE;
        for (ConsumptionHistory.Sample s : samples) {
            min = Math.min(min, s.value);
        }
        return min;
    }

    /** Highest plotted value, or NaN if nothing is plotted. */
    public float getMax() {
        if (samples == null || samples.isEmpty()) {
            return Float.NaN;
        }
        float max = -Float.MAX_VALUE;
        for (ConsumptionHistory.Sample s : samples) {
            max = Math.max(max, s.value);
        }
        return max;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float inset = linePaint.getStrokeWidth();
        float top = inset;
        float bottom = h - inset;
        float usableHeight = bottom - top;
        float plotLeft = labelGutter;
        float plotWidth = w - plotLeft;
        if (usableHeight <= 0 || plotWidth <= 0) {
            return;
        }

        // The axis is drawn even with no data, so an empty tile still reads as a
        // graph waiting for samples rather than as a blank panel.
        float ceiling = niceCeiling(samples == null || samples.isEmpty()
                ? 0f : Math.max(getMax(), 0f));

        drawGrid(canvas, plotLeft, w, top, bottom, usableHeight, ceiling);

        if (samples == null || samples.size() < MIN_SAMPLES_TO_DRAW) {
            return;
        }

        int n = samples.size();
        float stepX = (n > 1) ? plotWidth / (n - 1) : plotWidth;

        linePath.reset();
        fillPath.reset();

        float lastX = plotLeft;
        float lastY = bottom;
        for (int i = 0; i < n; i++) {
            float x = plotLeft + i * stepX;
            float y = yFor(samples.get(i).value, ceiling, top, bottom, usableHeight);

            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }

        fillPath.lineTo(lastX, bottom);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);

        if (!Float.isNaN(average)) {
            // Drawn as a Path, not drawLine: DashPathEffect is not reliably
            // honoured for drawLine on a hardware-accelerated canvas.
            float avgY = yFor(average, ceiling, top, bottom, usableHeight);
            averagePath.reset();
            averagePath.moveTo(plotLeft, avgY);
            averagePath.lineTo(w, avgY);
            canvas.drawPath(averagePath, averagePaint);
        }

        canvas.drawPath(linePath, linePaint);
        canvas.drawCircle(lastX, lastY, linePaint.getStrokeWidth() * 1.6f, endpointPaint);
    }

    private void drawGrid(Canvas canvas, float plotLeft, float right, float top, float bottom,
            float usableHeight, float ceiling) {
        // Baseline at zero, unlabelled: the axis starting at zero is the point.
        canvas.drawLine(plotLeft, bottom, right, bottom, gridPaint);

        float labelX = plotLeft - 4f;
        for (float fraction : GRID_FRACTIONS) {
            float value = ceiling * fraction;
            float y = bottom - fraction * usableHeight;
            canvas.drawLine(plotLeft, y, right, y, gridPaint);
            canvas.drawText(formatGridLabel(value), labelX,
                    y - (gridLabelPaint.ascent() + gridLabelPaint.descent()) / 2f,
                    gridLabelPaint);
        }
    }

    private static String formatGridLabel(float value) {
        // Thirds of 10 or 15 are not whole numbers; everything else is.
        return value == Math.rint(value)
                ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.US, "%.1f", value);
    }

    private static float yFor(float value, float ceiling, float top, float bottom,
            float usableHeight) {
        if (ceiling <= 0f) {
            return bottom;
        }
        float y = bottom - (value / ceiling) * usableHeight;
        return Math.max(top, Math.min(bottom, y));
    }
}
