package com.vos.ipc;

/**
 * The session's interface into the actuator (§3.1), served by ActuatorService behind the
 * com.vos.permission.SESSION signature permission and BinderGuard (§5.3).
 */
interface IVosActuator {
    /**
     * {@link com.vos.ipc.Ipc#PROTOCOL_VERSION} of this build. PeerLink calls it on every new binder
     * ({@link com.vos.ipc.Ipc#actuatorHandshake()}) and drops a peer whose version differs.
     */
    int protocolVersion();

    /** The session's voice backend is ready for this turn: the AudioPlane moves CAPTURE → CONVERSE. */
    oneway void turnReady(String turnId);

    /** The session ends the turn (idle, vendor error, the model's end); the actuator restores audio. */
    oneway void endTurn(String turnId, String reason);

    /**
     * Runs a fast tool for the open turn {@code turnId} (§3.2) and returns its result as JSON with
     * {@code "status"}: VERIFIED, UNVERIFIED or FAILED, from the actuator's oracle (P3). Refused
     * (FAILED) unless {@code turnId} is the turn the actuator opened and is still conversing.
     */
    String callTool(String turnId, String name, String argsJson);

    /**
     * A model started producing output for the open turn {@code turnId} (a vendor response, a
     * ui_step, an escalation): the actuator shows the generating indicator until every started call
     * has ended or the turn ends (Shell Contract row 13). {@code callId} pairs it with its end; the
     * events carry no model output. Ignored for any turn but the one the actuator has open.
     */
    oneway void modelCallStarted(String turnId, String callId);

    /** The model call {@code callId} of turn {@code turnId} no longer produces output (done, cancelled or failed). */
    oneway void modelCallEnded(String turnId, String callId);

    /**
     * The reply to a typed turn the actuator opened (decision 21). The actuator shows it as model text
     * (data, never a trusted card) only for a typed turn it has open; anything else is refused and
     * audited, so the session cannot post into the owner's thread on its own.
     */
    oneway void typedReply(String turnId, String text);
}
