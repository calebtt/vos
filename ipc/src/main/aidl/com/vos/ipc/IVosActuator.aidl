package com.vos.ipc;

/**
 * The session's interface into the actuator (§3.1), served by ActuatorService behind the
 * com.vos.permission.SESSION signature permission and BinderGuard (§5.3). Turns, tools and pipes
 * arrive with the M1 planes.
 */
interface IVosActuator {
    /**
     * {@link com.vos.ipc.Ipc#PROTOCOL_VERSION} of this build. PeerLink calls it on every new binder
     * ({@link com.vos.ipc.Ipc#actuatorHandshake()}) and drops a peer whose version differs.
     */
    int protocolVersion();
}
