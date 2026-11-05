package com.vos.ipc;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The audit chain's host tool (M2-AUDIT-01, #154): {@link AuditChain#verify} on audit JSONL files,
 * on a plain JVM. It lives with the JVM tests, so it never ships in an APK; run it through
 * {@code ./gradlew -q :ipc:auditVerify --args="..."} (eval/m2/audit-01.sh does).
 *
 * <pre>
 *   verify [--head HEX] FILE...   each FILE: "FILE: OK records=R legacy=L", or
 *                                 "FILE: FAIL line N: reason (B breaks)" and every break indented;
 *                                 exit 0 if every file chains, 1 if any does not
 *   generate FILE N               writes an N-record chain through AuditChain.Writer (as JsonlAudit
 *                                 does) to a new FILE; prints "head HEX"
 * </pre>
 *
 * {@code --head} is the writer's head: without it a change to the newest line cannot be seen (no
 * later line links to it). Exit 2 on a usage or I/O error.
 */
public final class AuditVerify {
    private AuditVerify() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length >= 1 && args[0].equals("verify")) {
                return verify(args, out, err);
            }
            if (args.length == 3 && args[0].equals("generate")) {
                return generate(new File(args[1]), Integer.parseInt(args[2]), out);
            }
        } catch (IOException | JSONException | NumberFormatException e) {
            err.println("audit-verify: " + e);
            return 2;
        }
        err.println("usage: verify [--head HEX] FILE... | generate FILE N");
        return 2;
    }

    private static int verify(String[] args, PrintStream out, PrintStream err) throws IOException {
        String head = null;
        List<File> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--head") && i + 1 < args.length) {
                head = args[++i];
            } else {
                files.add(new File(args[i]));
            }
        }
        if (files.isEmpty()) {
            err.println("usage: verify [--head HEX] FILE...");
            return 2;
        }
        int rc = 0;
        for (File f : files) {
            if (!f.isFile()) {
                err.println("audit-verify: no such file: " + f);
                return 2;
            }
            AuditChain.Result r = AuditChain.verify(f, head);
            if (r.ok()) {
                out.println(f + ": OK records=" + r.records + " legacy=" + r.legacy);
            } else {
                rc = 1;
                out.println(f + ": FAIL " + r.first() + " (" + r.breaks.size() + " breaks)");
                for (AuditChain.Break b : r.breaks) {
                    out.println("  " + b);
                }
            }
        }
        return rc;
    }

    private static int generate(File file, int n, PrintStream out) throws IOException, JSONException {
        if (file.exists()) {
            throw new IOException("will not write over " + file);
        }
        AuditChain.Writer w = new AuditChain.Writer(new AuditFile(file, Runnable::run, e -> { }));
        for (int i = 1; i <= n; i++) {
            w.append(new JSONObject().put("ts", 1_000_000L + i).put("event", "audio.transition")
                    .put("turnId", "t" + i).put("state", i % 2 == 0 ? "CONVERSE" : "CAPTURE").put("mode", 0));
        }
        out.println("head " + w.head());
        return 0;
    }
}
