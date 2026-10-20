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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * M1-AUTH-01 probe. Runs inside the process (and UID) of the app it instruments: the session or
 * actuator debug build (androidTest APK) or a fixture (eval/fixtures/auth). It binds one VOS
 * service, makes one real transaction, and reports what happened. Driven by eval/m1/auth-01.sh:
 *
 *   am instrument -w -r -e target actuator|session -e expect ok|unauthorized|bind_refused|died <component>
 *
 * Result keys: outcome, expect, pass, uid, package, detail. With expect=died the probe reports
 * status "linked" once the binder is linked, then waits up to 60 s for the peer to die.
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
        String target = args.getString("target", "actuator");
        String expect = args.getString("expect", "ok");
        BlockingQueue<IBinder> up = new LinkedBlockingQueue<>();
        BlockingQueue<String> down = new LinkedBlockingQueue<>();
        PeerLink link = new PeerLink(ctx, "session".equals(target) ? Ipc.sessionIntent() : Ipc.actuatorIntent(),
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
                IBinder b = up.poll(10, TimeUnit.SECONDS);
                if (b == null) {
                    outcome = "connect_timeout";
                } else {
                    outcome = call(b, "session".equals(target) ? IVosSession.DESCRIPTOR : IVosActuator.DESCRIPTOR);
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
