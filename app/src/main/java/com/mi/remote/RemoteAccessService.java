package com.mi.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.*;
import android.media.AudioManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.net.*;

public class RemoteAccessService extends AccessibilityService {
    static volatile RemoteAccessService inst;
    final Handler ui = new Handler(Looper.getMainLooper());
    WindowManager wm;
    View cursor;
    float cx, cy;
    int sw, sh;
    ServerSocket server;
    volatile boolean run;

    @Override protected void onServiceConnected() {
        inst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        sw = m.widthPixels; sh = m.heightPixels; cx = sw / 2f; cy = sh / 2f;
        final float d = m.density;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xCCFFFFFF);
        edge.setColor(Color.BLACK); edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(2 * d);
        dot.setColor(0xFFD71921);
        cursor = new View(this) {
            @Override protected void onDraw(Canvas c) {
                c.drawCircle(cx, cy, 12 * d, fill);
                c.drawCircle(cx, cy, 12 * d, edge);
                c.drawCircle(cx, cy, 4 * d, dot);
            }
        };
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(cursor, p);
        startServer();
    }

    void startServer() {
        run = true;
        new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(MainActivity.PORT));
                while (run) {
                    final Socket s = server.accept();
                    new Thread(() -> serve(s)).start();
                }
            } catch (Exception e) { }
        }).start();
    }

    void serve(Socket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String pin = getSharedPreferences("mi", MODE_PRIVATE).getString("pin", "");
            String first = r.readLine();
            if (first == null || !first.equals("AUTH " + pin)) { s.close(); return; }
            s.getOutputStream().write("OK\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
            String l;
            while ((l = r.readLine()) != null) {
                final String c = l;
                ui.post(() -> handle(c));
            }
        } catch (Exception e) { }
    }

    void handle(String l) {
        try {
            String[] a = l.split(" ", 3);
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            switch (a[0]) {
                case "MOVE":
                    cx = Math.max(0, Math.min(sw - 1, cx + Float.parseFloat(a[1])));
                    cy = Math.max(0, Math.min(sh - 1, cy + Float.parseFloat(a[2])));
                    cursor.invalidate();
                    break;
                case "CLICK": click(); break;
                case "SCROLL": scroll(Float.parseFloat(a[1])); break;
                case "BACK": performGlobalAction(GLOBAL_ACTION_BACK); break;
                case "HOME": performGlobalAction(GLOBAL_ACTION_HOME); break;
                case "VOLUP": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
                case "VOLDN": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
                case "MUTE": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); break;
                case "TYPE":
                    if (RemoteIME.inst != null) RemoteIME.inst.apply(Integer.parseInt(a[1]), URLDecoder.decode(a.length > 2 ? a[2] : "", "UTF-8"));
                    break;
                case "ENTER":
                    if (RemoteIME.inst != null) RemoteIME.inst.enter();
                    break;
            }
        } catch (Exception e) { }
    }

    AccessibilityNodeInfo hit(AccessibilityNodeInfo n) {
        if (n == null) return null;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.contains((int) cx, (int) cy)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo h = hit(n.getChild(i));
            if (h != null) return h;
        }
        return n;
    }

    void click() {
        if (Build.VERSION.SDK_INT >= 24) {
            Path p = new Path();
            p.moveTo(cx, cy);
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
        } else {
            AccessibilityNodeInfo n = hit(getRootInActiveWindow());
            while (n != null && !n.isClickable()) n = n.getParent();
            if (n != null) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
    }

    void scroll(float dy) {
        if (Build.VERSION.SDK_INT >= 24) {
            Path p = new Path();
            p.moveTo(cx, cy);
            p.lineTo(cx, Math.max(10, Math.min(sh - 10, cy + Math.signum(dy) * 350)));
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null);
        } else {
            AccessibilityNodeInfo n = hit(getRootInActiveWindow());
            while (n != null && !n.isScrollable()) n = n.getParent();
            if (n != null) n.performAction(dy < 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        run = false;
        inst = null;
        try { if (server != null) server.close(); } catch (Exception e) { }
        try { if (cursor != null) wm.removeView(cursor); } catch (Exception e) { }
        super.onDestroy();
    }
}
