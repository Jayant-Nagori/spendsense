# SpendSense build — progress (resume point)

Goal: personal Android app (Kotlin/Compose) — auto-track UPI/bank/card txns from SMS + payment-app
notifications, post-payment split prompt (checkbox "fully mine" → else ask my share), dashboard,
manual entry (cash), Wealth/investments tab, AI assistant (Claude + offline fallback), fully secured,
premium "$10k" UI. Deadline 07:30 IST 2026-10-23. Install on user's phone WITHOUT USB/wireless
debugging: serve APK over LAN (Mac Wi-Fi IP via `ipconfig getifaddr en0`) + QR; user installs from browser.

Repo: ~/spendsense (branch main). Agent worktrees: ~/spendsense-worktrees/{ingest,ui,ai,security,portfolio}
on branches agent/<name>. Merge with `git -c commit.gpgsign=false merge --no-edit agent/<name>`.

## Status
- [x] skeleton (main 9711328)
- [x] security merged
- [x] ai merged
- [x] portfolio v1 merged (polish pass running — merge again when it lands)
- [x] ingest (written by orchestrator) (SMS/UPI/card parser) — merge
- [x] ui (theme, dashboard, transactions, split, settings) — merge; wire launcher icon in manifest
- [x] full build + all unit tests on main
- [x] emulator (Pixel_9_Pro) visual QA: screenshot every screen, design polish pass on everything (incl. AI chat)
- [x] build APK, serve over LAN + QR, tell user
Serving: http://192.168.1.103:8765/SpendSense.apk (sha256 16fe1524…9207). Remaining: user installs; optional polish.

## Phase 2 — NeoPOP redesign (user: "UI is disastrous", wants CRED-like, simpler)
Spec: DESIGN.md. Foundation committed on main (c80f8e3). Agents in worktrees:
~/spendsense-worktrees/r-home (redesign/home: nav 3 tabs, Home, Activity, split, components),
r-wealth (redesign/wealth: portfolio/*), r-ask (redesign/ask: ai screen, settings, lock UI).
- [x] merge redesign/home (DONE), redesign/wealth, redesign/ask (wire SettingsScreen(onBack) if needed)
- [x] build + tests, emulator screenshots of all screens, fix leftovers
- [x] release APK → ~/spendsense-dist/SpendSense.apk, serve on LAN (python3 -m http.server 8765 --bind $(ipconfig getifaddr en0)), tell user with URL

## Phase 3 — imports (user: "add bank statement pdf import, gmail import etc")
Deps + R8 rules committed on main (d6e3bd8). Agents:
~/spendsense-worktrees/i-stmt (imports/stmt: imports/statement/**, imports/ImportScreen.kt, ShareImportActivity + manifest entry)
~/spendsense-worktrees/i-mail (imports/mail: imports/email/** — EmailImportSection(), Gmail IMAP + App Password; JobService manifest entry to add by orchestrator)
- [x] merge imports/stmt + imports/mail after redesign merges; wire ImportScreen(emailSection = { EmailImportSection() }) into Settings ("Import" row) and Home; add JobService to manifest

## Refund flow (user: "keep a refund flow that i returned this product") — DONE on main
Schema v2 (refund_status/refund_amount/refund_of_id, migration), repo markReturned/cancelReturn/linkRefund/unlinkRefund/findRefundTarget,
auto-match in TransactionIngestor for refund credits + RefundNotifier, RefundCard in TransactionDetailSheet, refunds excluded from income.
TODO at QA: emulator test — mark Dmart ₹1,250 returned, send "Refund of Rs 1250.00 from DMART credited to HDFC Bank Card XX4321" SMS, check link + notification.
Served: http://192.168.1.110:8765/SpendSense.apk (12:27). Accent: champagne gold #D6B97C.
