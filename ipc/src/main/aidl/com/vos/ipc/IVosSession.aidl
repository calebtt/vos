package com.vos.ipc;

/**
 * The actuator's interface into the session (§3.1), served by SessionService behind the
 * com.vos.permission.ACTUATOR signature permission and BinderGuard (§5.3). Turn delivery and
 * results arrive with the M1 planes.
 */
interface IVosSession {
    /** {@link com.vos.ipc.Ipc#PROTOCOL_VERSION} of this build; a peer with another version is refused. */
    int protocolVersion();
}
