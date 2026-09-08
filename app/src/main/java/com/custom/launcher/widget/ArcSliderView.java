package com.custom.launcher.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.custom.launcher.R;

/**
 * A round dial you drag around, for setting one value with one finger.
 *
 * <h3>Why not a SeekBar</h3>
 * Temperature and fan were side-by-side horizontal SeekBars, and on the car they
 * ended up close enough together to grab the wrong one — the thumb is a few
 * millimetres tall on a dash screen you are reaching across for. A dial gives the
 * whole circle as a target instead of a thin horizontal strip, and two dials side
 * by side are unmistakable because they are in different places rather than
 * different rows.
 *
 * <h3>Gesture model</h3>
 * The value follows the finger's <em>angle</em> around the centre, not its
 * distance, so the touch can start anywhere in the view and wander. The sweep is
 * a 270° arc starting at the lower left and ending at the lower right, with the
 * gap at the bottom: that gap is what stops a drag past maximum from wrapping
 * round to minimum, which is the classic way a rotary control sets the cabin to
 * 16° when you wanted 32°. Angles landing in the gap clamp to whichever end is
 * nearer.
 *
 * <p>
 * Values are reported as integer steps, so the caller decides what a step means
 * (0.5°C for temperature, one notch for fan). {@link OnValueChangeListener} fires
 * continuously while dragging so the label can track the finger, and
 * {@link OnValueCommitListener} fires once on release — writing a vehicle
 * property on every pixel of a drag would flood the car bus.
 */
public class ArcSliderView extends View {

    /** Where the arc starts, in Android's canvas degrees (0 = 3 o'clock, CW+). */
    private static final float ARC_START_DEG = 135f;
    /** How far it sweeps. 270 leaves a 90° gap at the bottom. */
    private static final float ARC_SWEEP_DEG = 270f;

    private static final float TRACK_WIDTH_DP = 12f;
    private static final float THUMB_RADIUS_DP = 12f;
    private static final float MARKS_RADIUS_DP = 7f;

    private static final float MARGIN_DP = 32f;

    public interface OnValueChangeListener {
        void onValueChanged(ArcSliderView view, int value, boolean fromUser);
    }

    public interface OnValueCommitListener {
        void onValueCommitted(ArcSliderView view, int value);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint darkenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private SweepGradient tempGradient = null;
    private SweepGradient stepGradient = null;
    private final RectF arcBounds = new RectF();

    private int minValue;
    private int maxValue = 100;
    private int value;

    private String label = "";
    private String valueText = "";

    private boolean dragging;
    private OnValueChangeListener changeListener;
    private OnValueCommitListener commitListener;

    private float trackWidth;
    private float thumbRadius;
    private float markRadius;

    public ArcSliderView(Context context) {
        this(context, null);
    }

    public ArcSliderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ArcSliderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = getResources().getDisplayMetrics().density;
        trackWidth = TRACK_WIDTH_DP * density;
        thumbRadius = THUMB_RADIUS_DP * density;
        markRadius = MARKS_RADIUS_DP * density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(trackWidth);
        trackPaint.setColor(0x33FFFFFF);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeWidth(trackWidth + 8f * density);
        strokePaint.setColor(0xFF000000);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(trackWidth);
        progressPaint.setColor(0xFFFFFFFF);

        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(0xFF666666);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(0xFFC0C0C0);
        strokeThumbPaint.setStyle(Paint.Style.FILL);
        strokeThumbPaint.setColor(0xFF000000);

        darkenPaint.setStyle(Paint.Style.STROKE);
        darkenPaint.setStrokeWidth(4f * density);
        darkenPaint.setColor(0xFF808080);

        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(4f * density);
        highlightPaint.setColor(0xFFFFFFFF);

        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setColor(0x99FFFFFF);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ArcSliderView);
            String configured = a.getString(R.styleable.ArcSliderView_arcLabel);
            if (configured != null) {
                label = configured;
            }
            a.recycle();
        }
    }

    // --- configuration ---

    public void setRange(int min, int max) {
        minValue = min;
        maxValue = Math.max(min, max);
        value = clamp(value);
        invalidate();
    }

    /** Sets the value without notifying listeners — for polling the car. */
    public void setValue(int newValue) {
        if (dragging) {
            // Never yank the dial out from under a finger.
            return;
        }
        int clamped = clamp(newValue);
        if (clamped != value) {
            value = clamped;
            invalidate();
        }
    }

    public int getValue() {
        return value;
    }

    /** The big number in the middle. The caller formats it, units and all. */
    public void setValueText(String text) {
        String safe = text == null ? "" : text;
        if (!safe.equals(valueText)) {
            valueText = safe;
            invalidate();
        }
    }

    public void setLabel(String text) {
        label = text == null ? "" : text;
        invalidate();
    }

    public void setOnValueChangeListener(OnValueChangeListener listener) {
        changeListener = listener;
    }

    public void setOnValueCommitListener(OnValueCommitListener listener) {
        commitListener = listener;
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.4f);
        /*
        if (!enabled && trackPaint.getShader() != null) {
            // Disable fancy graphics
            trackPaint.setShader(null);
            trackPaint.setColor(0x33FFFFFF);
            invalidate();
        } else if (enabled && trackPaint.getShader() == null) {
            // Enable fancy graphics
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
            invalidate();
        }*/
    }

    // --- drawing ---
    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        //float density = getResources().getDisplayMetrics().density;
        float cx = width / 2f; //(width - (int)(MARGIN_DP * density)) / 2f;
        float cy = height / 2f; //(height - (int)(MARGIN_DP * density)) / 2f;


        int colorCount = maxValue - minValue;
        if (colorCount < 12)
        {
            int[] greyBars = new int[colorCount * 2];
            float[] positionsBars = new float[colorCount * 2];
            for (int v = 0; v < colorCount; v++)
            {
                float c[] = {186f, 1.0f, (float)v / (float)(colorCount - 1)};
                greyBars[v * 2 + 0] = Color.HSVToColor(c);
                greyBars[v * 2 + 1] = Color.HSVToColor(c);
                float start = (float)v / (float)(colorCount), end = (float)(v+1) / (float)(colorCount) - 0.01f;
                positionsBars[v * 2 + 0] = start * ARC_SWEEP_DEG / 360f ;
                positionsBars[v * 2 + 1] = end * ARC_SWEEP_DEG / 360f;
            }
            stepGradient = new SweepGradient(cx, cy, greyBars, positionsBars);
            Matrix matrix = new Matrix();
            matrix.postRotate(270f - ARC_START_DEG, cx, cy); // rotate
            stepGradient.setLocalMatrix(matrix);

            trackPaint.setColor(0xFFFFFFFF);
            trackPaint.setShader(stepGradient);
        } else
        {
            int[] colors = { Color.BLUE, Color.GREEN, Color.YELLOW, Color.RED};
            float[] positions = {0, 0.25f * ARC_SWEEP_DEG / 360f, 0.5f * ARC_SWEEP_DEG / 360f, 1.0f * ARC_SWEEP_DEG / 360f};
            tempGradient = new SweepGradient(cx, cy, colors, positions);
            Matrix matrix = new Matrix();
            matrix.postRotate(270f - ARC_START_DEG, cx, cy); // rotate
            tempGradient.setLocalMatrix(matrix);

            trackPaint.setColor(0xFFFFFFFF);
            trackPaint.setShader(tempGradient);
        }

    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        int width = getWidth() - (int)(MARGIN_DP * density);
        int height = getHeight() - (int)(MARGIN_DP * density);
        float inset = trackWidth / 2f + thumbRadius - trackWidth / 2f;
        float diameter = Math.min(width, height) - 2f * Math.max(inset, thumbRadius);
        if (diameter <= 0) {
            return;
        }

        float cx = width / 2f + MARGIN_DP * density / 2f;
        float cy = height / 2f + MARGIN_DP * density / 2f;
        float radius = diameter / 2f;
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawArc(arcBounds, ARC_START_DEG, ARC_SWEEP_DEG, false, strokePaint);
        canvas.drawArc(arcBounds, ARC_START_DEG, ARC_SWEEP_DEG, false, trackPaint);



        if (maxValue - minValue < 12)
        {
            for (int v = minValue; v < maxValue; v++)
            {
                double thumbRad = Math.toRadians(ARC_START_DEG + ARC_SWEEP_DEG * fractionOf(v));
                canvas.drawCircle(cx + (float) (radius * Math.cos(thumbRad)),
                        cy + (float) (radius * Math.sin(thumbRad)), markRadius, markPaint);
            }
        }

        float fraction = fractionOf(value);
        if (fraction > 0f) {
            canvas.drawArc(arcBounds, ARC_START_DEG, ARC_SWEEP_DEG * fraction, false,
                    progressPaint);
        }

        double thumbRad = Math.toRadians(ARC_START_DEG + ARC_SWEEP_DEG * fraction);
        canvas.drawCircle(cx + (float) (radius * Math.cos(thumbRad)),
                cy + (float) (radius * Math.sin(thumbRad)), thumbRadius + 4f * density, strokeThumbPaint);
        canvas.drawCircle(cx + (float) (radius * Math.cos(thumbRad)),
                cy + (float) (radius * Math.sin(thumbRad)), thumbRadius, thumbPaint);

        // Fancy glass effect for the thumb now
        float tr = thumbRadius * 0.75f;
        arcBounds.set(cx + (float) (radius * Math.cos(thumbRad)) - tr, cy + (float) (radius * Math.sin(thumbRad)) - tr, cx + (float) (radius * Math.cos(thumbRad)) + tr, cy + (float) (radius * Math.sin(thumbRad)) + tr);
        canvas.drawArc(arcBounds, 45f, 135f, false, darkenPaint);
        canvas.drawArc(arcBounds, 0f, -135f, false, highlightPaint);

        // Type scales with the dial so one layout works at any card size.
        valuePaint.setTextSize(diameter * 0.30f);
        labelPaint.setTextSize(Math.max(11f, diameter * 0.12f));

        Paint.FontMetrics fm = valuePaint.getFontMetrics();
        canvas.drawText(valueText, cx, cy - (fm.ascent + fm.descent) / 2f, valuePaint);
        canvas.drawText(label, cx, cy + diameter * 0.32f, labelPaint);
    }

    // --- touch ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                // The dial owns the gesture from here; stop any scrolling ancestor
                // from stealing it mid-drag.
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (commitListener != null) {
                        commitListener.onValueCommitted(this, value);
                    }
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateFromTouch(MotionEvent event) {
        float dx = event.getX() - getWidth() / 2f;
        float dy = event.getY() - getHeight() / 2f;

        // Angle measured from the arc's start, going clockwise, in [0, 360).
        float fromStart = (float) Math.toDegrees(Math.atan2(dy, dx)) - ARC_START_DEG;
        while (fromStart < 0f) {
            fromStart += 360f;
        }

        float fraction;
        if (fromStart <= ARC_SWEEP_DEG) {
            fraction = fromStart / ARC_SWEEP_DEG;
        } else {
            // In the dead zone at the bottom. Snap to the nearer end rather than
            // wrapping, so dragging off the top does not jump to minimum.
            float pastEnd = fromStart - ARC_SWEEP_DEG;
            fraction = pastEnd < (360f - ARC_SWEEP_DEG) / 2f ? 1f : 0f;
        }

        int newValue = clamp(minValue + Math.round(fraction * (maxValue - minValue)));
        if (newValue != value) {
            value = newValue;
            invalidate();
            if (changeListener != null) {
                changeListener.onValueChanged(this, value, true);
            }
        }
    }

    private float fractionOf(int v) {
        if (maxValue == minValue) {
            return 0f;
        }
        return (clamp(v) - minValue) / (float) (maxValue - minValue);
    }

    private int clamp(int v) {
        return Math.max(minValue, Math.min(maxValue, v));
    }
}
