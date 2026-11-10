# Audit chain anchor

Status: proposal (#340). Relates to M2-AUDIT-01 (#154), §3.8 and §5.8 of `docs/TARGET-PLAN.md`.
A pure-JVM checkpoint helper (`ipc/.../AuditCheckpoint.java`) exists with tests. Nothing in the app
writes or checks checkpoints yet.

## What the chain covers today

`AuditChain` gives every line a `prev`: the SHA-256 of the line before it, as stored. `AuditVerify`
(`./gradlew :ipc:auditVerify`) checks a file on the host. `AuditChainLimitsTest` pins down what
that catches:

| Change to the file | Detected by the chain alone |
|---|---|
| One byte edited in a record | yes, at that line |
| A middle line removed | yes, reported at the line before the gap |
| The last N records removed, cut cleanly at a line boundary | **no** |
| The whole file rewritten from genesis with a different history | **no** |

The last two pass because every check compares the file with itself. `verify(raw, head)` also
checks the newest record against a head, but the only head that exists is the writer's in-memory
one. That head is recovered from the file at startup, and `JsonlAudit.chainHead()` has no
production caller, so after a restart the head agrees with whatever file is on disk.

## The fail-closed gate today (#340)

What the gate covers:

- **Scope:** state-changing acts by the actuator only. Every state-changing fast tool is refused,
  with nothing done, while `JsonlAudit.writable()` is false.
- **When it is false:** the chain head is unknown. That happens after a failed write, or when the
  head cannot be recovered at startup.
- **How it heals:** the next line written recovers the head from the file, and a successful write
  makes the audit writable again.
- **When it does not heal:** recovery needs a complete record within the file's last `MAX_TAIL`
  (4 MiB) bytes, and a readable file. If either is missing, every state-changing tool stays refused
  until the owner deals with the file. M2 needs an owner acknowledgement flow for this, the same
  one an anchor mismatch uses (below).

Open question for the owner: the session's model egress is **not** gated. The session writes a
`model.pick` line to its own audit, then sends the request to the vendor whether or not that line
was written. Should an upload be refused when its audit line cannot be written (compare P12, "every
upload is audited")? This change leaves it ungated on purpose.

## Threat

The threat is someone who can change the actuator's `files/audit/audit.jsonl` and wants to hide
what VOS did. They might cut off the newest lines (for example, the line for an act they caused)
or replace the whole log. Realistic routes:

- an offline change to CE storage (a backup/restore path, a rooted or forensic copy written back);
- a bug in the actuator that lets a caller choose a file path;
- code running as the actuator UID.

The last route is the hardest. That attacker can also use any key the actuator can use. No local
anchor stops it; only a copy held outside the UID can.

Out of scope: the session UID changing the actuator's file. It cannot do that without a sandbox
escape, which UID separation (§3.1) already covers.

## Options

1. **A checkpoint file signed with a Keystore HMAC key.** Every so often, the actuator writes
   `{head, ts, mac}` next to the log. `mac` is HMAC-SHA256 under a non-exportable Android Keystore
   key that only the actuator UID can use. At startup and on demand, the actuator checks two
   things: the checkpoint is authentic, and some line in the file hashes to its head
   (`AuditCheckpoint.check`).
   - Catches a tail cut below the checkpoint, and a rewrite, by anyone who does not hold the key.
   - Cost: one small file write per checkpoint. Works offline.
   - Limits: lines after the newest checkpoint are not covered. A genuine older checkpoint, put back
     together with a log cut to match it, passes, because nothing is monotonic (both limits are
     tested). Code running as the actuator can sign anything. Only the device can check the
     checkpoint, because the key never leaves it.
2. **The other UID keeps a copy of the head over the existing Binder link.** The actuator sends
   `{head, count}` to the session over `PeerLink`, and the session stores the newest one. Verifying
   compares the two.
   - Catches the same changes, and also code running as the actuator, because the copy is outside
     that UID.
   - Cost: one oneway call per checkpoint.
   - Limits: the session is the networked and less trusted side (threat A5). If it is compromised,
     it can report a wrong head. That raises a false alarm but cannot hide anything, unless both
     UIDs are compromised. The session may not be running, so sends must be queued and coalesced.
3. **The head in each task record.** `TaskRecords` already writes one JSONL file for each task. Its
   header or footer could carry the audit head at the time.
   - Cost: almost nothing.
   - Limits: the files are in the same directory and owned by the same UID as the log, so anyone
     who can rewrite the log can rewrite these too. They only help against careless edits.
4. **External export.** The owner's export (audit v1, §3.8) includes the head with each export.
   Each exported copy then anchors everything before it. This is the only anchor that survives a
   device compromise, but it is only as fresh as the latest export.

## Recommendation

- **Ship 1 and 2 together in M2.** Write a checkpoint before every irreversible act (alongside the
  intent line required by the fail-closed contract in `JsonlAudit`), on every audit rotation, and
  every 64 lines or 10 minutes, whichever comes first. Send the same `{head, count, ts}` to the
  session. The Keystore checkpoint covers the case where the session is not running. The session's
  copy covers code running as the actuator and the replay of an older checkpoint: if the session
  holds a newer count than the local checkpoint, the local one was rolled back.
- **Add 4 when export lands.** Skip 3.
- **On a failed check,** the actuator writes a chained `audit.anchor_mismatch` record that names
  which anchor failed, and shows it in "what did you do today". Starting up must never be blocked,
  but the fail-closed gate treats the audit as not writable for irreversible acts until the owner
  acknowledges the mismatch.

## Retention and rotation (30 days)

Rotation (`AuditFile.rotate`) renames the file. A new file should start at the last head of the
previous file rather than at genesis, so the files chain into each other.

That does not work today. `AuditChain.verify` expects a file's first chained record to carry
`GENESIS`, or to follow a legacy prefix. The `Writer` also keeps its in-memory head across
`AuditFile.rotate`. So the first record of a rotated file carries the previous file's head, and
`verify` reports a break there. Before rotation ships, one of these has to change:

- `verify` takes a starting head (from the previous file, or from an `audit.retention` record);
- or each file opens with a header record that names its starting head.

With 30-day retention the oldest files are deleted, so:

- A checkpoint names a head in a specific file. Each checkpoint should record which file it covers
  (its first record's hash), and each rotation should write a closing checkpoint for the old file
  and an opening one for the new file.
- When retention deletes a file, its checkpoints are deleted with it. The anchor only promises that
  nothing was removed early, before its 30 days were up. The deletion itself should be a chained
  `audit.retention` record (the file name, its last head, and the line count) in the current file,
  so a missing file can be told apart from an expired one.
- The session's copy only needs the newest head and count, plus the last head of each rotated
  file that has not expired.

## What M2-AUDIT-01 (#154) should test

Today's row ("change one byte, verification fails at that line") stays. Add:

1. Remove a middle line: the chain fails at the gap.
2. Cut the last N lines cleanly (with the app stopped, then restart it): the anchor check reports
   `HEAD_MISSING`, and an `audit.anchor_mismatch` record follows.
3. Replace the file with a valid chain written from genesis: same result as case 2.
4. Edit the checkpoint file, or sign one with another key: the anchor check reports `FORGED`.
5. Put back an older genuine checkpoint with a log cut to match it: the session's copy catches the
   rollback.
6. Make the audit unwritable (fill the directory, or swap a file in for it): every state-changing
   tool returns FAILED with nothing done (#340 gate), a read-only tool still runs, and acting
   resumes once a line is written again.
7. In a release build, run a turn and check that logcat has no `VOS_AUDIT` line contents.
8. Rotate the log and let a file expire: verification still passes across the boundary, and the
   expiry shows up as an `audit.retention` record. This case cannot pass until `verify` takes a
   starting head or files get a header record (see Retention and rotation).
9. Make the head unrecoverable (no complete record in the last 4 MiB): every state-changing tool
   stays refused across writes and restarts until the owner acknowledges it.

Some of this has JVM tests already. `AuditChainLimitsTest` covers case 1, plus the helper-level
parts of cases 2 to 5 (for case 5 it asserts the local-only limit). `JsonlAuditTest` and
`FastToolsTest` cover case 6, and `JsonlAuditTest` covers the JVM half of case 9. The device runs and the wiring are still to do. The "not detected"
tests in `AuditChainLimitsTest` assert the chain-only behaviour; change them when the anchor is
wired.
