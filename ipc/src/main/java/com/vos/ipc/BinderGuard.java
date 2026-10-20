package com.vos.ipc;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The guard every incoming session ↔ actuator transaction runs before any argument is read
 * (§5.3): the calling UID must map to exactly the expected package, that package must be signed
 * with this app's own certificate, and it must have a grant set. Decisions are cached per UID until
 * {@link #invalidate()} (called on package changes). Every refusal is audited, cached or not, and
 * surfaces to the caller as {@code SecurityException("UNAUTHORIZED")}.
 */
public final class BinderGuard {
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    /** Platform lookups the guard needs. A seam, so the rules are unit-tested off the device. */
    public interface Platform {
        /** {@code PackageManager.getPackagesForUid}; null when the UID has no visible package. */
        String[] packagesForUid(int uid);

        /** {@code PackageManager.hasSigningCertificate(uid, certSha256, CERT_INPUT_SHA256)}. */
        boolean hasSigningCertificate(int uid, byte[] certSha256);
    }

    /** Where refusals are written (the actuator's audit; the session's local log until it forwards). */
    public interface Audit {
        void unauthorized(String service, int uid, String[] packages, String reason);
    }

    private static final class Decision {
        final String[] packages;
        final Set<Grant> grants; // null when refused
        final String reason;

        Decision(String[] packages, Set<Grant> grants, String reason) {
            this.packages = packages;
            this.grants = grants;
            this.reason = reason;
        }
    }

    private final String service;
    private final String expectedPackage;
    private final byte[] certSha256;
    private final GrantTable grants;
    private final Platform platform;
    private final Audit audit;
    private final ConcurrentHashMap<Integer, Decision> cache = new ConcurrentHashMap<>();

    /**
     * @param service name used in audit lines, e.g. "ActuatorService"
     * @param certSha256 SHA-256 of this app's own signing certificate: release builds accept only
     *     release peers and debug builds only debug peers, because the two certs always differ (M0-02)
     */
    public BinderGuard(String service, String expectedPackage, byte[] certSha256, GrantTable grants,
            Platform platform, Audit audit) {
        if (certSha256 == null || certSha256.length != 32) {
            throw new IllegalArgumentException("certSha256 must be a SHA-256 digest");
        }
        this.service = service;
        this.expectedPackage = expectedPackage;
        this.certSha256 = certSha256.clone();
        this.grants = grants;
        this.platform = platform;
        this.audit = audit;
    }

    /** Returns the caller's grants, or audits and throws {@code SecurityException("UNAUTHORIZED")}. */
    public Set<Grant> check(int uid) {
        Decision d = cache.computeIfAbsent(uid, this::decide);
        if (d.grants == null) {
            refuse(uid, d.packages, d.reason);
        }
        return d.grants;
    }

    /** {@link #check(int)}, and the caller must also hold {@code grant}. */
    public void require(int uid, Grant grant) {
        if (!check(uid).contains(grant)) {
            refuse(uid, cache.get(uid).packages, "missing grant " + grant);
        }
    }

    /** Drops every cached decision; the next transaction from each UID is checked afresh. */
    public void invalidate() {
        cache.clear();
    }

    private Decision decide(int uid) {
        String[] packages = platform.packagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return new Decision(packages, null, "no package for uid");
        }
        if (packages.length != 1 || !expectedPackage.equals(packages[0])) {
            return new Decision(packages, null, "package mismatch");
        }
        if (!platform.hasSigningCertificate(uid, certSha256)) {
            return new Decision(packages, null, "certificate mismatch");
        }
        Set<Grant> g = grants.forPackage(packages[0]);
        if (g.isEmpty()) {
            return new Decision(packages, null, "no grants");
        }
        return new Decision(packages, g, null);
    }

    private void refuse(int uid, String[] packages, String reason) {
        audit.unauthorized(service, uid, packages == null ? new String[0] : Arrays.copyOf(packages, packages.length),
                reason);
        throw new SecurityException(UNAUTHORIZED);
    }
}
