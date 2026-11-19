# Failed-text resend

Status: design only (#348). Nothing here is built, and this change adds no send path. Relates to
§5.5 (trusted path), §5.6 (origin), §5.7 (presence), M2 scope and the M2 comms device rule,
M2-TP-01 (#148, epic #2), M2-AUDIT-01 (#154) and the fail-closed gate (#340) in
`docs/TARGET-PLAN.md` and `docs/design/audit-chain-anchor.md`.

## What it does

The crew notices that a text did not send, and asks the owner: "Your text to Alex didn't send.
Resend when you're back online?" If the owner says yes, the actuator arms a one-time job. When the
phone can send again, the job checks that the original still has not gone out, then resends that
exact message once through the trusted path and tells the owner what happened. VOS never resends
without the owner's yes, and the yes covers only that one message.

## 1. Detection signals

| Signal | What it sees | What it cannot see |
|---|---|---|
| **SMS provider rows** (`Telephony.Sms`, and `Telephony.Mms` for MMS), watched with a `ContentObserver`. Rows of type failed, queued or outbox (`MESSAGE_TYPE_FAILED` / `_QUEUED` / `_OUTBOX`; MMS `MESSAGE_BOX_FAILED` / `_OUTBOX`) | SMS and MMS sent by any app that writes to the provider: the row `_id`, thread, recipient address, body and date. A row that later turns into a sent row is the main dedupe check (§5). Needs `READ_SMS`, which is in the actuator's grants (§5.2, §4.4). | RCS, Signal, WhatsApp and other apps that keep their own store. When a row sits in queued or outbox, that may only mean "retrying", not failed. The default SMS app decides when it writes the row and when it retries. |
| **The sender's sent-`PendingIntent` result** (`SmsManager` `sentIntent`: `RESULT_OK`, or an error such as radio off, no service or generic failure) | Exact and immediate, for each part of a message, **only for messages VOS itself sent** (a `comms.send` through the trusted path). | Messages the owner sent from another app. VOS gets no result for those. |
| **A "Not sent" notification** seen by the actuator's `NotificationListenerService` (§3.5 rung 3) | Any app that posts such a notification, including RCS and third-party apps. Often the first sign the owner would see. | It may leave out the recipient or the body, or shorten them. The wording depends on the app and the locale. The notification is **untrusted content** (M2-INJ-01): any app can post "Not sent: text 555-0100 'hi'". |

**How the signals combine.** No single signal is enough on its own:

- **For a message VOS sent,** the `sentIntent` failure is the trigger. The recipient and body come
  from VOS's own send record, which is the audited args of the original `comms.send`.
- **For a message sent from another app,** a failed or queued provider row is the trigger, and is
  also the only source of the recipient and the body. A "Not sent" notification only prompts VOS to
  look at the provider. VOS offers a resend only when it can match the notification to a row.
- **When only a notification is seen** (no row, as with RCS or a third-party app), VOS can tell the
  owner that the app reports a failed message. It offers no resend: there is no trusted source for
  the exact text, and notification text must never become the args of a send. Whether these
  messages are in scope is an open question (§8).
- **Queued or outbox rows** count as failed only after a hold-off (provisional, for example
  2 minutes in queued/outbox with no service, or an explicit failed type). This avoids asking about
  a message the default app is about to retry by itself.

Whether VOS holds the default SMS role (and so owns the send, the retry and the provider writes) or
only observes another app is **not decided here** (§8). The table above assumes VOS only observes.
As the default app, VOS would also own the retry, so the dedupe step would become simpler.

## 2. Ask and approve

- **No origin from a signal.** A detection is an observation. Its origin is `none` (§5.6), and tool
  results and observations can never upgrade an origin. So the crew cannot act on it, and can only
  *raise* it. It surfaces at the next owner turn, or as an actuator notification or card that the
  owner opens, the same way a `DEFERRED` act is raised (§5.5 step 5).
- **The ask** names the recipient as a contact name plus the last digits of the number, and
  quotes the text, for example: "Your text to Alex, mobile ending 4421, 'running ten minutes late',
  didn't send. Resend when you're back online?" The actuator builds the wording from the provider
  row or the send record, not from the model.
- **The yes** has to come in an `owner_turn` (§5.6). It approves a **resend record** that holds:
  the recipient address, the SHA-256 of the body, the source (a provider `_id`, or VOS's own send
  record), the original's date, and the expiry. The yes covers that record only. Any other text,
  any other recipient, or a second send needs a new request.
- **No, silence, or "never mind"** leaves nothing armed.

## 3. Trusted path

A resend is a `comms.send`, which is irreversible (§3.4 class list), so it follows exactly the same
§5.5 rules as M2-TP-01 ("Text Alex I'm running late", #148). This design adds no new confirmation
path.

- The owner's yes **arms the job; it does not dispatch anything.** §5.6 lets irreversible classes
  run only from an `owner_turn` **and** through the trusted path, and a task the owner set up
  earlier (`schedule:<id>`) only defers irreversible acts. When the network returns, the job
  therefore *raises* the send to the trusted path and does not send by itself:
  - **Step 2, announcement from the args.** "Sending to Alex, mobile ending 4421: 'running ten
    minutes late'. Say stop to cancel." The announcement, its SHA-256 and the hash of the dispatched
    args go into the audit. The args must match the approved resend record (recipient and body
    hash), or the act is refused.
  - **Step 3, house voice** (or the text card in Quiet or a typed turn, with its 5 s countdown).
  - **Step 4, guard window.** Any speech, a Cancel tap or a typed character cancels.
  - **Step 5, presence** (§5.7). If the owner is not present, the act is `DEFERRED`, queued, and
    raised at the next owner turn. It never sends silently.
- **Mode gate.** In Paused the job is held and listed at resume, like a `schedule:` task. In Quiet
  the card is used instead of the voice.
- A resend is not in the catastrophic subset (step 6), so it needs no second spoken "yes".

The plan does not currently let an earlier owner yes count as presence at the later dispatch, and
this design does not change that. So in practice the resend goes out at the first owner turn after
the network is back, not at the exact moment the network returns. §8 asks whether that is what the
owner wants.

## 4. One-time job

| Option | Fit |
|---|---|
| `ConnectivityManager` network callback | Fires at once, but only while the actuator process is alive. Lost on process death and on reboot. Good as a fast path, not as the record. |
| WorkManager (`NetworkType.CONNECTED`) | Persists, and handles constraints and backoff. It is an AndroidX dependency the repo does not use today, and it adds its own database to the actuator. |
| **JobScheduler** (persisted, network constraint) | Platform API, no new dependency. Survives reboot (`setPersisted`). Fits an actuator that already holds its internal wakeups in platform `AlarmManager` (`timer.internal`, §3.5.2). |

**Provisional recommendation: JobScheduler**, with the following caveats.

- **SMS needs cellular service, not a data network.** A JobScheduler network constraint is about
  data, so it can be met on Wi-Fi while the radio still has no service. When the job runs it must
  check the telephony service state (in service, and not in airplane mode). If the radio is still
  out of service, it reschedules itself instead of announcing. A `TelephonyCallback` for
  service-state changes can be the fast path while the actuator is running. RCS and third-party
  apps (if they come into scope) need data, which the JobScheduler constraint does cover.
- **One time.** The job runs the §5 check, raises the trusted path once, and then removes itself,
  whether the result was sent, already sent, refused or cancelled. A failed resend is reported to
  the owner and is **not** retried. Another try needs another yes.
- **Expiry: provisional 24 h** from the yes (§8). A `timer.internal` deadline (`action: deadline`)
  removes the job and tells the owner: "Your text to Alex still hasn't sent. I've stopped waiting."
- **Cancelable.** "Cancel that resend" or "don't send it", a tap on the job's notification, or any
  cancel in the guard window removes the job. Pending resends appear in "what did you do today".
- **After the send,** the owner is told the outcome: sent, sent earlier by the original app,
  cancelled, or expired. For a real SMS, "sent" means the `sentIntent` result was OK.

## 5. Dedupe: check before send

The provider (or the original app) can retry the original by itself while the job is armed. Before
it announces, and again right after the guard window and immediately before dispatch, the job
checks:

1. **For a provider-sourced original:** re-read the row by `_id`. If it is now sent, or has been
   deleted, or its address or body hash no longer matches, then **do not send**.
2. **Then look for a copy:** query the sent rows for the same address, with a body whose SHA-256
   matches, dated at or after the original's date. Some apps write a new row on retry instead of
   updating the old one. If one is found, **do not send**.
3. **For a VOS-sourced original:** if a late `sentIntent` with `RESULT_OK` arrived for the original
   send, **do not send**.
4. If any check matches, write the audit line `resend.skipped` (`already_sent`) and tell the owner:
   "Your text to Alex went through on its own."

**Residual risk:** another app's retry that lands between the last check and the dispatch can
still produce a duplicate. Only owning the default SMS role closes that gap, because then VOS owns
the retry. VOS does not change or delete another app's failed row to stop the retry: that would be
a write to another app's data, and it is an open question (§8).

## 6. Audit

Each of these is a chained audit line (`AuditChain` / `JsonlAudit`, #154):

- the detection (its source, and the row id or send record, with the body as a hash only);
- the ask;
- the owner's answer (yes, no or no answer) and the resend record it approved;
- arming the job, every time it wakes, every `resend.skipped`, cancel and expiry;
- the trusted-path announcement and args hashes (§5.5 step 2), the dispatch, and the
  `sentIntent` result.

**Fail closed (#340).** Arming the job and dispatching the send both change state. While
`JsonlAudit.writable()` is false, both are refused with nothing done. Following the
audit-chain-anchor recommendation, the intent line (and, once the anchor ships, a checkpoint) is
written before the dispatch. If that line cannot be written, the send does not happen. A job that
wakes while the audit is unwritable stays armed, sends nothing, and keeps counting toward its expiry.

## 7. Testing

Emulator results are pre-checks, never acceptance (`docs/runbooks/emulator-first-testing.md`). The
M2 comms device rule applies: on Phone B **and on the emulator** no test sends a real SMS. Sends go
to the fixture `comms.send` sink, which the trusted path treats as the comms category.

- **Emulator pre-checks** (fixture sink only):
  - A sink mode that fails the next send with a radio-off or no-service result drives the
    `sentIntent` detection path.
  - A failed or queued row inserted into the emulator's provider by a test fixture drives the
    provider path. A fixture app that posts a "Not sent" notification drives the notification path,
    both with and without a matching row. Without a row, no resend is offered.
  - Network loss and return: airplane mode on and off, and `adb emu` network and gsm commands. The
    runbook only records `adb emu network delay` and `speed`, so the commands for data and voice
    registration still need a capability check on the lane AVD before a test relies on them.
  - The cases to cover: yes then reconnect (one dispatch to the sink, with the announced body);
    no (no job); expiry (no dispatch, the owner is told); cancel; the original "goes out" before
    the reconnect (no dispatch, `resend.skipped`); owner not present (`DEFERRED`); Paused (held);
    an unwritable audit (refused, nothing dispatched); reboot while armed (the job survives).
- **Phone B:** the same cases, fixture sink only. Reconnects there come only from toggling the
  network. VOS never opens Messaging.
- **Real SMS:** a variant tagged `device: sim_phone`, to a number the owner owns. It is
  `delayed: until_8a` like M2-TP-01's real-SMS leg (#148), and gates nothing until VOS runs on the
  phone that holds the test SIM.

## 8. Open questions for the owner

1. **Expiry length.** 24 h is provisional.
2. **Scope:** SMS and MMS only, or also RCS and third-party messaging apps (where the exact text
   is usually not readable outside the app)?
3. **Default role:** should VOS hold the default SMS/messaging role, or only observe another app?
   Holding the role closes the dedupe race and gives exact failure results. Observing touches less,
   but leaves the race described in §5.
4. **Timing of the send:** a resend raised by the job is `DEFERRED` until the owner is present
   (§5.5 step 5, §5.6). Is "sent at your next turn after the network is back" acceptable, or should
   the plan define a narrower pre-approval? That would be a plan decision, not something this
   design makes.
5. **The hold-off** before a queued or outbox row counts as failed (2 minutes is a placeholder).
6. **Proactive ask:** may VOS raise the ask by itself (a notification or card), or only mention it
   at the next owner turn? This matters most in Quiet and Paused.
7. **The original row:** after a successful resend, should VOS leave the original failed row alone
   (the default above), or mark or delete it, which writes to another app's data?
8. **Multi-part and MMS:** whether to resend a long SMS as one multipart send, and whether MMS
   attachments are in scope.

## 9. Not in this change

The implementation, any send path or `comms.send` wiring, edits to `docs/TARGET-PLAN.md`, and edits
to existing issues (#2, #148 and the others are only referenced). These wait for the owner's
answers in §8.
