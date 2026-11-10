package com.vos.actuator;

import java.util.List;
import java.util.Objects;

/**
 * The bundled offline speech engine behind the OfflinePlane (§3.3): it hears the owner's mic in the
 * actuator, with no network, and reports what it heard to {@link OfflineGrammar}. This is only the
 * seam. No engine is bound yet: the sherpa-onnx small-vocabulary binding (and
 * {@code createOnDeviceSpeechRecognizer} where it exists), its model files, and the on-device
 * accuracy bar of at least 95% on stop/pause/yes/no fixtures, in quiet and on a noisy floor, are
 * spike S-15 (#25) work. Nothing in the app calls this interface yet.
 *
 * <p>Lifecycle: {@link #start}, then {@link #feed} from the capture thread, then {@link #stop}
 * (which may deliver one last final result) and {@link #close}. Results go to the listener on an
 * engine thread; the listener must not block.
 */
public interface OfflineRecognizer extends AutoCloseable {
    /** The PCM {@link #feed} takes: 16 kHz, mono, signed 16-bit. */
    int SAMPLE_RATE_HZ = 16_000;

    /** One reading of an utterance. {@code confidence} is the engine's score scaled to 0..1. */
    record Hypothesis(String text, double confidence) { }

    /**
     * What the engine heard so far in one utterance: its alternatives, in any order, and whether the
     * utterance has ended ({@code isFinal}) or may still change (a partial). An empty list means the
     * engine heard nothing it could read. Null alternatives are dropped.
     */
    record Recognition(List<Hypothesis> alternatives, boolean isFinal) {
        public Recognition {
            alternatives = alternatives == null ? List.of()
                    : alternatives.stream().filter(Objects::nonNull).toList();
        }
    }

    interface Listener {
        void onRecognition(Recognition recognition);

        /** The engine failed or the model could not load. The caller treats it as no match. */
        void onError(String reason);
    }

    /** Begins an utterance stream. Throws IllegalStateException if already started. */
    void start(Listener listener);

    /** Feeds PCM ({@link #SAMPLE_RATE_HZ}, mono, 16-bit) captured by the AudioPlane. */
    void feed(short[] pcm, int offset, int length);

    /** Ends the stream; the engine may deliver one last final result before returning. */
    void stop();

    /** Frees the model and native memory. */
    @Override
    void close();
}
