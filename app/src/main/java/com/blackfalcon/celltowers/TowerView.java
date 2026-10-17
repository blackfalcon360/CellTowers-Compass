package com.blackfalcon.celltowers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Radar: you in the centre, every tower whose position is known placed by distance and direction.
 * Compass OFF: North is at the top. Compass ON: the radar turns with the phone so the way you face is at the top.
 */
public class TowerView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Tower> list = new ArrayList<>();
    private float heading = 0f;
    private boolean compassOn = false;

    public TowerView(Context c) { super(c); }

    public void setData(List<Tower> towers) {
        list = towers;
        invalidate();
    }

    public void setHeading(float h, boolean on) {
        heading = h;
        compassOn = on;
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private static float niceMax(float m) {
        float[] c = {500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000};
        for (float v : c) if (v >= m * 1.1f) return v;
        return 400000;
    }

    private static String distText(float m) {
        return m >= 1000 ? String.format("%.1f km", m / 1000f) : String.format("%d m", Math.round(m));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f - dp(30);
        float rot = compassOn ? heading : 0f;
        cv.drawColor(0xFF02030A);

        float max = 0;
        int known = 0;
        for (Tower t : list) if (t.hasPos() && t.distM >= 0) { known++; max = Math.max(max, t.distM); }
        float scale = niceMax(max);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(0xFF3A3A60);
        for (int i = 1; i <= 3; i++) cv.drawCircle(cx, cy, r * i / 3f, p);
        // the four spokes turn with the compass
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(k * 90 - rot);
            cv.drawLine(cx, cy, cx + (float) (r * Math.sin(a)), cy - (float) (r * Math.cos(a)), p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(13));
        p.setColor(0xFF9E9E9E);
        for (int i = 1; i <= 3; i++) cv.drawText(distText(scale * i / 3f), cx + dp(2), cy - r * i / 3f - dp(3) + (compassOn ? dp(16) : 0), p);

        // N E S W around the ring
        String[] card = {"N", "E", "S", "W"};
        p.setTextSize(dp(16));
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(k * 90 - rot);
            float x = cx + (float) ((r + dp(16)) * Math.sin(a));
            float y = cy - (float) ((r + dp(16)) * Math.cos(a)) + dp(5);
            p.setColor(k == 0 ? 0xFFE57373 : 0xFF9E9E9E);
            cv.drawText(card[k], x, y, p);
        }

        // "you are facing this way" marker at the top when the compass is on
        if (compassOn) {
            Path tri = new Path();
            tri.moveTo(cx, cy - r - dp(2));
            tri.lineTo(cx - dp(7), cy - r + dp(12));
            tri.lineTo(cx + dp(7), cy - r + dp(12));
            tri.close();
            p.setColor(0xFF2ECC71);
            cv.drawPath(tri, p);
            p.setColor(0xFF2ECC71);
            p.setTextSize(dp(13));
            cv.drawText(Math.round(heading) + "\u00B0", cx, cy - r + dp(30), p);
        }

        // you
        p.setColor(0xFFFFFFFF);
        cv.drawCircle(cx, cy, dp(5), p);

        if (known == 0) {
            p.setColor(0xFF9E9E9E);
            p.setTextSize(dp(14));
            cv.drawText("No tower positions known yet", cx, cy + dp(40), p);
            cv.drawText("Add a free OpenCelliD key in the Info tab", cx, cy + dp(60), p);
            return;
        }

        for (Tower t : list) {
            if (!t.hasPos() || t.distM < 0) continue;
            double a = Math.toRadians(t.bearing - rot);
            float d = Math.min(t.distM / scale, 1f) * r;
            float x = cx + (float) (d * Math.sin(a));
            float y = cy - (float) (d * Math.cos(a));
            p.setColor(t.registered ? 0xFF2ECC71 : 0xFFFFB300);
            cv.drawCircle(x, y, dp(t.registered ? 9 : 6), p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(dp(11));
            cv.drawText(t.operator + " " + t.tech, x, y - dp(12), p);
        }
    }
}
