package com.suvo.agent.voice;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.util.CancellationToken;

/** A text-to-speech backend. Implementations are blocking; call them off the main thread. */
public interface TtsEngine {

    String id();

    /** True for engines running on this device (no network). */
    boolean isLocal();

    /** Null when ready to synthesize, otherwise a user-facing reason. */
    String notReadyReason();

    Pcm synthesize(String text, CancellationToken cancel) throws Exception;

    /** Releases resources (models, connections). */
    default void close() {
    }
}
