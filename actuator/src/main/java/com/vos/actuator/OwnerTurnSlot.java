package com.vos.actuator;

/**
 * The one owner-turn slot (decision 21, Shell Contract row 13). A voice summon and a typed turn
 * must not hold it at once: both write the shared conversation, and the voice turn's
 * {@code turnOpened} would replace the typed turn on the indicator, so the indicator goes idle
 * when the voice turn ends while the typed turn is still running.
 *
 * <p>Not thread-safe by itself. {@link TypedChat} is the lock: it mutates the slot only while
 * synchronized on itself, and a summon takes the voice claim through {@link TypedChat#claimVoice()}.
 */
final class OwnerTurnSlot {
    enum Holder { NONE, VOICE, TYPED }

    /** What a voice summon got. {@link #BLOCKED} means it must not start the microphone. */
    enum Claim { NEW, HELD, BLOCKED }

    /** False for {@link Claim#BLOCKED}: the summon must not start the microphone service. */
    static boolean opensMicrophone(Claim claim) {
        return claim != Claim.BLOCKED;
    }

    /** What a press of "Stop VOS" (the QS tile, the assist gesture) does. */
    enum Press { STOP_VOICE, STOP_TYPED, SUMMON }

    /**
     * The press stops whichever owner turn is open, voice or typed (P5: the owner can always stop
     * it), and summons only when neither is. A press during a typed turn never starts the microphone.
     */
    static Press press(boolean voiceTurnOpen, boolean typedTurnOpen) {
        if (voiceTurnOpen) {
            return Press.STOP_VOICE;
        }
        return typedTurnOpen ? Press.STOP_TYPED : Press.SUMMON;
    }

    private Holder holder = Holder.NONE;

    Holder holder() {
        return holder;
    }

    /** False when a voice turn or another typed turn already holds the slot. */
    boolean claimTyped() {
        if (holder != Holder.NONE) {
            return false;
        }
        holder = Holder.TYPED;
        return true;
    }

    /**
     * A typed turn blocks the claim. A voice turn already holding the slot stays held, so a second
     * summon during a voice turn does not take or drop it.
     */
    Claim claimVoice() {
        if (holder == Holder.TYPED) {
            return Claim.BLOCKED;
        }
        if (holder == Holder.VOICE) {
            return Claim.HELD;
        }
        holder = Holder.VOICE;
        return Claim.NEW;
    }

    void release(Holder who) {
        if (holder == who) {
            holder = Holder.NONE;
        }
    }
}
