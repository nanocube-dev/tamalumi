package com.lou.lumi.brain

/** What the voice side of Lumi is doing – the face shows it (listening dots, thinking dots …). */
enum class BrainState {
    /** No models loaded (or not imported yet): Lumi lives, but cannot talk. */
    OFF,
    /** Models are loading (~7 s): „Lumi wacht auf“. */
    WAKING,
    /** Loaded, mic closed. */
    READY,
    /** Conversation open, waiting for speech. */
    LISTENING,
    /** Whisper is transcribing what was said. */
    HEARING,
    /** Gemma is generating. */
    THINKING,
    /** Piper is talking. */
    SPEAKING,
    ERROR,
}
