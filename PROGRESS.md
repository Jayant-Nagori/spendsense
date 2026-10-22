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
- [ ] build APK, serve over LAN + QR, tell user
