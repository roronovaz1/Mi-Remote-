package com.mi.remote;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

public class RemoteIME extends InputMethodService {
    static volatile RemoteIME inst;

    @Override public void onCreate() { super.onCreate(); inst = this; }
    @Override public void onDestroy() { inst = null; super.onDestroy(); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }

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
