package com.evsenso.treaddepth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProfileView extends View {
    public static class GrooveMark {
        public final double xMm;
        public final double depthMm;
        public final double widthMm;
        public GrooveMark(double xMm, double depthMm, double widthMm) {
            this.xMm = xMm;
            this.depthMm = depthMm;
            this.widthMm = widthMm;
        }
    }

    private double[] depthMm = new double[0];
    private double xStartMm = 0.0;
    private double xStepMm = 0.5;
    private List<GrooveMark> grooves = new ArrayList<>();

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint profile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ProfileView(Context context) { super(context); init(); }
    public ProfileView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ProfileView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        grid.setColor(Color.rgb(205, 216, 228));
        grid.setStrokeWidth(1f);
        axis.setColor(Color.rgb(18, 59, 109));
        axis.setStrokeWidth(2f);
        profile.setColor(Color.rgb(18, 59, 109));
        profile.setStrokeWidth(3f);
        profile.setStyle(Paint.Style.STROKE);
        marker.setColor(Color.rgb(180, 35, 24));
        marker.setStrokeWidth(2f);
        text.setColor(Color.rgb(17, 24, 39));
        text.setTextSize(24f);
    }

    public void setProfile(double[] values, double xStart, double xStep, List<GrooveMark> marks) {
        this.depthMm = values == null ? new double[0] : values.clone();
        this.xStartMm = xStart;
        this.xStepMm = xStep;
        this.grooves = marks == null ? new ArrayList<>() : new ArrayList<>(marks);
        invalidate();
    }

    public void clear() {
        depthMm = new double[0];
        grooves.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = 58f, right = getWidth() - 18f, top = 24f, bottom = getHeight() - 48f;
        if (right <= left || bottom <= top) return;

        for (int i = 0; i <= 6; i++) {
            float y = top + (bottom - top) * i / 6f;
            canvas.drawLine(left, y, right, y, grid);
            double d = 120.0 * i / 6.0;
            canvas.drawText(String.format(Locale.US, "%.0f", d), 8f, y + 8f, text);
        }
        for (int i = 0; i <= 6; i++) {
            float x = left + (right - left) * i / 6f;
            canvas.drawLine(x, top, x, bottom, grid);
            double xm = 420.0 * i / 6.0;
            canvas.drawText(String.format(Locale.US, "%.0f", xm), x - 14f, getHeight() - 12f, text);
        }
        canvas.drawLine(left, top, left, bottom, axis);
        canvas.drawLine(left, bottom, right, bottom, axis);

        if (depthMm.length < 2) {
            text.setTextSize(28f);
            canvas.drawText("Waiting for calibrated tread profile…", left + 20f, (top + bottom) / 2f, text);
            text.setTextSize(24f);
            return;
        }

        boolean havePrev = false;
        float pxPrev = 0f, pyPrev = 0f;
        for (int i = 0; i < depthMm.length; i++) {
            double d = depthMm[i];
            if (Double.isNaN(d)) { havePrev = false; continue; }
            double xMm = xStartMm + i * xStepMm;
            float px = left + (float) (xMm / 420.0) * (right - left);
            float py = top + (float) (Math.max(0.0, Math.min(120.0, d)) / 120.0) * (bottom - top);
            if (havePrev) canvas.drawLine(pxPrev, pyPrev, px, py, profile);
            pxPrev = px; pyPrev = py; havePrev = true;
        }

        text.setTextSize(21f);
        for (int i = 0; i < grooves.size(); i++) {
            GrooveMark g = grooves.get(i);
            float px = left + (float) (g.xMm / 420.0) * (right - left);
            float py = top + (float) (Math.max(0.0, Math.min(120.0, g.depthMm)) / 120.0) * (bottom - top);
            canvas.drawLine(px, top, px, py, marker);
            canvas.drawCircle(px, py, 6f, marker);
            canvas.drawText("G" + (i + 1), px + 5f, Math.max(top + 20f, py - 8f), text);
        }
        text.setTextSize(24f);
    }
}
