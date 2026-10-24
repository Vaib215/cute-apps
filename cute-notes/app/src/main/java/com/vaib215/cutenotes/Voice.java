package com.vaib215.cutenotes;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Dictation through the phone's speech service. Keeps listening, sentence after sentence,
 * until stopped.
 */
final class Voice implements RecognitionListener {
    interface Listener {
        void onHeard(String partial);
        /** What was said, best guess first. */
        void onSaid(List<String> guesses);
        void onStopped(String problem);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean on;

    Voice(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /**
     * The speech service to use: the phone's default if it has one, otherwise Google's
     * (Speech Services by Google), otherwise any. Null if the phone has none.
     */
    private ComponentName service() {
        List<ResolveInfo> all = context.getPackageManager()
                .queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        if (all.isEmpty()) return null;
        ResolveInfo pick = all.get(0);
        for (ResolveInfo r : all) {
            if (r.serviceInfo.packageName.equals("com.google.android.tts")) pick = r;
        }
        return new ComponentName(pick.serviceInfo.packageName, pick.serviceInfo.name);
    }

    boolean isOn() { return on; }

    void start() {
        ComponentName c = service();
        if (c == null) {
            listener.onStopped("This phone has no speech service. Install Speech Services by Google.");
            return;
        }
        on = true;
        recognizer = SpeechRecognizer.createSpeechRecognizer(context, c);
        recognizer.setRecognitionListener(this);
        listen();
    }

    void stop() {
        on = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private void listen() {
        if (!on) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        recognizer.startListening(i);
    }

    private void again(long delay) {
        handler.postDelayed(this::listen, delay);
    }

    @Override
    public void onPartialResults(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty()) listener.onHeard(r.get(0));
    }

    @Override
    public void onResults(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) listener.onSaid(r);
        again(0);
    }

    @Override
    public void onError(int error) {
        if (!on) return;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_CLIENT:
                again(0); // silence or a pause: just keep listening
                return;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                again(400);
                return;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                stop();
                listener.onStopped("Voice needs the microphone permission.");
                return;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                stop();
                listener.onStopped("Voice needs the internet, or the offline English pack in Speech Services by Google.");
                return;
            default:
                stop();
                listener.onStopped("Voice stopped (error " + error + "). Tap the mic to try again.");
        }
    }

    @Override public void onReadyForSpeech(Bundle b) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float db) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int type, Bundle b) {}
}
