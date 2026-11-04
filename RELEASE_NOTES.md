# SpendSense release notes

## 1.3 (build 4)

Install updates over the top — don't uninstall first. If Android says 'App not installed' you have an old test build: take a backup in Settings → Backup first, then uninstall, install, and restore.

- **Pop-up on time.** The payment pop-up appears within a second of the payment notification when the phone is
  unlocked. When the phone is locked or the screen is off it opens like an incoming call (Android 14+: allow
  "Full-screen notifications" in setup). Tapping the notification opens the pop-up directly. A quiet "watching for
  payments" notification keeps One UI from putting SpendSense to sleep (Settings › Capture, can be turned off).
- **No more promo pop-ups.** Cashback offers, "get ₹15 back when you pay", scratch cards, coupons, bill reminders,
  statements, failed payments and OTPs are ignored. Cashback that is actually credited is recorded as income. New
  "Not a payment" button in the pop-up and in a payment's details: it removes it and ignores similar alerts from
  that sender.
- **Backup & restore.** Settings › Backup: an encrypted backup file (your passphrase; AES-256-GCM, PBKDF2 310 000
  rounds) with payments, splits, receipts and settings — never API keys. Restore merges and skips duplicates.
  Android: weekly auto-backup to a folder you pick. If the app's key is ever lost, a recovery screen offers
  "Restore from backup" or "Start fresh" instead of crashing.
- **By card & account.** Home shows how much was paid from each card and account (Axis Bank Credit Card ••1234,
  HDFC Bank UPI ••5678 …); tap one for its payments. Settings › Accounts & cards: rename, hide, merge. Credit card
  bill payments and transfers between your own accounts no longer count as spending. Ask: "how much on my Axis card
  this month", "hdfc card se kitna kharcha", "card wise spend".
- **Splitwise.** Settings › Connected apps › Splitwise with your own API key: what friends paid for you is added as
  your share, payments you made are linked and split, settle-ups are marked as transfers, and Home shows who owes
  whom. Optional "Also add to Splitwise" after a split (off by default).
