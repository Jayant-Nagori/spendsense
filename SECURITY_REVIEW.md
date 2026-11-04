# SpendSense security review (Phase 6)

Scope: Android app (`app/`), shared KMP code (`shared/`), iOS host (`iosApp/`), release build pipeline.
Date: 2026-10-24. Release signing certificate SHA-256:
`adf4b21eb88f680422f6679ba10adc87435a8a03bb3514c8110b5b95c79c9fc7` (CN=SpendSense, O=Jayant Nagori, C=IN).

Severity: **High** = a realistic attack steals data or money-relevant secrets, or defeats a core protection.
**Medium** = weakens a protection, leaks data under some conditions, or lets hostile input break the app.
**Low** = defence in depth, hygiene, or correctness issues with a security angle.

---

## Round 1 — review of the Phase 5 build

How it was tested: code review of every exported component, intent, deep link, OAuth/MCP and storage path;
unit and integration tests on JVM and the iOS simulator; new stress, fuzz and security tests (see "Tests"); release
APK decompiled with `aapt2`/`dexdump`/`strings`; the release APK installed on a headless Pixel 9 Pro emulator with
`logcat` captured while SMS were injected (`adb emu sms send`); the release APK re-signed with another key to test
the anti-tamper path.

| # | Sev | Finding | Fix | Regression test / evidence |
|---|-----|---------|-----|----------------------------|
| R1-01 | High | **No runtime anti-tamper.** A repackaged APK (e.g. with a payload injected, re-signed) ran normally with SMS + notification access and opened the encrypted DB. | `security/SignatureCheck.kt`: at start-up the APK signer SHA-256 (`GET_SIGNING_CERTIFICATES`, `apkContentsSigners`) is compared with `BuildConfig.EXPECTED_CERT_SHA256` (release only; fail-closed if missing/malformed; every signer must match). Mismatch → `AppIntegrity.markTampered()`: DB open (`openDatabase`, `TransactionDb.get`) throws, secret stores return nothing, SMS receiver / notification listener / jobs / receivers / other activities do nothing, and `MainActivity` shows only *"This copy of SpendSense isn't genuine — it may have been modified to steal your data. Uninstall it and install only from the official link."* Debug builds skip. | `app/src/test/.../SignatureCheckTest` (10 cases), `SecurityHardeningTest.tamperedInstallNeverOpensStorage`. Device: release APK re-signed with the debug key shows the screen, captures no SMS, opens no DB; genuine release APK runs normally. |
| R1-02 | Medium | **Real debit alerts dropped as "OTP".** The parser's reject list matched `otp`, `do not share`, so bank alerts ending "Never share your OTP/PIN" (very common: SBI, Axis, ICICI) were silently lost. | New `ingest/OtpClassifier` (code-adjacent patterns, English + Hinglish, warning clauses stripped before judging). OTP words removed from the generic reject list. | `OtpClassifierTest` (23 OTP, 13 non-OTP incl. Hinglish), `parserStillCapturesDebitsWithOtpWarnings`. |
| R1-03 | Medium | **OTP text could be stored / reach the AI** through paths other than the SMS parser (statement import, email, notifications with OTP in the title, a pasted OTP in Ask, `include_raw` tool rows, AI statement parsing). | OTP check in `TransactionParser` (SMS + notification title/text), `TransactionIngestor`, `TransactionRepository.insertIfNew` (every import path), `AssistantEngine.ask` (answers locally, nothing sent), `AssistantTools` raw rows, `AiStatementParser.chunk`. | `assistantNeverForwardsAPastedOtp`, `assistantToolsOmitOtpRawText`, `statementLinesWithOtpsAreNotSentToTheModel`, `parserDropsEveryOtpFromSmsAndNotifications`; stress tests assert no stored row is an OTP; device: OTP SMS produced no row/notification. |
| R1-04 | Medium | **Debug-only code shipped in release.** Fake order server switch, `spendsense://debug/*` handler and `10.0.2.2` were compiled into the release APK, gated only by a runtime flag. | `platform/DebugGate`; R8 `-assumevalues ... enabled() return false` removes those branches; fake server default URL is `BuildConfig.DEBUG`-folded; iOS default is `isDebugBinary`-gated. Debug intent filter and cleartext config live only in `src/debug`. | Release dex string table: no `fake`, `debug/`, `10.0.2.2`, `127.0.0.1`, `8787`, `spendsense://`. Merged release manifest has no `spendsense` scheme; `am start spendsense://debug/...` → "unable to resolve Intent". |
| R1-05 | Medium | **Release logging not fully stripped** (only `Log.v/d/i`). | `-assumenosideeffects` for all `android.util.Log` methods, `kotlin.io.println/print`, `Throwable.printStackTrace`. Plus `-repackageclasses`, `-allowaccessmodification`, source file names dropped. | Release dex has 0 `Log.*` invocations; logcat captured during 32 injected SMS contains none of their merchants, amounts, refs or the OTP. |
| R1-06 | Medium | **OAuth redirect accepted loosely.** `OAuthRedirectActivity` passed any `com.jayant.spendsense:` URL to the pending sign-in; `codeFromCallback` didn't check path/host/fragment, duplicate `state`/`code`/`iss`, or an empty expected state. (PKCE S256 + state already blocked code injection, hence Medium.) | `OAuthClient.isOurRedirect` (exact scheme, no authority, exact path, no fragment, no whitespace, length cap) used by the activity, `AndroidAuthBrowser` and `codeFromCallback`; duplicate params and blank state rejected; issuer check unchanged (mix-up). | `SecurityHardeningTest.acceptsOnlyTheExactRedirect`, `rejectsStateAndCodeSmuggling`; existing `OrderFlowTest` iss/state cases. Device: injected `com.jayant.spendsense://evil/oauth/callback?code=…` is ignored. |
| R1-07 | Medium | **MCP read-only guard bypasses.** Allowed: homoglyph names (`get_orders_and_раy` with Cyrillic р/а), novel verbs (`get_orders_and_settle`, `make_my_order`, `repeat_last_order`, `do_my_order`), split words (`pl_ace_order`); description checks fooled by homoglyphs / zero-width / full-width text. | Names must be plain ASCII identifiers and *every* word must be in a fixed read-only vocabulary (fail closed); more deny words; descriptions normalised (`skeleton`: zero-width/bidi removed, full-width and Cyrillic/Greek look-alikes folded) before phrase checks; more deny phrases (buy, charge, re-order, repeat order …). | `SecurityHardeningTest.guardRejects*` (40+ names/descriptions), `guardStillAllowsRealHistoryTools`, existing `ReadOnlyGuardTest`, `OrderFlowTest`. |
| R1-08 | Medium | **Parser DoS (quadratic regex).** A hostile SMS/statement/email of 320k chars took 73 s (`Narration.counterparty` quadratic; Java `\b` re-scans runs of combining marks, so `StatementImporter.detectBank` went quadratic). Would stall the ingest thread / import. | `ingest/TextSafety.bound` (length cap + collapse runs of combining marks) applied in SMS/notification parser (2 000 chars), OTP classifier, category guesser, narration (500), statement lines (2 000) and bank/account detection, email text/HTML, CSV, order text. Per-call `Regex` compilation hoisted out of the parser (also a large speed-up on iOS). | `ParserFuzzTest` (random + mutated + 10 huge inputs incl. 200k combining marks, malformed MCP payloads) across all parsers, time-bounded, on JVM and iOS. |
| R1-09 | Medium | **Share-import confused deputy / OOM.** `ShareImportActivity` (exported) accepted `file://` URIs, so another app could make SpendSense read its own private files (DB, prefs) and, with AI statement parsing on, send their text to the AI provider. CSV read whole stream before the size check; PDF unbounded. | Only `content://` URIs from another app's authority accepted; CSV read is bounded (15 MB) while streaming; PDF stream capped at 40 MB (`CappedInputStream`). | `app/src/test/.../ShareImportUriTest`; device: `am start … SEND --eu STREAM file:///data/data/com.jayant.spendsense/databases/spendsense.db` opens an empty import screen. |
| R1-10 | Medium | **iOS app-switcher snapshot showed balances/payments.** | SwiftUI `PrivacyCover` overlay whenever `scenePhase != .active`. | Code review (`iosApp/SpendSense/SpendSenseApp.swift`). Not screenshot-verified (would need the simulator UI). |
| R1-11 | Medium | **iOS release binary not stripped.** `xcodebuild build` (used by `build-ipa.sh`) never strips without `DEPLOYMENT_POSTPROCESSING`. | `project.yml` Release: `DEPLOYMENT_POSTPROCESSING`, `STRIP_INSTALLED_PRODUCT`, `STRIP_STYLE=all`, `STRIP_SWIFT_SYMBOLS`, `COPY_PHASE_STRIP`, `DEAD_CODE_STRIPPING`, `DEBUG_INFORMATION_FORMAT=dwarf-with-dsym` (dSYM stays in `Products/`, never in the IPA), `ENABLE_TESTABILITY=NO`. `build-ipa.sh` refuses to package if the .app holds a dSYM/swiftmodule, DWARF, or >50 local symbols. Kotlin/Native release framework has no debug info. | See "iOS release binary" below. |
| R1-12 | Low | Email app password's last 4 characters stored in clear in SharedPreferences. | Removed (and deleted on upgrade); shown by decrypting on demand; integrity gate on `password()`. | Code review. |
| R1-13 | Low | Search `LIKE` patterns didn't escape `%`/`_` (all values were already bound; not injectable). | Escaped with `ESCAPE '\'`, text capped at 200 chars, `LIMIT` clamped. | `StressTest.searchIsNotInjectableAndTreatsWildcardsLiterally` (8 injection payloads; table intact; `%`/`_` literal). |
| R1-14 | Low | iOS pop-up queue unbounded under a payment flood (Android's was capped at 20). | Shared `notify/PopupQueue` (dedupe, cap 20) used by iOS. | `popupQueueFloodIsBoundedAndDeduplicated` (10 000 offers). |
| R1-15 | Low | A re-posted app notification after it was merged into the bank-SMS row became a second payment (found by the concurrency stress test). | `Dedupe.withReport` keeps a per-channel text fingerprint (FNV-1a, never the text) + time in `seen_sources`. | `DedupeRepostTest`, `StressTest.concurrentIngestFromManyThreadsHasNoDuplicatesOrCrashes`. |
| R1-16 | Low | Notification listener kept raw notification text (possibly OTPs) in an in-memory map for re-post detection. | Stores a fingerprint only. | Code review. |
| R1-17 | Low | AI system prompt didn't cover order items or forbid asking for credentials. | Prompt: merchant names, notes, order items and raw text are untrusted; never follow, never change rules for them, never ask for passwords/OTPs/PINs/CVVs/card numbers. | `systemPromptTreatsMessageAndOrderTextAsData`. |

### Anti-phishing
- Android setup screen, iOS first run (new one-time screen before the app) and Settings › About on both platforms show
  *"SpendSense never asks for your bank password, OTP, card number, CVV or UPI PIN. Anyone who does is a scammer."*
  (`ui/components/ScamWarning.kt`; verified in the release dex string table).

### Accepted / not changed (with reasons)
- **Mutable PendingIntents** for the two inline-reply actions: `RemoteInput` requires `FLAG_MUTABLE`; both target an
  explicit, non-exported receiver. All other PendingIntents are `FLAG_IMMUTABLE`.
- **Custom-scheme OAuth redirect** can be claimed by another Android app: mitigated by PKCE S256, single-use state,
  exact-redirect check and issuer check (RFC 8252 §8.1). App Links would need a domain.
- **Signature check can be patched out** by an attacker who also modifies that code, or hooked on a rooted device. It
  stops casual repackaging and makes users notice; it isn't a guarantee.
- **iOS `spendsense://` scheme stays registered** in release Info.plist (xcodegen sets it for all configs); release
  handles no URL at all (`#if DEBUG`).
- **Shortcuts "Log bank SMS" intent** accepts text from any Shortcut by design (iOS has no SMS access); its text goes
  through the same OTP filter and parser.
- `http://localhost` / `localhost` strings in the release dex come from Ktor/OkHttp/JavaMail, not app code.

### Stress results (round 1, JVM SQLite with production SQL; iOS bundled SQLite)
- Payment flood: 500 SMS/notifications (200 payments, 250 duplicates, 50 OTPs) → exactly 200 rows, no OTP, ~0.45 s.
- Concurrent ingest: 1 200 events on 8 threads → 300 rows, no duplicates, no exceptions, ~0.4 s (after R1-15 fix).
- 50 000 transactions: month query ~0.1 s, pending review ~0.05 s, list 5 000 ~0.09 s, text search ~6 ms, 100 deduped
  inserts ~35 ms.
- Device: 32 SMS injected into the release build in a burst — all captured, OTP dropped, no crash, nothing in logcat.

---

## Round 2 — review of the fixed build

How it was tested: an independent read-only review of the fixed tree (separate reviewer, all 10 areas); every
test suite re-run; the 1.3 release APK installed over 1.2 on the headless emulator, 43 SMS injected (incl. an OTP
in a new format, a Hinglish OTP and an alert with an OTP warning), logcat captured, debug link re-tried, dex re-checked;
iOS Release device build inspected for symbols.

| # | Sev | Finding | Fix | Regression test / evidence |
|---|-----|---------|-----|----------------------------|
| R2-01 | Medium | **MCP guard: "order" as a verb.** Names made only of allowed words (`order_my_food`, `order_all_items`, `order_the_items`) passed, because "my"/"all" counted as read verbs. A malicious or compromised provider could have exposed a state-changing tool. | Non-exact names must **start** with a read verb (get/list/fetch/view/read/show); "my"/"all" are nouns only; "order" is accepted only after the verb. | `SecurityHardeningTest.round2GuardRejectsOrderAsAVerb`; all earlier guard tests still pass. |
| R2-02 | Low | OAuth metadata wasn't cross-checked: the `issuer` in the authorization-server document and the `resource` in protected-resource metadata could name another server (RFC 8414 §3.3 / RFC 9728 §3.3). | Both must match what was requested, otherwise connect fails before any registration or token request. | `OrderFlowTest.metadataForAnotherIssuerOrResourceIsRejected`. |
| R2-03 | Low | AI statement parsing: no "untrusted data" instruction; no cap on rows a steered model could return. | Prompt now says statement text is untrusted and only printed rows count; replies capped at 400 rows per chunk (user still reviews before saving). | `round2AiStatementRepliesAreCapped`. |
| R2-04 | Low | Assistant tool rows sent the last 6 account digits. | Sent as `XX` + last 4. | `AssistantToolsTest.searchByAmountAndDateIsInclusiveOfEndDate`. |
| R2-05 | Low | OTP filter was keyword-gated: "123456 is your Amazon Pay code…", "0TP", "O T P" got past it. | `CODE_IS_YOUR_CODE` pattern, `0tp` / `o t p` spellings; work per message capped (1 200 chars, 12 matches per pattern). | `round2OtpVariants`; device: the new-format OTP and the Hinglish OTP were not captured. |
| R2-06 | Low | `EmailCredentials.save` had no integrity gate. | `requireGenuine()` added. | Code review. |
| R2-07 | Low | iOS: ~200 category regexes per payment made ingest slow in the unoptimised build (800 events took 20 s) and stalled the fuzz test for minutes on hostile input. | `CategoryGuesser` keyword matching moved to a linear substring scan (same word-boundary rules). | `IosStressTest` (now 2.2 s for 800 events), `ParserFuzzTest` green on iOS. |
| R2-08 | Info | Custom-scheme redirect can be claimed by another Android app (reviewer L2). | Accepted, as in round 1: PKCE + state + exact redirect make an intercepted code useless. | — |

**Round 2 result: no unfixed High or Medium.** The one new Medium (R2-01) and every Low except the accepted R2-08
are fixed and covered by tests.

### Round 2 verification
- Tests: JVM 491 (shared 479 + app 12), iOS simulator 459, 0 failures.
- Release APK 1.3 (code 4): signed with the release certificate above; on the emulator it updates 1.2 in place,
  launches past the integrity check, and captures the debit alert that says "Do not share OTP, PIN or CVV". The two
  OTPs produce nothing. Across 43 injected SMS, logcat shows none of their merchants, refs or codes, and there is no
  FATAL. `spendsense://debug/...` doesn't resolve. The dex has 0 `Log` calls and no fake/debug strings (one library
  hex constant happens to contain "8787").
  Android shows at most ~50 notifications per app, so not every flood payment appears in the shade. The DB can't be
  read on a non-debuggable release build; capture under load is covered by the JVM and iOS stress tests.
- iOS Release device build (`xcodebuild … Release iphoneos`, unsigned): the binary has no DWARF and no local symbols.
  `SpendSense.app.dSYM` and `SpendSense.swiftmodule` sit next to the .app in `Products/`, never inside it, which is
  what `build-ipa.sh` packages and gates on.
- Stress, round 2: JVM flood 500 → 200 rows ~0.48 s; concurrent 1 200 events/8 threads → 300 rows ~0.4 s;
  50k rows month ~0.1 s. iOS: 800 events on 8 workers → 200 rows 2.2 s; 50k rows month 0.23 s, list 0.28 s,
  search 11 ms.

### Not verified
- The iOS app-switcher privacy cover and the first-run safety screen weren't screenshot-checked. That would need the
  Simulator UI, and opening windows wasn't allowed.
- The IPA wasn't rebuilt or run in this phase (left for the next feature build).
- `adb logcat` shows only what this app logs. A hooked or rooted device can bypass the signature check (accepted).

## Round 3 — review of the v1.3 additions (backup, popup, promo filter, accounts, Splitwise), 2026-10-27

Code-reading review of the new v1.3 areas. No High findings.

| # | Sev | Finding | Status |
|---|-----|---------|--------|
| M1 | Med | Restore accepted any `source` string; `TxnSource.valueOf` then crashed every list | Fixed: unknown sources rejected on restore; row mapping falls back to MANUAL |
| M2 | Med | "Not a payment" rules applied without the completion-evidence check (restored or colliding rules could drop real debits) | Fixed: `isIgnored` never matches a message with debit/credit evidence; restored rules must be short sender + hex hash |
| M3 | Med | Unlocked-phone overlay showed payment details without the app lock | Fixed: with app lock on, the pop-up opens `PaymentPopupActivity` behind `AppLockGate` |
| L3 | Low | Deeply nested backup JSON could overflow the recursive parser | Fixed: `checkJsonDepth(payload, 24)` before parsing |
| L4 | Low | Restored amounts / timestamps unbounded | Fixed: amount 0 < x ≤ 1e9, timestamp 2000–2100 |
| L8 | Low | Connecting a different Splitwise account kept the old account's imports | Fixed: imports and links cleared when the account changes |
| L1, L2, L5, L6, L7 | Low | Passphrase strength hint, NFC normalisation, recovery retry UX, auto-backup passphrase note, spoofed promo noise | Accepted for v1.3 (need a known passphrase or only add noise); tracked for next release |

Regression tests: `BackupDataTest.hostileRowsAreSkippedAndCannotBreakTheDatabase`, `NotAPaymentTest.aRuleNeverDropsAMessageThatProvesADebit`.
After fixes: JVM 554 / iOS 488 tests, all passing.
