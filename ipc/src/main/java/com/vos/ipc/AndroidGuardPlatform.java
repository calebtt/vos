package com.vos.ipc;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** {@link BinderGuard.Platform} on PackageManager, plus the app's own cert. */
public final class AndroidGuardPlatform implements BinderGuard.Platform {
    private final PackageManager pm;

    public AndroidGuardPlatform(PackageManager pm) {
        this.pm = pm;
    }

    @Override
    public String[] packagesForUid(int uid) {
        return pm.getPackagesForUid(uid);
    }

    @Override
    public boolean hasSigningCertificate(int uid, byte[] certSha256) {
        return pm.hasSigningCertificate(uid, certSha256, PackageManager.CERT_INPUT_SHA256);
    }

    /** SHA-256 of this app's signing certificate. Exactly one signer is required. */
    public static byte[] ownCertSha256(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null || si.hasMultipleSigners()) {
                throw new IllegalStateException("expected exactly one signer");
            }
            Signature[] signers = si.getApkContentsSigners();
            if (signers == null || signers.length != 1) {
                throw new IllegalStateException("expected exactly one signer");
            }
            return MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
