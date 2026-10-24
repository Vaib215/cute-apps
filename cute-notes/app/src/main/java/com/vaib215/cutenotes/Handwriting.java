package com.vaib215.cutenotes;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;
import com.google.mlkit.vision.digitalink.common.RecognitionCandidate;
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext;
import com.google.mlkit.vision.digitalink.recognition.WritingArea;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Google's on-device handwriting recognition (ML Kit Digital Ink). The English model, about
 * 20 MB, is downloaded once; after that recognition works offline.
 */
final class Handwriting {
    enum State { CHECKING, DOWNLOADING, READY, FAILED }

    private static Handwriting instance;

    static Handwriting get() {
        if (instance == null) instance = new Handwriting();
        return instance;
    }

    private final DigitalInkRecognitionModel model;
    private DigitalInkRecognizer recognizer;
    private State state = State.CHECKING;
    private Runnable listener;
    private boolean started;

    private Handwriting() {
        DigitalInkRecognitionModelIdentifier id;
        try {
            id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US");
        } catch (MlKitException e) {
            throw new IllegalStateException(e);
        }
        model = DigitalInkRecognitionModel.builder(id).build();
    }

    State state() { return state; }

    /** Called (on the main thread) whenever the state changes. */
    void listen(Runnable r) { listener = r; }

    /** Makes sure the model is here, downloading it if not. Safe to call often. */
    void start() {
        if (started && state != State.FAILED) return;
        started = true;
        set(State.CHECKING);
        RemoteModelManager.getInstance().isModelDownloaded(model)
                .addOnSuccessListener(have -> {
                    if (have) ready();
                    else download();
                })
                .addOnFailureListener(e -> download());
    }

    private void download() {
        set(State.DOWNLOADING);
        RemoteModelManager.getInstance().download(model, new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> ready())
                .addOnFailureListener(e -> set(State.FAILED));
    }

    private void ready() {
        if (recognizer == null) {
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build());
        }
        set(State.READY);
    }

    private void set(State s) {
        state = s;
        if (listener != null) listener.run();
    }

    /**
     * Reads the ink; `done` gets the guesses, best first (empty if nothing was read).
     * `before` is the text just before the cursor, which helps with ambiguous letters.
     */
    void recognize(Ink ink, String before, float width, float height, Consumer<List<String>> done) {
        if (recognizer == null) {
            done.accept(new ArrayList<>());
            return;
        }
        RecognitionContext context = RecognitionContext.builder()
                .setPreContext(before)
                .setWritingArea(new WritingArea(width, height))
                .build();
        recognizer.recognize(ink, context)
                .addOnSuccessListener(result -> {
                    List<String> out = new ArrayList<>();
                    for (RecognitionCandidate c : result.getCandidates()) {
                        String t = c.getText().trim();
                        if (!t.isEmpty() && !out.contains(t)) out.add(t);
                    }
                    done.accept(out);
                })
                .addOnFailureListener(e -> done.accept(new ArrayList<>()));
    }
}
