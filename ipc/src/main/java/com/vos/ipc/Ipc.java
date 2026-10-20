package com.vos.ipc;

import android.content.ComponentName;
import android.content.Intent;

/** Names and the protocol version shared by the session and the actuator (§3.1, §5.3). */
public final class Ipc {
    /** Bumped on any incompatible change to IVosActuator or IVosSession. */
    public static final int PROTOCOL_VERSION = 1;

    public static final String ACTUATOR_PACKAGE = "com.vos.actuator";
    public static final String SESSION_PACKAGE = "com.vos.session";

    /** Signature permission defined by the actuator; the session holds it to bind ActuatorService. */
    public static final String PERMISSION_SESSION = "com.vos.permission.SESSION";
    /** Signature permission defined by the session; the actuator holds it to bind SessionService. */
    public static final String PERMISSION_ACTUATOR = "com.vos.permission.ACTUATOR";

    public static final ComponentName ACTUATOR_SERVICE =
            new ComponentName(ACTUATOR_PACKAGE, "com.vos.actuator.ActuatorService");
    public static final ComponentName SESSION_SERVICE =
            new ComponentName(SESSION_PACKAGE, "com.vos.session.SessionService");

    private Ipc() {
    }

    public static Intent actuatorIntent() {
        return new Intent().setComponent(ACTUATOR_SERVICE);
    }

    public static Intent sessionIntent() {
        return new Intent().setComponent(SESSION_SERVICE);
    }
}
