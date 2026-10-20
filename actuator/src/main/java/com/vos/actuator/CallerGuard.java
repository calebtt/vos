package com.vos.actuator;

/**
 * Step 2 of the Binder guard (§5.3): the calling UID must map to exactly the expected package.
 * The certificate check and the grant table arrive with M1-AUTH-01.
 */
public final class CallerGuard {
    public static final String SESSION_PACKAGE = "com.vos.session";

    private CallerGuard() {
    }

    /** {@code packagesForUid} is what {@code PackageManager.getPackagesForUid} returned. */
    public static boolean isExactly(String[] packagesForUid, String expectedPackage) {
        return packagesForUid != null
                && packagesForUid.length == 1
                && expectedPackage != null
                && expectedPackage.equals(packagesForUid[0]);
    }
}
