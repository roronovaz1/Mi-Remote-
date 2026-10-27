package com.mi.remote;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.view.inputmethod.InputConnection;

public class RemoteIME extends InputMethodService {
    static volatile RemoteIME inst;

    @Override public void onCreate() { super.onCreate(); inst = this; }
    @Override public void onDestroy() { inst = null; super.onDestroy(); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() { return true; }

    @Override public View onCreateInputView() {
        TextView b = new TextView(this);
        b.setText("MI REMOTE KEYBOARD  -  typing from phone.  Press OK here to use the TV keyboard.");
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xFF000000);
        b.setTextSize(14);
        b.setPadding(32, 24, 32, 24);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> {
            if (!switchToPreviousInputMethod())
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
        });
        return b;
    }

    void apply(int del, String txt) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (del > 0) ic.deleteSurroundingText(del, 0);
        if (txt.length() > 0) ic.commitText(txt, 1);
    }

    void enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }
}
