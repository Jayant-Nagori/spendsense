# SpendSense design spec — NeoPOP (CRED-inspired)

The user's verdict on v1: "generic/ugly, confusing, too cluttered". Reference: CRED app (NeoPOP design system).
Goal: calm, premium, obvious. One job per screen. Fewer things, bigger and bolder.

## Foundation (already in code — use it, don't fork it)
- Theme tokens: `ui/theme/Color.kt`, `Theme.kt`, `Type.kt`. Background pure black `#000`, surfaces `#121212/#1A1A1A`,
  text white / grey `#8C8C8C`, ONE pop accent lime `colorScheme.primary` (`#E5FE40`) used only for "needs you" states
  and tiny highlights. Credit green `MaterialTheme.spend.credit`. Debits are plain white (no red).
- Shapes are near-square (2–4dp). Bottom sheets: 12dp top corners only. **No pills, no rounded-full chips, no circles**
  except avatar initials.
- Buttons: `ui/components/Buttons.kt` → `NeoPopSurface`, `PrimaryButton` (white face, black UPPERCASE label, 3D edge),
  `GhostButton` (black face, hairline border). Use these for every tappable button/chip/card-action. Do not change
  their signatures.
- Type: money/hero numbers `displayLarge/Medium/Small` (Manrope 800, tight tracking). Section labels: `labelSmall`
  UPPERCASE grey with wide tracking (e.g. "NEEDS YOUR ANSWER"). Screen titles may use `headlineMedium` (serif) — max
  one per screen.

## Layout rules
- 20dp side gutters, 8dp spacing grid, sections separated by 32–40dp of space or a 1dp hairline — not by boxing
  everything in cards. Use cards only where a block is interactive.
- Max ~3 sections visible on first screen. No horizontal carousels of cards. No donuts + lists of the same data twice.
- Lists: flat rows separated by hairlines; left 40dp square monochrome icon tile (2dp radius), name + one meta line,
  amount right-aligned. Lime "ANSWER" tag only on pending rows.
- Inputs: square-cornered, 1dp hairline border, focus border white. Search field same.
- Filter chips → square NeoPOP toggles (selected = white face/black text; unselected = black face/hairline).
- Bottom nav: flat full-width bar, black, 1dp top hairline, 3 items (Home, Wealth, Ask) icon + tiny uppercase label,
  selected = white, unselected = grey. No floating pill.
- FAB: square white NeoPOP "+ ADD" block, bottom-right, above the nav.
- Motion: NeoPOP press-sink on buttons, count-up on hero numbers, 150–250ms fades. Nothing bouncy.

## Information architecture (3 tabs)
1. **Home** — top bar: month name (tap to change month via ‹ › small square buttons) + settings gear (top-right, opens
   Settings as a full screen). Then:
   a. Hero: "SPENT THIS MONTH" label, huge `₹6,325` (my share), one line under it: "₹6,325 paid · ₹0 covered by
      others · ₹2,000 in" in grey. No progress bar.
   b. "NEEDS YOUR ANSWER" — ONE card at a time (the newest pending debit): merchant, time, big amount, two NeoPOP
      buttons side by side: "ALL MINE" (white primary) and "I SPLIT IT" (ghost). Below: "1 of 4 · skip" text link.
      Hidden entirely when nothing is pending.
   c. "WHERE IT WENT" — single horizontal stacked bar (4dp tall, segments in greyscale + lime for the top category)
      then top 4 categories as rows (name, %, amount). No donut, no daily chart, no top-merchants on Home.
   d. "RECENT" — last 5 rows + "SEE ALL ACTIVITY →" text button which opens the Activity screen (full screen, back
      returns to Home). FAB "+ ADD" lives on Home and Activity.
2. **Wealth** — net worth hero, a thin allocation stacked bar, holdings as flat rows grouped by type, "+ ADD" FAB.
3. **Ask** — chat. Empty state: headline "Ask about your money", 4 suggestion rows (flat rows with → arrow, not pills),
   input bar pinned to bottom (square, hairline), send = small white NeoPOP square.

Settings, Activity, Split sheet and Lock screen are secondary screens using the same language.
