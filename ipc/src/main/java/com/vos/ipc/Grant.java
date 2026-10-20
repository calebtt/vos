package com.vos.ipc;

/** What an authenticated caller may do (§5.3 step 4). New grants need a plan change. */
public enum Grant {
    /** Session → actuator: the core surface (turns, tools, pipes as they land in M1). */
    SESSION_CORE,
    /** Actuator → session: turn delivery and results. */
    ACTUATOR_CORE,
}
