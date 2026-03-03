package com.yahooeu2k.dlb_charging;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AmpsGraphView extends View {

    private Paint linePaintAmps;
    private Paint gridPaint;
    private Paint textPaint;
    private Path pathAmps;

    private List<AmpsHistoryManager.AmpsDataPoint> data;
    private long minTime, maxTime;
    private int maxAmps;

    public AmpsGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaintAmps = new Paint();
        linePaintAmps.setColor(0xFF00FF88); // Green, matching the amp text color
        linePaintAmps.setStrokeWidth(5f);
        linePaintAmps.setStyle(Paint.Style.STROKE);
        linePaintAmps.setAntiAlias(true);
        linePaintAmps.setShadowLayer(4, 0, 0, 0xFF00FF88);

        gridPaint = new Paint();
        gridPaint.setColor(0xFF444466);
        gridPaint.setStrokeWidth(2f);
        gridPaint.setPathEffect(new DashPathEffect(new float[] { 10, 10 }, 0));

        textPaint = new Paint();
        textPaint.setColor(0xFFCCCCCC);
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);

        pathAmps = new Path();
    }

    public void setData(List<AmpsHistoryManager.AmpsDataPoint> data) {
        this.data = data;
        if (data != null && !data.isEmpty()) {
            minTime = data.get(0).timestamp;
            maxTime = data.get(data.size() - 1).timestamp;

            // Ensure at least 1 hour range for visuals
            if (maxTime - minTime < 3600000) {
                minTime = maxTime - 3600000;
            }

            maxAmps = 32; // Hardware maximum limit for charging
            for (AmpsHistoryManager.AmpsDataPoint p : data) {
                if (p.amps > maxAmps)
                    maxAmps = p.amps;
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        int paddingBottom = 40;
        int paddingLeft = 120;
        int chartH = h - paddingBottom;
        int chartW = w - paddingLeft;

        // Draw Grid
        canvas.drawLine(paddingLeft, 0, paddingLeft, chartH, gridPaint);
        canvas.drawLine(paddingLeft, chartH, w, chartH, gridPaint);

        // Y-Axis Labels
        canvas.drawText("0 A", 10, chartH, textPaint);
        canvas.drawText(maxAmps + " A", 10, 50, textPaint);

        if (data == null || data.isEmpty()) {
            canvas.drawText("No Data", w / 2f, h / 2f, textPaint);
            return;
        }

        pathAmps.reset();

        boolean firstAmps = true;

        for (AmpsHistoryManager.AmpsDataPoint p : data) {
            float x = paddingLeft + ((p.timestamp - minTime) / (float) (maxTime - minTime)) * chartW;
            float y = chartH - ((float) p.amps / maxAmps) * chartH;

            if (firstAmps) {
                pathAmps.moveTo(x, y);
                firstAmps = false;
            } else
                pathAmps.lineTo(x, y);
        }

        canvas.drawPath(pathAmps, linePaintAmps);

        // Time Labels (Start/End)
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        canvas.drawText(sdf.format(new Date(minTime)), paddingLeft, h - 10, textPaint);
        canvas.drawText(sdf.format(new Date(maxTime)), w - 80, h - 10, textPaint);
    }
}
