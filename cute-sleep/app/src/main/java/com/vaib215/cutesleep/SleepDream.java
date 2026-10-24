package com.vaib215.cutesleep;

import android.service.dreams.DreamService;

/**
 * The automatic way in: Android's screen saver, which starts by itself when the phone is
 * charging and the screen would go off. Swipe to change the face; tap to wake the phone.
 */
public class SleepDream extends DreamService implements Face.Listener {
    private Bedside bedside;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true); // so swipes change the face instead of waking the phone
        setFullscreen(true);
        Stand stand = new Stand(this);
        stand.face.listener = this;
        setContentView(stand);
        bedside = new Bedside(this, getWindow(), stand.face, stand.panel, true);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        bedside.start();
    }

    @Override
    public void onDreamingStopped() {
        bedside.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onTap() {
        finish();
    }

    @Override
    public void onFaceChanged(int face) {
        bedside.faceChanged(face);
    }
}
