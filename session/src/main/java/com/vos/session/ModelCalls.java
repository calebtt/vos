package com.vos.session;

/**
 * When a model starts and stops producing output for a turn, reported to the actuator, which draws
 * the "generating" indicator from these events alone (Shell Contract row 13). The events carry no
 * model output, so nothing a model says can show or hide the indicator.
 */
interface ModelCalls {
    void started(String turnId, String callId);

    void ended(String turnId, String callId);
}
