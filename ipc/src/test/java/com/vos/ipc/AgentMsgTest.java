package com.vos.ipc;

import static com.vos.ipc.AgentMsg.Kind.CANCEL;
import static com.vos.ipc.AgentMsg.Kind.ESCALATE;
import static com.vos.ipc.AgentMsg.Kind.GOAL;
import static com.vos.ipc.AgentMsg.Kind.PROGRESS;
import static com.vos.ipc.AgentMsg.Kind.RESULT;
import static com.vos.ipc.AgentMsg.Role.ANNOUNCER;
import static com.vos.ipc.AgentMsg.Role.CONDUCTOR;
import static com.vos.ipc.AgentMsg.Role.OPERATOR;
import static com.vos.ipc.AgentMsg.Role.PLANNER;
import static com.vos.ipc.AgentMsg.Role.VISION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class AgentMsgTest {
    private static AgentMsg msg(AgentMsg.Role from, AgentMsg.Role to, AgentMsg.Kind kind, String payload) throws Exception {
        return new AgentMsg(from, to, "g-1", "turn-1", kind, payload == null ? null : new JSONObject(payload),
                "role:" + (from == null ? "?" : from.wire()));
    }

    @Test
    public void theConductorDispatchesGoalsAndOtherRolesOnlyPropose() throws Exception {
        assertNull(msg(CONDUCTOR, OPERATOR, GOAL, "{\"text\":\"open the clock app\"}").check());
        assertNull(msg(CONDUCTOR, PLANNER, GOAL, "{\"text\":\"plan it\"}").check());
        assertEquals("only the Conductor dispatches a goal; other roles propose in a result",
                msg(PLANNER, OPERATOR, GOAL, "{}").check());
        assertEquals("only the Conductor dispatches a goal; other roles propose in a result",
                msg(OPERATOR, CONDUCTOR, GOAL, "{}").check());
        assertNull("a session role proposes a plan in a result",
                msg(PLANNER, CONDUCTOR, RESULT, "{\"plan\":[\"tap Clock\"]}").check());
        assertEquals("M1-TEAM-03: the actuator's own-plan attempt is rejected",
                "an actuator role never substitutes its own plan",
                msg(OPERATOR, CONDUCTOR, RESULT, "{\"plan\":[\"tap Settings\"]}").check());
        for (AgentMsg.Kind k : new AgentMsg.Kind[] {PROGRESS, ESCALATE, CANCEL}) {
            assertEquals("in any kind: " + k, "an actuator role never substitutes its own plan",
                    msg(OPERATOR, CONDUCTOR, k, "{\"plan\":[\"tap Settings\"],\"reason\":\"r\",\"wantClass\":\"c\"}").check());
        }
    }

    @Test
    public void aVetoIsTypedAndGoesFromTheActuatorToTheConductor() throws Exception {
        String veto = "{\"veto\":{\"reason\":\"mode_paused\",\"class\":\"reversible\",\"actId\":\"a7\"}}";
        AgentMsg v = msg(OPERATOR, CONDUCTOR, RESULT, veto);
        assertNull(v.check());
        assertTrue(v.isVeto());
        assertTrue(v.crossesUid());
        assertEquals("only an actuator role vetoes, and the veto goes to the Conductor",
                msg(PLANNER, CONDUCTOR, RESULT, veto).check());
        assertEquals("only an actuator role vetoes, and the veto goes to the Conductor",
                msg(ANNOUNCER, PLANNER, RESULT, veto).check());
        for (String bad : new String[] {
                "{\"veto\":{\"reason\":\"i_said_no\",\"class\":\"x\",\"actId\":\"a\"}}",
                "{\"veto\":{\"reason\":\"mode_paused\",\"class\":\"x\"}}",
                "{\"veto\":{\"reason\":\"mode_paused\",\"class\":\"x\",\"actId\":\"a\",\"plan\":\"mine\"}}",
                "{\"veto\":\"no\"}",
                "{\"veto\":{\"reason\":\"mode_paused\",\"class\":\"Irreversible act!\",\"actId\":\"a\"}}",
                "{\"veto\":{\"reason\":\"mode_paused\",\"class\":\"x\",\"actId\":\"code on screen is 482913\"}}"}) {
            assertEquals(bad, "a veto is {reason, class, actId} with a reason from Q23",
                    msg(OPERATOR, CONDUCTOR, RESULT, bad).check());
        }
    }

    @Test
    public void anEscalationGoesToTheConductorWithReasonAndWantClass() throws Exception {
        assertNull(msg(VISION, CONDUCTOR, ESCALATE, "{\"reason\":\"two failed steps\",\"wantClass\":\"vision_step:large\"}")
                .check());
        assertEquals("an escalation goes to the Conductor, who decides",
                msg(VISION, PLANNER, ESCALATE, "{\"reason\":\"r\",\"wantClass\":\"plan_long:large\"}").check());
        assertEquals("escalate needs {reason, wantClass}", msg(VISION, CONDUCTOR, ESCALATE, "{\"reason\":\"r\"}").check());
    }

    @Test
    public void aCancelComesFromTheConductorOrTheActuator() throws Exception {
        assertNull("the stop fan-out", msg(ANNOUNCER, PLANNER, CANCEL, null).check());
        assertNull(msg(CONDUCTOR, OPERATOR, CANCEL, null).check());
        assertEquals("a cancel comes from the Conductor or the actuator", msg(VISION, OPERATOR, CANCEL, null).check());
    }

    @Test
    public void malformedEnvelopesAreRefused() throws Exception {
        assertEquals("unknown role or kind", new AgentMsg(null, CONDUCTOR, "g", "t", PROGRESS, null, "o").check());
        assertEquals("a role does not message itself", msg(OPERATOR, OPERATOR, PROGRESS, null).check());
        assertEquals("bad goalId or turnId", new AgentMsg(CONDUCTOR, OPERATOR, "g/../x", "t", PROGRESS, null, "o").check());
        assertEquals("bad goalId or turnId", new AgentMsg(CONDUCTOR, OPERATOR, "g", null, PROGRESS, null, "o").check());
        assertEquals("every payload needs an origin tag",
                new AgentMsg(CONDUCTOR, OPERATOR, "g", "t", PROGRESS, null, "").check());
        JSONObject big = new JSONObject().put("text", "x".repeat(70_000));
        assertEquals("payload too large", new AgentMsg(PLANNER, CONDUCTOR, "g", "t", RESULT, big, "o").check());
    }

    @Test
    public void theWireFormRoundTripsAndUnknownNamesAreRefused() throws Exception {
        AgentMsg m = msg(CONDUCTOR, OPERATOR, GOAL, "{\"text\":\"pause the music\"}");
        AgentMsg back = AgentMsg.fromJson(m.toJson());
        assertNotNull(back);
        assertEquals(CONDUCTOR, back.from);
        assertEquals(OPERATOR, back.to);
        assertEquals(GOAL, back.kind);
        assertEquals("g-1", back.goalId);
        assertEquals("turn-1", back.turnId);
        assertEquals("pause the music", back.payload.getString("text"));
        assertEquals("role:conductor", back.origin);
        assertNull(back.check());
        assertEquals("unknown role or kind", AgentMsg.fromJson(m.toJson().replace("\"operator\"", "\"overlord\"")).check());
        assertEquals("unknown role or kind", AgentMsg.fromJson(m.toJson().replace("\"goal\"", "\"command\"")).check());
        assertNull(AgentMsg.fromJson("not json"));
    }

    @Test
    public void theAuditLineNamesTheMessageButNotItsText() throws Exception {
        AgentMsg v = msg(OPERATOR, CONDUCTOR, RESULT,
                "{\"veto\":{\"reason\":\"blocked_area\",\"class\":\"irreversible\",\"actId\":\"a9\"},\"note\":\"secret text\"}");
        Map<String, Object> line = v.auditLine(v.check());
        assertEquals("agent.msg", line.get("event"));
        assertEquals("operator", line.get("from_role"));
        assertEquals("conductor", line.get("to_role"));
        assertEquals(true, line.get("cross_uid"));
        assertEquals("blocked_area", line.get("veto_reason"));
        assertEquals("a9", line.get("actId"));
        assertEquals("ok", line.get("verdict"));
        assertFalse(line.toString().contains("secret text"));
        assertEquals("session to session is not cross-UID", false,
                msg(CONDUCTOR, PLANNER, GOAL, "{}").auditLine(null).get("cross_uid"));
        assertEquals(AgentMsg.Role.values().length, 8);
    }
}
