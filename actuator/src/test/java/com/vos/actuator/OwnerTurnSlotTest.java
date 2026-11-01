package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A voice summon must not run during an open typed turn (decision 21, Shell Contract row 13):
 * both would write the shared conversation, and the voice turn would take the indicator so it
 * goes idle while the typed turn is still in flight.
 */
public class OwnerTurnSlotTest {
    @Test
    public void aVoiceSummonDoesNotStartTheMicDuringATypedTurn() {
        OwnerTurnSlot slot = new OwnerTurnSlot();
        assertTrue(slot.claimTyped());
        OwnerTurnSlot.Claim claim = slot.claimVoice();
        assertEquals(OwnerTurnSlot.Claim.BLOCKED, claim);
        assertFalse("summon returns before startForeground", OwnerTurnSlot.opensMicrophone(claim));
        assertEquals("the typed turn keeps the slot, and the indicator", OwnerTurnSlot.Holder.TYPED, slot.holder());
    }

    @Test
    public void aTypedTurnWaitsWhileAVoiceSummonHoldsTheSlot() {
        OwnerTurnSlot slot = new OwnerTurnSlot();
        assertEquals(OwnerTurnSlot.Claim.NEW, slot.claimVoice());
        assertFalse(slot.claimTyped());
        assertEquals(OwnerTurnSlot.Holder.VOICE, slot.holder());
        // The mic is already the voice turn's; a second summon must not take or drop the slot.
        assertEquals(OwnerTurnSlot.Claim.HELD, slot.claimVoice());
        assertTrue(OwnerTurnSlot.opensMicrophone(OwnerTurnSlot.Claim.HELD));
        slot.release(OwnerTurnSlot.Holder.VOICE);
        assertTrue(slot.claimTyped());
    }

    @Test
    public void releasingATypedTurnLetsTheNextSummonStartTheMic() {
        OwnerTurnSlot slot = new OwnerTurnSlot();
        slot.claimTyped();
        slot.release(OwnerTurnSlot.Holder.TYPED);
        OwnerTurnSlot.Claim claim = slot.claimVoice();
        assertEquals(OwnerTurnSlot.Claim.NEW, claim);
        assertTrue(OwnerTurnSlot.opensMicrophone(claim));
    }

    @Test
    public void stopVosStopsAnOpenTypedTurnInsteadOfSummoning() {
        // P5 (#300): the tile or the assist gesture during a typed turn stops it; it is not a refused summon.
        assertEquals(OwnerTurnSlot.Press.STOP_TYPED, OwnerTurnSlot.press(false, true));
        assertEquals(OwnerTurnSlot.Press.STOP_VOICE, OwnerTurnSlot.press(true, false));
        assertEquals(OwnerTurnSlot.Press.SUMMON, OwnerTurnSlot.press(false, false));
        // Never both at once (the slot); if so, the voice turn holding the microphone is stopped first.
        assertEquals(OwnerTurnSlot.Press.STOP_VOICE, OwnerTurnSlot.press(true, true));
    }

    @Test
    public void releasingTheOtherHolderDoesNothing() {
        OwnerTurnSlot slot = new OwnerTurnSlot();
        slot.claimTyped();
        slot.release(OwnerTurnSlot.Holder.VOICE);
        assertEquals(OwnerTurnSlot.Holder.TYPED, slot.holder());
        assertFalse(slot.claimTyped());
    }
}
