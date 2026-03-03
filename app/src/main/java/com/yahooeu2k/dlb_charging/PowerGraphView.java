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

public class PowerGraphView extends View {

    private Paint linePaintMqtt;
    private Paint linePaintBle;
    private Paint gridPaint;
    private Paint textPaint;
    private Path pathMqtt;
    private Path pathBle;

    private List<HistoryManager.DataPoint> data;
    private long minTime, maxTime;
    private int maxWatts;

    public PowerGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaintBle = new Paint();
        linePaintBle.setColor(0xFF00D4FF); // Cyan
        linePaintBle.setStrokeWidth(5f);
        linePaintBle.setStyle(Paint.Style.STROKE);
        linePaintBle.setAntiAlias(true);
        linePaintBle.setShadowLayer(4, 0, 0, 0xFF00D4FF);

        linePaintMqtt = new Paint();
        linePaintMqtt.setColor(0xFFAA00FF); // Purple
        linePaintMqtt.setStrokeWidth(5f);
        linePaintMqtt.setStyle(Paint.Style.STROKE);
        linePaintMqtt.setAntiAlias(true);
        linePaintMqtt.setShadowLayer(4, 0, 0, 0xFFAA00FF);

        gridPaint = new Paint();
        gridPaint.setColor(0xFF444466);
        gridPaint.setStrokeWidth(2f);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

        textPaint = new Paint();
        textPaint.setColor(0xFFCCCCCC);
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);

        pathMqtt = new Path();
        pathBle = new Path();
    }

    public void setData(List<HistoryManager.DataPoint> data) {
        this.data = data;
        if (data != null && !data.isEmpty()) {
            minTime = data.get(0).timestamp;
            maxTime = data.get(data.size() - 1).timestamp;
            
            // Ensure at least 1 hour range for visuals
            if (maxTime - minTime < 3600000) {
                minTime = maxTime - 3600000;
            }

            maxWatts = 0;
            for (HistoryManager.DataPoint p : data) {
                if (p.watts > maxWatts) maxWatts = p.watts;
            }
            if (maxWatts < 100) maxWatts = 100; // Min scale
            maxWatts = (int)(maxWatts * 1.2); // Padding
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
        canvas.drawText("0", 10, chartH, textPaint);
        canvas.drawText(String.valueOf(maxWatts), 10, 50, textPaint);

        if (data == null || data.isEmpty()) {
            canvas.drawText("No Data", w/2f, h/2f, textPaint);
            return;
        }

        pathBle.reset();
        pathMqtt.reset();

        boolean firstBle = true;
        boolean firstMqtt = true;

        for (HistoryManager.DataPoint p : data) {
            float x = paddingLeft + ((p.timestamp - minTime) / (float)(maxTime - minTime)) * chartW;
            float y = chartH - ((float)p.watts / maxWatts) * chartH;

            if (p.isMqtt) {
                if (firstMqtt) { pathMqtt.moveTo(x, y); firstMqtt = false; }
                else pathMqtt.lineTo(x, y);
            } else {
                if (firstBle) { pathBle.moveTo(x, y); firstBle = false; }
                else pathBle.lineTo(x, y);
            }
        }

        canvas.drawPath(pathBle, linePaintBle);
        canvas.drawPath(pathMqtt, linePaintMqtt);
        
        // Time Labels (Start/End)
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        canvas.drawText(sdf.format(new Date(minTime)), paddingLeft, h - 10, textPaint);
        canvas.drawText(sdf.format(new Date(maxTime)), w - 80, h - 10, textPaint);
    }
}
