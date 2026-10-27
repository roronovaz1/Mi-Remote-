package com.mi.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final int PORT = 8765, GRAY = 0xFF8A8A8A, RED = 0xFFD71921;
    SharedPreferences sp;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    volatile boolean linked;
    Socket sock;
    OutputStream out;
    TextView status;
    EditText ipE, pinE, typer;
    String lastSent = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("mi", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.BLACK);
        String r = sp.getString("role", "");
        if (r.equals("tv")) tv(); else if (r.equals("phone")) phone(); else chooser();
    }

    @Override protected void onResume() {
        super.onResume();
        if ("tv".equals(sp.getString("role", ""))) tv();
    }

    @Override protected void onDestroy() {
        try { if (sock != null) sock.close(); } catch (Exception e) { }
        super.onDestroy();
    }

    // ---------- small UI helpers ----------
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    GradientDrawable ring(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(40));
        g.setStroke(dp(1), Color.WHITE);
        return g;
    }

    TextView txt(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE); t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    TextView btn(String s, View.OnClickListener c) {
        TextView v = txt(s, 14, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(16), dp(14), dp(16), dp(14));
        v.setBackground(ring(Color.TRANSPARENT));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, f) -> x.setBackground(ring(f ? RED : Color.TRANSPARENT)));
        v.setOnClickListener(c);
        return v;
    }

    EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(GRAY); e.setTextColor(Color.WHITE);
        e.setTypeface(Typeface.MONOSPACE); e.setTextSize(14); e.setSingleLine(true);
        e.setPadding(dp(18), dp(12), dp(18), dp(12));
        e.setBackground(ring(Color.TRANSPARENT));
        return e;
    }

    LinearLayout box(boolean vertical, int gravity) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    LinearLayout root(int gravity) {
        LinearLayout l = box(true, gravity);
        l.setBackgroundColor(Color.BLACK);
        l.setPadding(dp(20), dp(20), dp(20), dp(20));
        return l;
    }

    LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        return p;
    }

    LinearLayout.LayoutParams lw() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(4), dp(10), dp(4), 0);
        return p;
    }

    // ---------- role chooser ----------
    void chooser() {
        LinearLayout r = root(Gravity.CENTER);
        r.addView(txt("MI REMOTE", 28, Color.WHITE));
        r.addView(txt("WHICH DEVICE IS THIS?", 12, GRAY));
        r.addView(btn("PHONE", v -> { sp.edit().putString("role", "phone").apply(); phone(); }), lp());
        r.addView(btn("TV", v -> { sp.edit().putString("role", "tv").apply(); tv(); }), lp());
        setContentView(r);
    }

    // ---------- TV side ----------
    static String localIp() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a.isSiteLocalAddress() && a instanceof Inet4Address)
                        return a.getHostAddress();
        } catch (Exception e) { }
        return "0.0.0.0";
    }

    void open(String action) {
        try { startActivity(new Intent(action)); }
        catch (Exception e) { Toast.makeText(this, "Open this from TV Settings", Toast.LENGTH_LONG).show(); }
    }

    void tv() {
        String pin = sp.getString("pin", null);
        if (pin == null) {
            pin = String.valueOf(1000 + new Random().nextInt(9000));
            sp.edit().putString("pin", pin).apply();
        }
        String addr = localIp();
        LinearLayout r = box(false, Gravity.CENTER);
        r.setBackgroundColor(Color.BLACK);
        r.setPadding(dp(24), dp(24), dp(24), dp(24));

        LinearLayout left = box(true, Gravity.CENTER_HORIZONTAL);
        left.addView(txt("MI REMOTE", 26, Color.WHITE));
        ImageView qr = new ImageView(this);
        try {
            qr.setImageBitmap(new BarcodeEncoder().encodeBitmap(addr + ";" + pin, BarcodeFormat.QR_CODE, dp(200), dp(200)));
        } catch (Exception e) { }
        left.addView(qr, new LinearLayout.LayoutParams(dp(200), dp(200)));
        left.addView(txt(addr, 16, Color.WHITE));
        left.addView(txt("PIN  " + pin, 16, RED));

        LinearLayout right = box(true, Gravity.CENTER_VERTICAL);
        right.addView(txt(RemoteAccessService.inst != null ? "● SERVICE ON" : "○ SERVICE OFF", 12, RemoteAccessService.inst != null ? RED : GRAY));
        right.addView(btn("1  TURN ON SERVICE", v -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS)), lp());
        right.addView(btn("2  ENABLE KEYBOARD", v -> open(Settings.ACTION_INPUT_METHOD_SETTINGS)), lp());
        right.addView(btn("3  CHOOSE KEYBOARD", v -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker()), lp());
        right.addView(btn("CHANGE ROLE", v -> { sp.edit().remove("role").apply(); chooser(); }), lp());

        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        setContentView(r);
    }

    // ---------- phone side ----------
    boolean send(String line) {
        if (!linked) return false;
        io.execute(() -> {
            try {
                out.write((line + "\n").getBytes("UTF-8"));
                out.flush();
            } catch (Exception e) {
                linked = false;
                ui.post(() -> setStatus(false));
            }
        });
        return true;
    }

    void setStatus(boolean on) {
        status.setText(on ? "● LINKED" : "○ NOT LINKED");
        status.setTextColor(on ? RED : GRAY);
    }

    void link() {
        final String h = ipE.getText().toString().trim(), p = pinE.getText().toString().trim();
        if (h.isEmpty()) return;
        status.setText("… LINKING");
        io.execute(() -> {
            try {
                if (sock != null) sock.close();
                Socket s = new Socket();
                s.connect(new InetSocketAddress(h, PORT), 3000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(3000);
                OutputStream o = s.getOutputStream();
                o.write(("AUTH " + p + "\n").getBytes("UTF-8"));
                o.flush();
                String resp = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
                if (!"OK".equals(resp)) throw new IOException("auth");
                s.setSoTimeout(0);
                sock = s; out = o; linked = true;
                sp.edit().putString("lip", h).putString("lpin", p).apply();
                ui.post(() -> setStatus(true));
            } catch (Exception e) {
                linked = false;
                ui.post(() -> { setStatus(false); Toast.makeText(this, "Could not link. Check IP and PIN.", Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        IntentResult r = IntentIntegrator.parseActivityResult(rq, rs, d);
        if (r != null && r.getContents() != null && ipE != null) {
            String[] a = r.getContents().split(";");
            if (a.length == 2) { ipE.setText(a[0]); pinE.setText(a[1]); link(); }
        } else super.onActivityResult(rq, rs, d);
    }

    void phone() {
        LinearLayout r = root(Gravity.TOP);

        LinearLayout top = box(false, Gravity.CENTER_VERTICAL);
        status = txt("○ NOT LINKED", 12, GRAY);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(btn("SCAN QR", v -> new IntentIntegrator(this).setBeepEnabled(false).setOrientationLocked(false).initiateScan()));
        r.addView(top, lp());

        LinearLayout pr = box(false, Gravity.CENTER_VERTICAL);
        ipE = edit("TV IP");
        ipE.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pinE = edit("PIN");
        pinE.setInputType(InputType.TYPE_CLASS_NUMBER);
        pr.addView(ipE, new LinearLayout.LayoutParams(0, -2, 2f));
        pr.addView(pinE, new LinearLayout.LayoutParams(0, -2, 1f));
        pr.addView(btn("LINK", v -> link()));
        r.addView(pr, lp());

        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 0, 1f);
        pp.topMargin = dp(12);
        r.addView(new Pad(this), pp);

        LinearLayout nav = box(false, Gravity.CENTER);
        nav.addView(btn("BACK", v -> send("BACK")), lw());
        nav.addView(btn("HOME", v -> send("HOME")), lw());
        r.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout vol = box(false, Gravity.CENTER);
        vol.addView(btn("VOL −", v -> send("VOLDN")), lw());
        vol.addView(btn("MUTE", v -> send("MUTE")), lw());
        vol.addView(btn("VOL +", v -> send("VOLUP")), lw());
        r.addView(vol, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tr = box(false, Gravity.CENTER_VERTICAL);
        typer = edit("TYPE HERE");
        typer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        typer.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                String now = e.toString();
                int p = 0, m = Math.min(now.length(), lastSent.length());
                while (p < m && now.charAt(p) == lastSent.charAt(p)) p++;
                int del = lastSent.length() - p;
                String add = now.substring(p);
                try {
                    if (send("TYPE " + del + " " + URLEncoder.encode(add, "UTF-8"))) lastSent = now;
                } catch (Exception ex) { }
            }
        });
        tr.addView(typer, new LinearLayout.LayoutParams(0, -2, 1f));
        tr.addView(btn("KB", v -> send("PICK")));
        tr.addView(btn("ENTER", v -> send("ENTER")));
        r.addView(tr, lp());

        setContentView(r);
        ipE.setText(sp.getString("lip", ""));
        pinE.setText(sp.getString("lpin", ""));
        if (!ipE.getText().toString().isEmpty()) link();
    }

    // ---------- touchpad ----------
    class Pad extends View {
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lx, ly, x0, y0, acc;
        long t0;
        boolean moved, two, skip;

        Pad(Context c) {
            super(c);
            dot.setColor(0xFF444444);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF0D0D0D);
            g.setCornerRadius(dp(28));
            g.setStroke(dp(1), 0xFF333333);
            setBackground(g);
        }

        @Override protected void onDraw(Canvas c) {
            int g = dp(20);
            for (int x = g; x < getWidth() - dp(8); x += g)
                for (int y = g; y < getHeight() - dp(8); y += g)
                    c.drawCircle(x, y, dp(1), dot);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            if (e.getPointerCount() > 1) { x = (e.getX(0) + e.getX(1)) / 2; y = (e.getY(0) + e.getY(1)) / 2; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lx = x0 = x; ly = y0 = y; t0 = e.getEventTime();
                    moved = two = skip = false; acc = 0;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    two = true; moved = true; lx = x; ly = y; acc = 0;
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    skip = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (skip) { lx = x; ly = y; skip = false; break; }
                    float dx = x - lx, dy = y - ly;
                    lx = x; ly = y;
                    if (Math.abs(x - x0) + Math.abs(y - y0) > dp(8)) moved = true;
                    if (two) {
                        acc += dy;
                        if (Math.abs(acc) > dp(24)) { send("SCROLL " + (int) acc); acc = 0; }
                    } else if (moved) {
                        send("MOVE " + (int) (dx * 2.2f) + " " + (int) (dy * 2.2f));
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved && e.getEventTime() - t0 < 300) send("CLICK");
                    break;
            }
            return true;
        }
    }
}
