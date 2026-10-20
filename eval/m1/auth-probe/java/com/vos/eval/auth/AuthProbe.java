package com.vos.eval.auth;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.IVosActuator;
import com.vos.ipc.IVosSession;
import com.vos.ipc.Ipc;
import com.vos.ipc.PeerLink;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * M1-AUTH-01 probe. Runs inside the process (and UID) of the app it instruments: the session or
 * actuator debug build (androidTest APK) or a fixture (eval/fixtures/auth). It binds one VOS
 * service, makes one real call, and reports what happened. Driven by eval/m1/auth-01.sh:
 *
 *   am instrument -w -r -e target actuator|session -e expect ok|unauthorized|bind_refused|died
 *       [-e via link|raw|aidl] [-e repeat N] <component>
 *
 * via=link (default): PeerLink with the real protocol-version handshake ({@link Ipc}); the call is
 * the handshake's protocolVersion(), so a refusal arrives as a dropped link.
 * via=raw: protocolVersion() as a raw transact(), so the guard in onTransact runs even when the
 * service lives in this same process.
 * via=aidl: protocolVersion() through Stub.asInterface(); in this same process that is a direct
 * Java call on the Stub, without onTransact, so it exercises the guard inside the method.
 *
 * Result keys: outcome, expect, pass, uid, package, detail. With repeat=N (ok cases) the probe
 * makes N more raw calls and puts their p50/p95 latency in detail. With expect=died the probe
 * reports status "linked" once the binder is linked, then waits up to 60 s for the peer to die.
 */
public final class AuthProbe extends Instrumentation {
    private Bundle args;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments != null ? arguments : new Bundle();
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Context ctx = getTargetContext();
        if ("mic".equals(args.getString("probe"))) { // M1-AUD-02 (MicProbe)
            Bundle r = com.vos.eval.mic.MicProbe.run(ctx);
            finish("true".equals(r.getString("pass")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, r);
            return;
        }
        String target = args.getString("target", "actuator");
        String expect = args.getString("expect", "ok");
        String via = args.getString("via", "link");
        int repeat = Integer.parseInt(args.getString("repeat", "0"));
        boolean toSession = "session".equals(target);
        String descriptor = toSession ? IVosSession.DESCRIPTOR : IVosActuator.DESCRIPTOR;
        PeerLink.Handshake handshake = !"link".equals(via) ? b -> true
                : toSession ? Ipc.sessionHandshake() : Ipc.actuatorHandshake();
        BlockingQueue<IBinder> up = new LinkedBlockingQueue<>();
        BlockingQueue<String> down = new LinkedBlockingQueue<>();
        PeerLink link = new PeerLink(ctx, toSession ? Ipc.sessionIntent() : Ipc.actuatorIntent(), handshake,
                new PeerLink.Listener() {
                    @Override
                    public void onPeerUp(IBinder binder) {
                        up.add(binder);
                    }

                    @Override
                    public void onPeerDown(String why) {
                        down.add(why);
                    }
                });
        String outcome;
        String detail = "";
        try {
            if (!link.bind()) {
                outcome = "bind_false";
            } else {
                IBinder b = waitForUp(up, down);
                if (b == null) {
                    String why = down.poll();
                    outcome = why == null ? "connect_timeout"
                            : why.endsWith(BinderGuard.UNAUTHORIZED) ? "unauthorized" : "down:" + why;
                } else {
                    outcome = "link".equals(via) ? "ok:" + Ipc.PROTOCOL_VERSION // the handshake compared it
                            : "aidl".equals(via) ? callAidl(b, toSession) : call(b, descriptor);
                    if (repeat > 0 && outcome.startsWith("ok:")) {
                        detail = timeCalls(b, descriptor, repeat);
                    }
                    if ("died".equals(expect) && outcome.startsWith("ok:")) {
                        Bundle s = new Bundle();
                        s.putString("state", "linked");
                        sendStatus(1, s);
                        String why = down.poll(60, TimeUnit.SECONDS);
                        outcome = why != null ? "died" : "alive";
                        detail = why != null ? why : "";
                    }
                }
            }
        } catch (SecurityException e) {
            outcome = "bind_refused";
            detail = String.valueOf(e.getMessage());
        } catch (InterruptedException e) {
            outcome = "interrupted";
        } finally {
            try {
                link.unbind();
            } catch (RuntimeException ignored) {
                // never bound
            }
        }
        boolean pass = "ok".equals(expect) ? outcome.equals("ok:" + Ipc.PROTOCOL_VERSION) : outcome.equals(expect);
        Bundle r = new Bundle();
        r.putString("outcome", outcome);
        r.putString("expect", expect);
        r.putString("pass", String.valueOf(pass));
        r.putString("uid", String.valueOf(Process.myUid()));
        r.putString("package", ctx.getPackageName());
        r.putString("detail", detail);
        finish(pass ? Activity.RESULT_OK : Activity.RESULT_CANCELED, r);
    }

    /** The first up within 10 s, or null once the link is reported down or the time runs out. */
    private static IBinder waitForUp(BlockingQueue<IBinder> up, BlockingQueue<String> down)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            IBinder b = up.poll(50, TimeUnit.MILLISECONDS);
            if (b != null) {
                return b;
            }
            if (!down.isEmpty()) {
                return null;
            }
        }
        return null;
    }

    /** protocolVersion() through the AIDL interface: a Proxy for a remote service, the Stub itself locally. */
    private static String callAidl(IBinder b, boolean toSession) {
        try {
            int v = toSession ? IVosSession.Stub.asInterface(b).protocolVersion()
                    : IVosActuator.Stub.asInterface(b).protocolVersion();
            return "ok:" + v;
        } catch (SecurityException e) {
            return BinderGuard.UNAUTHORIZED.equals(e.getMessage()) ? "unauthorized" : "security:" + e.getMessage();
        } catch (RemoteException e) {
            return "remote:" + e;
        }
    }

    /** p50/p95 of {@code n} raw protocolVersion() round trips, guard included, in microseconds. */
    private static String timeCalls(IBinder b, String descriptor, int n) {
        long[] us = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            if (!call(b, descriptor).startsWith("ok:")) {
                return "timing aborted at call " + i;
            }
            us[i] = (System.nanoTime() - t0) / 1000;
        }
        Arrays.sort(us);
        return "calls=" + n + " p50_us=" + us[n / 2] + " p95_us=" + us[(int) Math.ceil(n * 0.95) - 1];
    }

    /**
     * protocolVersion() as a raw transaction, so the guard runs even when the service lives in this
     * same process (a local Stub would otherwise be called directly, without onTransact).
     */
    private static String call(IBinder b, String descriptor) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            b.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
            reply.readException();
            return "ok:" + reply.readInt();
        } catch (SecurityException e) {
            return BinderGuard.UNAUTHORIZED.equals(e.getMessage()) ? "unauthorized" : "security:" + e.getMessage();
        } catch (RemoteException e) {
            return "remote:" + e;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
