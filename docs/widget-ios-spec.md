# Widget spec: iOS Focus-control fidelity

Source: dedicated research pass over Apple docs/HIG and iOS 15→27 coverage
(2026-09-30). The design reference is the **Control Center Focus control** —
Apple ships no first-party home-screen Focus widget on any iOS version.

## The interaction model (verified iOS behavior)

- The control has **two hit zones**: tapping the **icon/label zone toggles**
  the shown Focus on/off; tapping the **trailing chevron zone** (visible
  up/down arrows since iOS 18.4) or long-pressing anywhere **opens the Focus
  selector**. Long-press is unimplementable in Glance/RemoteViews — the
  chevron zone is the faithful and sufficient substitute (it's what Apple
  itself shipped as the visible affordance).
- Off-state title is literally the word **"Focus"** (not "Off"); on-state
  title is the active Focus's name, with a secondary **"On"** value at larger
  sizes.
- The expanded selector: one row per Focus (its glyph in its colour + name),
  the active row highlighted with a trailing **"On"**, tapping the active row
  turns it off, tapping another switches (single-active). iOS has no "Off"
  row — ours keeps its explicit Off row as a deliberate Android-clarity
  divergence. Per-row duration options ("For 1 hour", "Until this evening",
  "Until I leave this location") are **deferred** (engine work). Bottom
  entry: one "Focus settings" row → MainActivity (collapses iOS's "+" and
  per-row Settings link).
- iOS tints the **symbol** in the on state, never the control's material —
  do not flood-fill the card in the mode colour. Chevrons are never tinted.

## Implementation plan (repo-verified against Glance 1.2.0)

### 2×1 small bracket — three-zone Row (replaces the single clickable in `FocusWidget.CurrentFocusButton`, widget/FocusWidget.kt:239-270)

| Zone | Width | Content | Action | Semantics |
|---|---|---|---|---|
| Toggle | `defaultWeight()` | existing `GlyphChip` (38dp) + 10dp spacer + name | existing `focusButtonAction()` toggle | on: "Work, on, double-tap to turn off"; off: "Focus, off, double-tap to turn on" |
| Divider | 1dp, inset ~8dp v | `Box` bg `onSurfaceVariant` @ ~20% | none | none |
| Chevron | fixed 36dp | new `ic_chevron_updown` drawable (stacked up/down chevrons, 16dp, tint `onSurfaceVariant`, never mode-coloured) | `actionStartActivity<FocusPickerActivity>()` | "Choose a focus mode" |

Behavior corrections:
1. Off label string becomes **"Focus"** (`widget_off_label`).
2. `TileTap.Ask` / `TileTap.Blocked` on the toggle zone open **FocusPickerActivity**
   (the old "widget cannot show a dialog" comment at FocusWidget.kt:276-278 is
   obsolete). `MainActivity` remains only for the hard-blocked card
   (FocusWidget.kt:187-203).

### Wide brackets (`ROW_SIZE`, `GRID_SIZE`)

Keep the per-mode grid (`ModeButtons`/`ModeCell`). Add:
- **Overflow cell** when modes exceed capacity: chevron glyph on
  `surfaceVariant` → FocusPickerActivity (today overflow modes are invisible).
- Secondary **"On"** line under the active cell's name at `GRID_SIZE` only.

### Optional (low priority): 1×1 bracket `DpSize(56.dp, 48.dp)` — chip only,
whole view toggles (mirrors iOS 27's new 1×1 symbol-only size).

### FocusPickerActivity (new, widget/)

`exported=false`, `excludeFromRecents=true`, `launchMode="singleTop"`,
`theme=Theme.FocusModes.Translucent`. Do **not** reuse TilePrefsActivity (it
is bound to QS_TILE_PREFERENCES and bottom-anchors); this one is a **centred
card** reusing `ui/ModePicker.kt::ModePickerSheet` inside the same
scrim-tap-to-dismiss Box pattern as TilePrefsActivity.kt:56-66. Additions to
ModePickerSheet usage: trailing "On" on the active row; tapping the active
row deactivates; bottom "Focus settings" row → MainActivity. After a pick:
`finish()` first, then submit through ModeEngine (same ordering as
ComposeModePickerDialog.kt:78-80).

### State deltas

| | Off | On |
|---|---|---|
| Chip | hollow `OFF_RES` ring, `onSurfaceVariant`, no disc | solid disc in mode colour, white glyph |
| Label | "Focus", `onSurfaceVariant`, Normal | mode name, mode colour, Medium |
| Value | — | "On" at GRID_SIZE+ only |
| Chevron | `onSurfaceVariant` | unchanged |
| Card | `widgetBackground` | unchanged (never flood-filled) |

### Explicitly not replicated (documented substitutes)

Blur/Liquid Glass (host material + system radii; never fake with
translucency — RemoteViews composites unblurred onto the wallpaper),
long-press expand (chevron zone), springy expand animation (dialog-activity
animation), symbol on/off animation (instant delta).
