package com.vos.ipc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class BinderGuardTest {
    private static final byte[] OWN_CERT = digest(1);
    private static final byte[] OTHER_CERT = digest(2);
    private static final int SESSION_UID = 10101;
    private static final int OTHER_UID = 10202;

    private static byte[] digest(int fill) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    /** Fake PackageManager: uid → packages and uid → cert, counting lookups. */
    private static final class FakePlatform implements BinderGuard.Platform {
        final Map<Integer, String[]> packages = new HashMap<>();
        final Map<Integer, byte[]> certs = new HashMap<>();
        int lookups;

        @Override
        public String[] packagesForUid(int uid) {
            lookups++;
            return packages.get(uid);
        }

        @Override
        public boolean hasSigningCertificate(int uid, byte[] certSha256) {
            return Arrays.equals(certs.get(uid), certSha256);
        }
    }

    private static final class Line {
        final int uid;
        final String[] packages;
        final String reason;

        Line(int uid, String[] packages, String reason) {
            this.uid = uid;
            this.packages = packages;
            this.reason = reason;
        }
    }

    private final FakePlatform platform = new FakePlatform();
    private final List<Line> audit = new ArrayList<>();
    private final BinderGuard guard = new BinderGuard("ActuatorService", Ipc.SESSION_PACKAGE, OWN_CERT,
            GrantTable.of(Ipc.SESSION_PACKAGE, Grant.SESSION_CORE), platform,
            (service, uid, packages, reason) -> audit.add(new Line(uid, packages, reason)));

    private void install(int uid, byte[] cert, String... packages) {
        platform.packages.put(uid, packages);
        platform.certs.put(uid, cert);
    }

    private void assertRefused(int uid, String reason, String... packages) {
        SecurityException e = assertThrows(SecurityException.class, () -> guard.check(uid));
        assertEquals(BinderGuard.UNAUTHORIZED, e.getMessage());
        Line last = audit.get(audit.size() - 1);
        assertEquals(uid, last.uid);
        assertEquals(reason, last.reason);
        assertArrayEquals(packages, last.packages);
    }

    @Test
    public void sessionWithOwnCertGetsItsGrants() {
        install(SESSION_UID, OWN_CERT, Ipc.SESSION_PACKAGE);
        Set<Grant> g = guard.check(SESSION_UID);
        assertEquals(Set.of(Grant.SESSION_CORE), g);
        assertTrue(audit.isEmpty());
    }

    @Test
    public void sameCertWrongPackageIsRefusedAndAudited() {
        install(OTHER_UID, OWN_CERT, "com.vos.fixture.wrongpkg");
        assertRefused(OTHER_UID, "package mismatch", "com.vos.fixture.wrongpkg");
    }

    @Test
    public void rightPackageNameWithForeignCertIsRefused() {
        // A foreign-signed APK that took the session's package name (e.g. a debug build calling a
        // release actuator, or the session not installed and a squatter in its place).
        install(SESSION_UID, OTHER_CERT, Ipc.SESSION_PACKAGE);
        assertRefused(SESSION_UID, "certificate mismatch", Ipc.SESSION_PACKAGE);
    }

    @Test
    public void sharedUidContainingTheSessionIsRefused() {
        install(OTHER_UID, OWN_CERT, Ipc.SESSION_PACKAGE, "com.example.other");
        assertRefused(OTHER_UID, "package mismatch", Ipc.SESSION_PACKAGE, "com.example.other");
    }

    @Test
    public void unknownUidIsRefused() {
        assertRefused(OTHER_UID, "no package for uid");
    }

    @Test
    public void packageWithoutGrantsIsRefused() {
        BinderGuard noGrants = new BinderGuard("ActuatorService", Ipc.SESSION_PACKAGE, OWN_CERT,
                GrantTable.of("com.example.someone.else", Grant.SESSION_CORE), platform,
                (service, uid, packages, reason) -> audit.add(new Line(uid, packages, reason)));
        install(SESSION_UID, OWN_CERT, Ipc.SESSION_PACKAGE);
        assertThrows(SecurityException.class, () -> noGrants.check(SESSION_UID));
        assertEquals("no grants", audit.get(0).reason);
    }

    @Test
    public void requireChecksTheGrant() {
        install(SESSION_UID, OWN_CERT, Ipc.SESSION_PACKAGE);
        guard.require(SESSION_UID, Grant.SESSION_CORE);
        SecurityException e = assertThrows(SecurityException.class,
                () -> guard.require(SESSION_UID, Grant.ACTUATOR_CORE));
        assertEquals(BinderGuard.UNAUTHORIZED, e.getMessage());
        assertEquals("missing grant ACTUATOR_CORE", audit.get(0).reason);
    }

    @Test
    public void everyCallAsksThePlatformAndEveryRefusalIsAudited() {
        install(SESSION_UID, OWN_CERT, Ipc.SESSION_PACKAGE);
        install(OTHER_UID, OWN_CERT, "com.vos.fixture.wrongpkg");
        guard.check(SESSION_UID);
        guard.require(SESSION_UID, Grant.SESSION_CORE);
        assertThrows(SecurityException.class, () -> guard.check(OTHER_UID));
        assertThrows(SecurityException.class, () -> guard.check(OTHER_UID));
        assertEquals(4, platform.lookups);
        assertEquals(2, audit.size());
    }

    @Test
    public void aReplacedPackageIsRefusedOnTheNextCall() {
        install(SESSION_UID, OWN_CERT, Ipc.SESSION_PACKAGE);
        guard.check(SESSION_UID);
        // The session is replaced by an APK with another signer under the same UID; no broadcast
        // has been delivered and nothing is invalidated.
        install(SESSION_UID, OTHER_CERT, Ipc.SESSION_PACKAGE);
        assertRefused(SESSION_UID, "certificate mismatch", Ipc.SESSION_PACKAGE);
    }

    @Test
    public void aRemovedPackageWhoseUidIsReusedIsRefusedOnTheNextCall() {
        install(SESSION_UID, OWN_CERT, Ipc.SESSION_PACKAGE);
        guard.require(SESSION_UID, Grant.SESSION_CORE);
        install(SESSION_UID, OWN_CERT, "com.example.reused.uid");
        assertRefused(SESSION_UID, "package mismatch", "com.example.reused.uid");
    }

    @Test
    public void aMissingGrantIsAuditedWithTheDecisionsOwnPackages() {
        install(SESSION_UID, OWN_CERT, Ipc.SESSION_PACKAGE);
        SecurityException e = assertThrows(SecurityException.class,
                () -> guard.require(SESSION_UID, Grant.ACTUATOR_CORE));
        assertEquals(BinderGuard.UNAUTHORIZED, e.getMessage());
        assertEquals(1, platform.lookups);
        assertArrayEquals(new String[] {Ipc.SESSION_PACKAGE}, audit.get(0).packages);
    }

    @Test
    public void rejectsANonDigestCert() {
        assertThrows(IllegalArgumentException.class, () -> new BinderGuard("x", Ipc.SESSION_PACKAGE, new byte[20],
                GrantTable.of(Ipc.SESSION_PACKAGE, Grant.SESSION_CORE), platform, (s, u, p, r) -> { }));
    }
}
