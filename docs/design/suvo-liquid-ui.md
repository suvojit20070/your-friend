---
name: suvo-liquid-ui
description: Build or restyle any app UI in the "Suvo Liquid UI" language — warm seed-based Material 3 tonal palette, Liquid Glass on every floating surface (top bar, buttons, composer, dialogs, popovers, sheets, fields), G2 squircle shapes, and the signature liquid press (spring grow + finger-follow stretch + radial glow + inner shadow + haptic). Use for Android Compose, Android XML/Java, Web/Vue/Telegram Mini Apps or Flutter whenever the user asks for "Suvo style", "liquid glass", or this skill.
---

# Suvo Liquid UI — Design Skill (v2)

Every number in this file comes from a working production implementation. Follow it literally.
You may place components wherever the product needs them, but **the material, shape, color temperature, motion, press physics and component choices must match this file**. Section 3 (component judgement) is as important as the visual tokens.

Read order for an AI: §1 → §2 (glass architecture, mandatory) → §3 (judgement) → §4 (press) → then tokens and recipes.

---

## 1. The feel

- A calm, warm canvas (cream in light, deep warm grey in dark). **Content lies flat on it**: lists, text and settings groups. Content never floats.
- **Everything that floats is liquid glass**: corner buttons, top bar, composer/input, text fields, scroll-to-bottom, dialogs, popovers/menus, bottom sheets, toggle and slider thumbs while touched. Glass always shows a blurred, refracted, saturated version of the **real page behind it**.
- **Everything you can touch responds like liquid.** On press the element swells, leans toward your finger, stretches in the drag direction, lights up where you touch, gets a soft inner shadow, and gives a light haptic. On release it springs back.
- Shapes are **G2 continuous squircles** with big radii; nothing has a hard corner.
- Color comes from **one seed**. Accents are low-alpha tints; at most one solid primary per view.
- Motion is short (120–360ms), FastOutSlowIn, direction-aware; bounce lives only in touch feedback.

A screen fails this skill if: any floating surface is opaque or flat-colored, any tappable thing uses a plain ripple, a dialog/sheet/popover does not blur the page behind it, or corners are plain rounded rectangles.

---

## 2. Glass architecture (mandatory, read first)

This is why glass looks right everywhere, including dialogs. Get this wrong and popups will look flat.

### 2.1 One captured layer per screen
1. The **page content** (everything that scrolls) is drawn into a capturable layer. Call it the **content backdrop**.
2. The **top bar background** samples the content backdrop and exports its own result as the **chrome backdrop**.
3. The two are combined into one **screen backdrop**. This is shared down the tree (a CompositionLocal in Compose, a shared reference in Views, the document itself on Web).
4. **Every floating surface samples the screen backdrop**: corner buttons, composer, text fields, scroll-to-bottom, dialog panel, popover, sheet. They blur what is under them, including the top bar.

### 2.2 Overlays must live inside the screen tree
Dialogs, popovers and sheets are **not separate system windows**. Each screen owns an **overlay host layer** (z-index above content and chrome). Overlays are added to that host, so they can see and blur the page.

| Stack | Do | Don't |
|---|---|---|
| Compose | Render overlay in a `Box` layer inside the screen (`zIndex 4`), sample screen backdrop | `Dialog {}`, `ModalBottomSheet`, `Popup` with a new window: they cannot see the page |
| Android Views | Add overlay views to a `FrameLayout overlayHost` that is the last child of the activity root; blur the content root | `AlertDialog`, `BottomSheetDialog`, `PopupWindow`, `DialogFragment`: separate window, no blur |
| Web | Overlay `div` in the same document, `position:fixed`, `backdrop-filter` | `<dialog>` inside an element with `filter`, `opacity<1`, `transform`, or its own `backdrop-filter` (breaks sampling) |

Each overlay handles back press itself and closes on scrim tap.

### 2.3 Local isolation
If a floating element must sample only part of the page (e.g. a chat area with its own composer), wrap that part in a **local backdrop container**: it captures only its children and provides that as the backdrop to floating children inside it. Never let an element sample a layer that contains itself.

### 2.4 Layer order of a screen
```
z0  page content (captured as content backdrop, scrolls, nestedScroll → top bar)
z2  top bar background (progressive blur of z0, exports chrome backdrop)
z3  top bar foreground (title + 2 glass corner buttons)
z4  overlay host (scrim + dialog / popover / sheet, all glass)
```
Floating bottom elements (composer, FAB-like action, scroll-to-bottom) sit at z1–z3 above content and sample the screen backdrop.

### 2.5 Glass recipe (identical everywhere, only values change)
Draw in this order:
1. **Backdrop effects** on the sampled content: vibrancy (saturation ×1.5) → blur → lens refraction (edge bending, optional depth effect, optional chromatic aberration).
2. **Hue tint**: `primaryContainer @ 18–32%` with blend mode **Hue** (gives the glass a whisper of the theme color without darkening). Used on composer, text fields, dialog.
3. **Surface fill**: the preset's surface color at its alpha.
4. **Highlight**: ambient rim light (thin bright edge), alpha per preset.
5. **Shadow**: soft outer shadow per preset.
6. **Interactive layer** (§4): press transform + glow + inner shadow.

### 2.6 Glass presets

| Preset | Blur light / dark | Lens height / amount | Hue tint | Surface light / dark | Highlight light / dark | Shadow |
|---|---|---|---|---|---|---|
| Button (corner, action) | 4dp | 6dp / h÷2 | — | white 22% / `#AFAFAF` 15% | 0 / ambient 1.0 | r12, black 20% / 8% |
| Popover / menu | 24dp / 20dp | 2dp / h÷2 | — | white 22% / `#AFAFAF` 15% | ambient 0.4 / 1.0 | r20, black 20% / 8% |
| Composer bar | 16dp / 12dp | 4dp / h÷2 | primaryContainer 32% | surfaceContainerHigh 64% / 72% | ambient 0.15 / 0.3 | r20, black 12% / 6% |
| Scroll-to-bottom | 16dp / 12dp | 4dp / h÷2 | — | surfaceContainerHigh 64% / 72% | 0.15 / 0.3 | r20, black 12% / 6% |
| Text field | 2dp | 12dp / 24dp, chromatic on | primaryContainer 32% | surfaceContainerHigh 64% | default rim (0.5dp white line) | default |
| Dialog | 12dp / 8dp | 24dp / 48dp, chromatic **off**, brightness +0.2 light | primaryContainer 18% | surfaceContainerHigh 76% | plain | r16, black 24% / 16% |
| Sheet | same as Dialog | same | primaryContainer 18% | surfaceContainerHigh 76% | plain | r24, black 20% / 14% |
| Liquid capsule button | 2dp | 12dp / 24dp | button color (Hue) + color @ 75% | color @ 18% | ambient | default |
| Top bar | 16dp progressive | — | — | surfaceContainerHigh, tint 65% / 72% | — | none |

Defaults: vibrancy on, depth effect on, chromatic aberration on (except Dialog and Sheet).

Disabled glass: surface `surfaceContainer @ 42%`, no Hue tint, no press.

### 2.7 Progressive top bar
- Height: status inset + **72dp** (48dp button + 12dp top/bottom).
- Shader: blur the content, then for each pixel `alpha = smoothstep(barBottom, buttonRowMiddleY, y) × progress`; tint with surfaceContainerHigh at 65% (light) / 72% (dark), rising to **100% solid inside the status bar area**. Result: solid at the very top, melting to fully transparent at the bar's bottom edge. **No line, no shadow.**
- `progress` = 0 at scroll top, animates to 1 when content scrolls under it (280ms LinearOutSlowIn), back to 0 when scrolled to top.
- Fallback below Android 13: solid surfaceContainerHigh with the same alpha animation.

### 2.8 Fallbacks
No backdrop blur available (old Android, low-end, browsers without `backdrop-filter`): keep shape, size, shadow and press physics. Replace glass with `surfaceContainerHigh` at preset alpha **+ 20%**, a 1px top inner highlight `white @ 15%` (dark: `white @ 8%`). Never fall back to a flat opaque gray card with Material elevation.

---

## 3. Component judgement (which component, where, when)

This section decides the structure. Keep these choices even when the layout is new.

### 3.1 Screen shell
- Every page is one shell: page content + top bar + overlay host (§2.4). Pages do **not** draw their own app bar.
- Top bar has **exactly two icon slots** (left, right) and a centered title. If a page needs more actions, the right slot opens a **popover menu**. Never put 3+ icons or a text button in the top bar.
- Title: 20sp SemiBold centered. A switcher (model, account, workspace) replaces the title with a **title chip**: name + small ▾ that opens a popover anchored to the title (BottomCenter).
- Title can be "collapsible": a large in-page heading is visible at top; the bar title fades in only after scroll.
- **Back button position follows push direction**: forward push → back button **top-left**, content slides from the right. "Push from left" (e.g. opening history from home) → button **top-right** (→ arrow), content slides from the left. Always pair push and pop directions.

### 3.2 Navigation pattern
- Home is the main surface (e.g. chat). Secondary areas (history, settings) are **pushed pages**, opened from the corner buttons. No bottom navigation bar by default.
- If the product truly needs tabs (3–5 peer destinations), use a **floating glass capsule tab bar** (Button glass, capsule, 16dp side margins, nav inset + 12dp from bottom, selected tab = `primary @ 12%` pill inside with liquid press). Never a docked full-width bar.
- Primary page action (create, add) = a floating glass **action button** (48dp circle) or a full-width **liquid capsule button** at bottom (nav inset + 24dp), never a Material FAB.

### 3.3 Choosing the overlay

| Need | Use | Why |
|---|---|---|
| Confirm / warn / permission request / short input (1–2 fields) | **Dialog** (centered glass, 48dp squircle) | Blocks, demands a decision |
| 3–7 actions or options related to a button or title | **Popover** that morphs out of that button | Keeps spatial link to the trigger |
| Long list of choices, pickers, attachments, share targets, anything > 7 rows or with headers | **Sheet** (glass, bottom, 32dp top corners) | Thumb reach, scrollable |
| Single choice from 2–4 values inside settings | **Segmented selector** inline | No overlay needed |
| Single choice from 5+ values | Settings row with preview value → **Sheet** or **single-choice dialog** | |
| Rename, edit a value | **Input dialog** with glass text field | |
| Long text editing (prompts, notes) | **Pushed full-screen editor page** | Not a dialog |
| Feedback after an action | Inline state change or a small tonal text; avoid toasts/snackbars | Calm |
| Destructive confirmation | Dialog, destructive action as primary with `error` content color | |

Dialog buttons: max 2, equal width side by side. Never 3 buttons; use a popover or sheet instead.

### 3.4 Settings and lists
- Settings are **grouped cards** (`surfaceContainerLow`, 28dp G2), optional section title outside the card. Rows inside, no dividers.
- Row type by intent:
  - Opens a page → title + summary + **chevron**.
  - Shows current value → title + **preview value** (≤20% width) + chevron.
  - On/off → title + summary + **liquid toggle** (row tap also toggles).
  - Numeric → **expandable slider row**: collapsed shows value preview, tap expands a liquid slider with min/max labels.
  - Choice of 2–4 → **segmented selector**.
  - Long text → **expandable text card** (tap expands, chevron rotates).
  - Deletable item (saved configs, rules) → **swipe-to-delete row** (left swipe, threshold 40dp, red delete icon reveals at 20dp, rubber-band beyond 72dp).
  - Secondary inline action (e.g. "Edit") → trailing action text inside the row; tap target expands to ≥64dp wide.
  - Selectable list (providers, models) → 56dp circular leading logo + title + chevron.
- A page with a form (provider config, rule detail) = grouped cards of glass text fields + one bottom liquid capsule "Save" button; content shifts above the keyboard.
- Page description text at top of a list page when the user needs guidance (16sp Medium, 28sp line height).

### 3.5 Collections (history, files, conversations)
- Sections with collapsible headers (pin icon + "Pinned", "Today", "Yesterday"…), header color `primary`, chevron to collapse.
- Rows are 20dp G2 cards with press highlight; pinned rows have `secondaryContainer` background.
- Long-press enters **multi-select**: selected rows shrink to 0.98 (spring 0.5 / 200) and get a 2dp `primary` border animating in 250ms; top bar left button becomes "select all", right becomes the action.
- Running items show a tiny 11dp spinner before the time; pinned shows a 13dp pin icon.

### 3.6 Chat
- User messages: right-aligned tinted bubble. Assistant: **plain text on the canvas**, no bubble, no avatar.
- Tool calls / thinking: **collapsed tonal cards** between text, expandable.
- Message actions: small icon row under each message, not a long-press menu.
- Input: floating glass composer at bottom. Attach (+) left, send (↑) right, both glass action buttons; send becomes a loading indicator acting as stop.
- A glass scroll-to-bottom circle appears above the composer when not at bottom.
- Permission/approval requests from the AI → **dialog** with code preview, matched-rule line in `error`, and "Deny" / "Allow once".

### 3.7 Text input
- Single field in a page or dialog → **glass text field** (36dp G2 radius, min height 52dp).
- Secret (API key, password) → glass secret field with eye toggle as trailing content.
- Multi-line → same glass field, grows, top-aligned text.
- Labels live **outside** the field (labelLarge, onSurfaceVariant, 8dp gap) or as placeholder. No floating Material labels, no outlines.

### 3.8 Empty / onboarding
- Onboarding: big bold typography (96sp Black) typed with an animated cursor, then a full-width liquid capsule button fades in.
- Empty state: one icon + one line `onSurfaceVariant`. No illustrations.
- Onboarding flows: provider pick → config form → done page. Each is a pushed page with forward slide.

---

## 4. Liquid press (the signature interaction)

Apply to **every glass element** and the liquid variants of buttons, fields, composer, popover. Flat content rows use the row press (§4.6).

### 4.1 State
- `pressProgress` ∈ [0,1], animated with a spring: **dampingRatio 0.5, stiffness 300**, visibility threshold 0.001. Down → animate to 1, up/cancel → animate to 0.
- `touchPosition`: snapped to the finger on every move. `startPosition` = first down point.
- `offset = touchPosition − startPosition`. On release, `touchPosition` animates back to `startPosition` with the same spring (damping 0.5, stiffness 300). This makes the element wobble back after a drag.
- Tracking starts on the **initial pointer pass**, before child handlers, and never consumes the gesture (taps still click; scroll still scrolls).

### 4.2 Transform (applied every frame to the element's graphics layer)
```
w, h = size;  minDim = min(w,h);  maxDim = max(w,h)
scale  = lerp(1, 1 + pressScale / h, pressProgress)            // swell
tx     = minDim * tanh(damping * offset.x / minDim)              // lean toward finger
ty     = minDim * tanh(damping * offset.y / minDim)
angle  = atan2(offset.y, offset.x)
dragS  = maxDragScale / h
scaleX = scale + dragS * |cos(angle) * offset.x / maxDim| * min(w/h, 1)   // stretch along drag
scaleY = scale + dragS * |sin(angle) * offset.y / maxDim| * min(h/w, 1)
```
Glass sampling is done in the transformed space, so the refraction moves with the element.

### 4.3 Per-component interactive styles

| Component | pressScale | maxDragScale | damping | glow |
|---|---|---|---|---|
| Corner / action glass button (48dp) | **24dp** (big swell) | 4.8dp | 0.05 | on |
| Liquid capsule button, scroll-to-bottom, dialog buttons | 4dp | 4dp | 0.05 | on |
| Composer bar | 3dp | 3dp | 0.05 | on |
| Glass text field (only when unfocused or empty) | 3dp | 3dp | 0.05 | on |
| Popover panel (non-scrollable) | 6dp | 6dp | 0.08 | off |
| Sheet panel (drag handle area only) | 4dp | 4dp | 0.06 | off |

### 4.4 Glow (drawn over the content, additive)
While `pressProgress > 0`:
1. Fill the whole shape with `white @ 8% × progress`, blend **Plus**.
2. Radial glow centered at the finger (clamped inside bounds): color `white @ 15% × progress`, radius `1.5 × minDim`, intensity `smoothstep(radius, radius × 0.5, distance)`, blend **Plus**.
3. If shaders aren't available: a flat `white @ 25% × progress` Plus fill instead.

### 4.5 Inner shadow (corner buttons, toggle thumb)
Record the shape outline filled with `black 20%`, punch out the same outline shifted down by 4dp (blend Clear), blur 4dp, draw it with `alpha = progress`. Then draw the surface fill with alpha `clamp(progress, 0.15, 0.35)` on corner buttons, so the button looks slightly denser while held.

### 4.6 Non-glass press (flat rows and cards)
- Whole row background animates to `onSurface @ 8%` while held, back to transparent; tween **500ms**. No ripple. Shape = the row's/card's G2 shape.
- A "pulse" variant (same color, 500ms) highlights a row the user just returned to or just created.
- Material / Expressive mode buttons: `springSquish` → scale **0.92** (circle buttons), **0.95** (text buttons), **0.88** (small icon buttons), **0.985** (fields/composer); spring dampingRatio **0.5 (MediumBouncy)**, stiffness **200 (Low)**.

### 4.7 Haptics
- `CONTEXT_CLICK` on every tap of a button or row (fire before running the action).
- `LONG_PRESS` on long-press.
- A tick when a toggle crosses its midpoint while dragged, and on each slider step when stepped.

### 4.8 Coverage matrix

| Element | Glass | Press |
|---|---|---|
| Top bar background | progressive | — |
| Top bar corner buttons | Button | liquid (24dp) + inner shadow + glow |
| Title chip | — (sits on bar) | row-style 8% + 16dp shape |
| Composer | Composer | liquid (3dp) + glow |
| Composer attach / send | Button | liquid (24dp) |
| Scroll-to-bottom (40dp) | ScrollToBottom | liquid (4dp) |
| Glass text field | TextField | liquid (3dp) when unfocused/empty |
| Liquid capsule button | Liquid capsule | liquid (4dp) + glow |
| Dialog panel | Dialog | — (its buttons press) |
| Dialog buttons | Liquid capsule (tinted) | liquid (4dp) |
| Popover panel | Popover | liquid panel (6dp, damping 0.08) |
| Popover items | — | 8% press bg, 12% checked, 240ms |
| Sheet panel | Sheet | handle drag liquid |
| Sheet options | — | row press 8%, selected primaryContainer |
| Toggle thumb | turns to glass while held | scale up to 1.5×, inner shadow |
| Slider thumb | turns to glass while held | velocity squash/stretch |
| Settings rows / list rows | — | row press 8% / 500ms |
| Chat action icons (28dp) | — | squish 0.88 |
| Scrollbar thumb | — | widens 3.5 → 5dp when grabbed |

---

## 5. Overlay specs

### 5.1 Dialog
- Container: full-screen box in the overlay host, `imePadding`, content centered.
- Scrim: `scrim @ 42%`, tap dismisses (configurable).
- Panel: width `screen − 2×24dp`, **max 360dp, min 200dp**, max height screen − 48dp (scrolls inside). Shape **G2 48dp**. Dialog glass (§2.6). Padding **24dp**, children spaced **12dp**, actions row 4dp extra top padding, actions spaced 12dp.
- Title ~24sp Regular left-aligned onSurface; body bodyLarge onSurfaceVariant; code preview block `surfaceContainerHighest @ 60%`, 16dp G2, monospace 14sp, max ~8 lines scroll; warning line bodySmall `error`.
- Buttons: liquid capsules, equal weight, min height **64dp** (the extra 8dp over the 56dp capsule leaves room for the press swell). Primary: tint `primary`, text `onPrimary`. Secondary: tint `surfaceContainerHighest`, text `onSurface`. Label titleMedium centered.
- Motion: fade in **120ms Linear**, panel scale **1.04 → 1.0 in 240ms FastOutSlowIn**. Out: fade 180ms + scale → 1.04 in 180ms. It settles down onto the page, never pops from zero.
- Back key closes. Opening a dialog clears focus and hides the keyboard.

### 5.2 Popover / menu
- Anchored to a top-bar button or the title chip. Alignment: BottomEnd for right button, BottomStart for left, BottomCenter for title.
- **Morph**: progress 0→1 over **240ms FastOutSlowIn** (close 180ms). At 0 the panel is exactly the anchor button (same size, radius = half height, same position). Width, height lerp to the menu size; radius lerps to **20dp**. The panel's top edge stays at the anchor's top, end edge stays at the anchor's end (BottomEnd). So the menu grows **out of the button itself**, covering it.
- Content alpha: 0 until 25% progress, then linear to 1.
- Width 180–300dp, never covering the opposite corner button. Max height 256dp when scrollable (≈5.5 items, half item peeking).
- Glass: Popover preset. Panel liquid press (§4.3) when not scrollable.
- Items: 4×2dp outer inset, 16dp G2 highlight, padding 14×10dp, 10dp gap, 20dp leading icon onSurfaceVariant, bodyMedium label, optional labelMedium trailing hint @70%, 18dp check in `primary`. Pressed 8%, checked 12% (black in light, white in dark), animated 240ms LinearOutSlowIn. Divider 0.5dp outlineVariant @35%, 12×4dp inset.
- Dismiss: tap outside (global touch listener on ACTION_DOWN outside panel), back key. Opening clears focus/keyboard.

### 5.3 Sheet (glass bottom sheet, lives in overlay host)
- Scrim `scrim @ 32%`.
- Panel: full width, top corners **32dp G2**, bottom corners 0, Sheet glass. Bottom padding nav inset + 24dp. Max height 60–85% of screen, inner scroll.
- Handle: 36×4dp capsule `onSurfaceVariant @ 40%`, 12dp from top. Dragging the handle/panel follows the finger with rubber band above the open position (resistance factor 0.52, decreasing as you pull), dismisses if released below 30% height or with downward velocity; otherwise springs back (damping 1.0, stiffness 400).
- Enter: translate from 100% height + fade scrim, **300ms FastOutSlowIn**. Exit: 220ms. Selecting an option first animates the sheet out, then runs the action.
- Title: 18sp SemiBold `primary`, 24×8dp padding.
- Options: outer inset 12×4dp, 20dp G2 highlight, padding 16×12dp, min height 64dp, optional leading content with 16dp end gap, bodyLarge title. Selected: `primaryContainer` bg, `onPrimaryContainer` text + trailing check.

---

## 6. Controls

### 6.1 Liquid toggle
- Track **64×28dp** capsule; thumb **39×24dp** capsule (wide pill), 2dp inset. Track color animates `surfaceVariant` (off) ↔ `primary` (on); disabled `onSurface @ 12%`.
- Thumb at rest: solid white (thumb surface = `white × (1 − progress)`), shadow 4dp black 5%.
- When pressed/dragged: `progress` springs to 1 (damping 0.5, stiffness 300). Thumb scales up to **1.5×**, becomes **glass**: samples the page + track (combined backdrop), lens refraction 6dp / h÷2 with depth + chromatic, ambient highlight alpha = progress, inner shadow alpha = progress. Inside the thumb the backdrop is drawn at **0.7 scale** for a magnifier look.
- Drag maps finger to fraction with overshoot allowed to **−0.3…1.3**; release snaps to nearest side with the spring. Tap toggles. Haptic tick on crossing 0.5.
- Settings row variant: whole row tap toggles too.

### 6.2 Liquid slider
- Height 44dp touch area, track **6dp** capsule `surfaceContainerHighest`, filled part `primary` (or accent).
- Thumb **40×24dp**, 12dp radius. At rest: solid white (dark: `#AFAFAF`), blur 8dp on backdrop fades out as pressed.
- Pressed: thumb scales to **1.35×**, turns to glass (lens height 10dp × p, amount 14dp × p, ambient highlight at 1/1.5 width, inner shadow 4dp × p), shadow 4dp black 12% / 8%.
- **Velocity squash**: horizontal speed `v` springs (damping 0.5, stiffness 300); `scaleX /= 1 − clamp(v×0.75, −0.2, 0.2)`, `scaleY *= 1 − clamp(v×0.25, −0.2, 0.2)`. Scale springs: X damping 0.6 / stiffness 250, Y damping 0.7 / stiffness 250. Value spring damping 1 / stiffness 1000.
- Stepped sliders snap with haptic tick per step.

### 6.3 Segmented selector
- Inside a settings card: title on top, options row below, 16dp vertical padding, 12dp spacing. Options are capsules; selected = `primary @ 12%` fill + `primary` text; unselected = transparent + `onSurface`; disabled `onSurface @ 45%`. Selection pill slides between options (spring damping 0.8, stiffness 400).

### 6.4 Scrollbar and overscroll
- iOS-style auto scrollbar on the right edge: thumb **3.5dp** wide (5dp while grabbed), 3dp from edge, min length 36dp (18dp when squeezed by overscroll), color `white @ 42%` dark / `black @ 38%` light, capsule. Fades in 180ms on scroll, stays 850ms after stop, fades out 280ms. 32dp-wide invisible grab area allows scrubbing. Length changes spring (no bounce, medium stiffness).
- Overscroll: **rigid translate rubber band** (no stretch distortion). `delta × (1 − |offset| / (0.9 × viewport))` clamped to ≥ 0.12, × 0.52. On release: momentum zeroed, return with spring (no bounce, MediumLow stiffness). Fling into edge: enters the overscroll with 42% of velocity, returns with spring (no bounce, Low stiffness).

### 6.5 Swipe-to-delete row
- Left swipe on a settings row; reveal 22dp delete icon anchored 20dp from edge; commit threshold 40dp; beyond threshold movement is damped over 72dp. Progress animations 0ms while dragging, 120ms on release.

---

## 7. Color

### 7.1 Generation
- Build a Material 3 tonal scheme from one **seed** with palette style **TonalSpot** (MaterialKolor `dynamicColorScheme(seed, isDark, style = TonalSpot)`; Expressive style only for the M3 Expressive mode).
- No seed chosen and Android 12+ → system dynamic color.
- Seed presets: Rose `#FEB4A7`, Blossom `#FFB3C0`, Lavender `#FCAAFF`, Blue `#B9C3FF`, Sky `#62D3FF`, Cyan `#44D9F1`, Mint `#52DBC9`, Green `#78DD77`, Lime `#9FD75C`, Lemon `#C1D02D`, **Amber `#FABD00` (default)**, Apricot `#FFB86E`.
- The global dark flag comes from the app setting (light / dark / system), not only the system. System bars transparent, icons follow app theme, window background = `background`.

### 7.2 Approximate amber scheme (only if runtime generation is impossible)

| Role | Light | Dark |
|---|---|---|
| background / surface | `#FFF8F2` | `#17130B` |
| surfaceContainerLow | `#FBF2E7` | `#201B13` |
| surfaceContainer | `#F5ECE1` | `#241F17` |
| surfaceContainerHigh | `#EFE6DB` | `#2F2A21` |
| surfaceContainerHighest | `#E9E1D5` | `#3A342B` |
| primary | `#785A0B` | `#EAC16C` |
| onPrimary | `#FFFFFF` | `#3F2E00` |
| primaryContainer | `#FFDF9E` | `#5B4300` |
| onPrimaryContainer | `#261A00` | `#FFDF9E` |
| secondaryContainer | `#F4E0BB` | `#53452A` |
| tertiary | `#4E6542` | `#B4CEA5` |
| onSurface | `#1F1B13` | `#EBE1D4` |
| onSurfaceVariant | `#4D4639` | `#D0C5B4` |
| outlineVariant | `#D0C5B4` | `#4D4639` |
| error | `#BA1A1A` | `#FFB4AB` |

### 7.3 Role usage

| Element | Color |
|---|---|
| Page | `background` |
| Settings card, tool/thinking card | `surfaceContainerLow` |
| Pinned row | `secondaryContainer` |
| User bubble | `primary @ 18%` |
| Icon dots, selected segment, chips | `primary @ 12%` |
| Glass tints | §2.6 |
| Pressed row | `onSurface @ 8%` |
| Section titles | `onSurfaceVariant @ 80%` (settings) or `primary` (collection groups) |
| Secondary text, placeholders | `onSurfaceVariant @ 70–82%` |
| Timestamps | `tertiary` |
| Disabled | content `onSurface @ 38%`, container `@ 12%` |
| Dialog scrim | `scrim @ 42%`; sheet scrim `@ 32%` |

No pure white or black backgrounds. One solid `primary` element per view at most.

---

## 8. Shape

G2 continuous corners everywhere (Compose: `com.kyant.capsule` `G2Continuity` / `ContinuousCapsule`). Radius is clamped to half the shortest side.

| Radius | Use |
|---|---|
| Capsule | buttons, chips, toggles, sliders, tab bar, scrollbar |
| 16dp | popover items, title chip, code blocks |
| 20dp | popover panel (expanded), list rows, sheet options |
| 24dp | user bubble (tail corner 0dp, inner group corners 2dp) |
| 28dp | settings cards, content cards (default) |
| 32dp | sheet top corners |
| 36dp | composer, glass text field |
| 48dp | dialog |
| Circle | 48dp corner / action buttons, 40dp scroll-to-bottom, 56dp leading logos |

The more it floats, the bigger the radius.

---

## 9. Layout and spacing

- Screen gutters 16dp. Spacing scale `2 · 3 · 4 · 6 · 8 · 10 · 12 · 16 · 20 · 24 · 50` dp. Block rhythm 12dp, group/turn gap 24dp.
- Content top padding: status inset + 72dp (+24dp on list pages). Bottom padding: nav inset + **50dp**. Floating bottom action: nav inset + **24dp**.
- Top bar: 4dp horizontal padding; each corner button has 12dp padding around a 48dp (font-scaled `48.sp`) circle; title side padding `12dp × 2 + 48dp` so it never touches the buttons.
- Settings card: outer 16dp horizontal, 8dp vertical. Row padding start 20 / end 16 / top 16 / bottom 16. Slot gutter 12dp. Title 18sp (max 2 lines), summary 13sp/18sp (max 2 lines, 2dp indent), max 4 lines total. Icons 24dp, chevrons 24dp, circle logo 56dp with 24dp icon. Trailing preview 14sp, max 20% width.
- Collections: rows spaced 4dp, row padding 16×10dp.
- Chat: user bubble padding 18×12dp, consecutive bubbles 3dp gap, attachment↔bubble 8dp, bubble↔actions 6dp, user↔assistant 12dp, assistant blocks 12dp, assistant↔actions 6dp, turns 24dp. Tool card header inset 16dp collapsed / 12dp expanded, icon dot 24dp with 13dp icon, chevron 18dp, expanded body max 102dp scroll.
- Composer: min height 72dp, text padding 18×12dp.

---

## 10. Typography

System font. Body default 16sp / 24sp, letter spacing 0.5sp.

| Use | Style |
|---|---|
| Top-bar title | 20sp SemiBold |
| Title chip | 19sp SemiBold + 18dp ▾ (black/white @70%) |
| Dialog title | ~24sp Regular |
| Onboarding hero | 96sp Black |
| Settings row title | 18sp |
| Sheet title | 18sp SemiBold primary |
| Page description | 16sp Medium / 28sp |
| Chat assistant body | 17sp / 26sp (user-scalable) |
| Chat headings | h1 20sp SemiBold, h2–h3 SemiBold, h4–h6 Medium |
| Code | monospace 15sp / 22sp |
| List title | bodyLarge Medium |
| Summary / preview | 13–14sp onSurfaceVariant |
| Button label | titleMedium (liquid), labelLarge (material) |
| Meta | 11–12sp |

---

## 11. Motion

| Transition | Spec |
|---|---|
| Page push / pop | slide full width + fade, **360ms FastOutSlowIn**, direction-aware |
| Title swap | same direction, 320ms FastOutSlowIn |
| Top bar background | 280ms LinearOutSlowIn |
| Corner button appear/disappear | scale 0↔1 + fade, 280ms LinearOutSlowIn ("liquid condensation") |
| Top bar buttons occluded by an overlay | fade 200ms LinearOutSlowIn |
| Dialog | 120ms fade in, scale 1.04→1 240ms; out 180ms |
| Popover morph | 240ms / 180ms FastOutSlowIn |
| Sheet | 300ms in / 220ms out FastOutSlowIn |
| Scroll-to-bottom | fade 160ms in / 100ms out |
| Expand + chevron 0→90° | 320ms FastOutSlowIn |
| Row press | 500ms tween |
| Liquid press | spring 0.5 / 300 |
| Squish press | spring 0.5 / 200 |
| Chat block enter | fade (spring damping 1) + slide up 25% (spring 0.8, MediumLow) ; exit fade 80ms |
| Icon swap | in scale 1.18→1 + fade 220ms, out scale 0.78 + fade 180ms |

Nothing over 360ms except hero animations. No bounce on page transitions. With reduce-motion: fades only, keep haptics.

---

## 12. Implementation recipes

### 12.1 Jetpack Compose
Libraries: `com.kyant.backdrop` (glass: `rememberLayerBackdrop`, `layerBackdrop`, `drawBackdrop`, `rememberCombinedBackdrop`, effects `vibrancy/blur/lens/colorControls`, `Highlight`, `Shadow`), `com.kyant.capsule` (G2), `com.materialkolor` (seed scheme).

```kotlin
val LocalScreenBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable fun SuvoScreen(topBar: @Composable (Backdrop) -> Unit, content: @Composable () -> Unit) {
    val contentBackdrop = rememberLayerBackdrop()
    val chromeBackdrop = rememberLayerBackdrop()
    val screenBackdrop = rememberCombinedBackdrop(contentBackdrop, chromeBackdrop)
    val overlays = remember { OverlayHostState() }            // list of overlay composables
    CompositionLocalProvider(LocalScreenBackdrop provides screenBackdrop, LocalOverlayHost provides overlays) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop).background(MaterialTheme.colorScheme.background)) { content() }
            /* z2 */ TopBarBackground(contentBackdrop, exported = chromeBackdrop)
            /* z3 */ topBar(screenBackdrop)
            /* z4 */ Box(Modifier.fillMaxSize().zIndex(4f)) { overlays.entries.forEach { key(it.id) { it.content() } } }
        }
    }
}
```

Liquid interaction (copy exactly):
```kotlin
class InteractiveHighlight(private val scope: CoroutineScope) {
    private val pressSpec = spring(0.5f, 300f, 0.001f)
    private val posSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val press = Animatable(0f, 0.001f)
    private val pos = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero
    val pressProgress get() = press.value
    val offset get() = pos.value - start

    private val shader = if (Build.VERSION.SDK_INT >= 33) RuntimeShader("""
        uniform float2 size; layout(color) uniform half4 color; uniform float radius; uniform float2 position;
        half4 main(float2 c){ float d = distance(c, position); return color * smoothstep(radius, radius*0.5, d); }""") else null

    val modifier = Modifier.drawWithContent {
        val p = press.value
        if (p > 0f) {
            if (shader != null) {
                drawRect(Color.White.copy(0.08f * p), blendMode = BlendMode.Plus)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setColorUniform("color", Color.White.copy(0.15f * p).toArgb())
                shader.setFloatUniform("radius", size.minDimension * 1.5f)
                shader.setFloatUniform("position", pos.value.x.coerceIn(0f, size.width), pos.value.y.coerceIn(0f, size.height))
                drawRect(ShaderBrush(shader), blendMode = BlendMode.Plus)
            } else drawRect(Color.White.copy(0.25f * p), blendMode = BlendMode.Plus)
        }
        drawContent()
    }
    val gestureModifier = Modifier.pointerInput(scope) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Initial)
            start = down.position
            scope.launch { press.animateTo(1f, pressSpec) }; scope.launch { pos.snapTo(start) }
            var up = false
            while (!up) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (c.changedToUpIgnoreConsumed() || c.isConsumed) up = true else scope.launch { pos.snapTo(c.position) }
            }
            scope.launch { press.animateTo(0f, pressSpec) }; scope.launch { pos.animateTo(start, posSpec) }
        }
    }
}

data class LiquidStyle(val pressScale: Dp, val maxDragScale: Dp, val damping: Float = 0.05f, val glow: Boolean = true)

fun GraphicsLayerScope.liquidTransform(s: LiquidStyle, p: Float, o: Offset, size: Size) {
    if (size.minDimension <= 0f) return
    val w = size.width; val h = size.height
    val scale = lerp(1f, 1f + s.pressScale.toPx() / h, p)
    val m = size.minDimension
    translationX = m * tanh(s.damping * o.x / m); translationY = m * tanh(s.damping * o.y / m)
    val d = s.maxDragScale.toPx() / h; val a = atan2(o.y, o.x)
    scaleX = scale + d * abs(cos(a) * o.x / size.maxDimension) * min(w / h, 1f)
    scaleY = scale + d * abs(sin(a) * o.y / size.maxDimension) * min(h / w, 1f)
}
```

Glass element template (composer, field, dialog, popover, sheet all follow it):
```kotlin
Modifier.drawBackdrop(
    backdrop = LocalScreenBackdrop.current ?: rememberLayerBackdrop(),
    shape = { shape },
    effects = { vibrancy(); blur(preset.blur.toPx()); lens(preset.lensH.toPx(), preset.lensAmount(size), depthEffect = true, chromaticAberration = preset.chromatic) },
    highlight = { Highlight.Ambient.copy(alpha = preset.highlightAlpha) },
    shadow = { Shadow(radius = preset.shadowR, color = preset.shadowColor) },
    layerBlock = { liquidTransform(style, hl.pressProgress, hl.offset, size) },
    onDrawSurface = { tint?.let { drawRect(it, blendMode = BlendMode.Hue) }; drawRect(preset.surface) },
).clip(shape).then(hl.modifier).then(hl.gestureModifier)
```
Popover morph: measure the final menu with `SubcomposeLayout`, lerp width/height from anchor to target, radius from `anchorH/2` to 20dp, place at `(anchor.right − width, anchor.top)`.

### 12.2 Android XML + Java (Views)

**Structure**
```xml
<!-- activity root -->
<FrameLayout android:id="@+id/screenRoot" android:layout_width="match_parent" android:layout_height="match_parent">
    <FrameLayout android:id="@+id/contentRoot" ... />          <!-- z0: pages (blur source) -->
    <View android:id="@+id/topBarBg" ... />                   <!-- z2 -->
    <FrameLayout android:id="@+id/topBar" ... />              <!-- z3: two 48dp glass buttons + title -->
    <FrameLayout android:id="@+id/overlayHost" android:layout_width="match_parent" android:layout_height="match_parent"
        android:clickable="false" android:focusable="false" /> <!-- z4: dialogs/popovers/sheets go here -->
</FrameLayout>
```
- Show dialogs, popovers, sheets by inflating into `overlayHost`, never `AlertDialog`/`BottomSheetDialog`/`PopupWindow`. Handle back with `OnBackPressedCallback`.
- Glass blur: use **BlurView** (`com.github.Dimezis:BlurView`, 2.x) with the blur root = `contentRoot` (so it blurs pages, never itself or overlays). On API 31+ it uses `RenderEffect`; below, it falls back automatically. Wrap: `GlassFrameLayout` = BlurView (radius per preset) + an overlay `Drawable` doing Hue-like tint (`primaryContainer` at preset alpha, `PorterDuff.Mode.SCREEN` approximation) + surface fill + 1px top gradient highlight + `setClipToOutline(true)` with a squircle `ViewOutlineProvider` + `setElevation` with `setOutlineSpotShadowColor(black@20%)`.
- Squircle outline (API 30+ `outline.setPath`):
```java
public final class Squircle {
    /** Superellipse-ish continuous corner path. radius in px, smoothing 0.6 ≈ G2 look. */
    public static Path path(float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) / 2f);
        float k = r * 1.28f;                              // extended corner start for continuity
        k = Math.min(k, Math.min(w, h) / 2f);
        Path p = new Path();
        p.moveTo(k, 0); p.lineTo(w - k, 0);
        p.cubicTo(w - k * 0.36f, 0, w, k * 0.36f, w, k);
        p.lineTo(w, h - k);
        p.cubicTo(w, h - k * 0.36f, w - k * 0.36f, h, w - k, h);
        p.lineTo(k, h);
        p.cubicTo(k * 0.36f, h, 0, h - k * 0.36f, 0, h - k);
        p.lineTo(0, k);
        p.cubicTo(0, k * 0.36f, k * 0.36f, 0, k, 0);
        p.close(); return p;
    }
}
```
- **Liquid press for any View** (same physics as §4):
```java
public final class LiquidPress implements View.OnTouchListener {
    private final View v; private final float pressScalePx, maxDragScalePx, damping; private final boolean glow;
    private final FloatValueHolder holder = new FloatValueHolder(0f);
    private final SpringAnimation press;
    private SpringAnimation backX, backY;
    private float startX, startY, curX, curY, progress;
    private final GlowDrawable glowDrawable = new GlowDrawable();

    public LiquidPress(View v, float pressScaleDp, float maxDragScaleDp, float damping, boolean glow) {
        this.v = v; float d = v.getResources().getDisplayMetrics().density;
        this.pressScalePx = pressScaleDp * d; this.maxDragScalePx = maxDragScaleDp * d; this.damping = damping; this.glow = glow;
        press = new SpringAnimation(holder).setSpring(new SpringForce().setDampingRatio(0.5f).setStiffness(300f));
        press.setMinimumVisibleChange(0.001f);
        press.addUpdateListener((a, value, vel) -> { progress = value; apply(); });
        if (glow) v.setForeground(glowDrawable);
    }
    public static LiquidPress attach(View v, float pressScaleDp, float maxDragScaleDp) {
        LiquidPress lp = new LiquidPress(v, pressScaleDp, maxDragScaleDp, 0.05f, true); v.setOnTouchListener(lp); return lp;
    }
    @Override public boolean onTouch(View view, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (backX != null) { backX.cancel(); backY.cancel(); }
                startX = curX = e.getX(); startY = curY = e.getY();
                press.animateToFinalPosition(1f); break;
            case MotionEvent.ACTION_MOVE:
                curX = e.getX(); curY = e.getY(); apply(); break;
            case MotionEvent.ACTION_UP:
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                // fall through
            case MotionEvent.ACTION_CANCEL:
                press.animateToFinalPosition(0f);
                backX = springBack(true); backY = springBack(false); break;
        }
        return false; // never consume: click listener still fires
    }
    private SpringAnimation springBack(boolean x) {
        FloatValueHolder h = new FloatValueHolder(x ? curX : curY);
        SpringAnimation s = new SpringAnimation(h, x ? startX : startY)
            .setSpring(new SpringForce(x ? startX : startY).setDampingRatio(0.5f).setStiffness(300f));
        s.addUpdateListener((a, val, vel) -> { if (x) curX = val; else curY = val; apply(); });
        s.start(); return s;
    }
    private void apply() {
        float w = v.getWidth(), h = v.getHeight(); if (w <= 0 || h <= 0) return;
        float min = Math.min(w, h), max = Math.max(w, h);
        float ox = curX - startX, oy = curY - startY;
        float scale = 1f + (pressScalePx / h) * progress;
        v.setTranslationX(min * (float) Math.tanh(damping * ox / min));
        v.setTranslationY(min * (float) Math.tanh(damping * oy / min));
        double a = Math.atan2(oy, ox); float ds = maxDragScalePx / h;
        v.setScaleX(scale + ds * Math.abs((float) Math.cos(a) * ox / max) * Math.min(w / h, 1f));
        v.setScaleY(scale + ds * Math.abs((float) Math.sin(a) * oy / max) * Math.min(h / w, 1f));
        if (glow) glowDrawable.set(progress, curX, curY, min);
    }
    /** Additive white wash + radial glow under the finger. */
    static final class GlowDrawable extends Drawable {
        private final Paint wash = new Paint(), radial = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float p, x, y, min;
        GlowDrawable() { wash.setBlendMode(BlendMode.PLUS); radial.setBlendMode(BlendMode.PLUS); } // API 29+
        void set(float p, float x, float y, float min) { this.p = p; this.x = x; this.y = y; this.min = min; invalidateSelf(); }
        @Override public void draw(Canvas c) {
            if (p <= 0f) return; Rect b = getBounds();
            wash.setColor(Color.argb((int) (255 * 0.08f * p), 255, 255, 255)); c.drawRect(b, wash);
            float r = min * 1.5f, cx = Math.max(0, Math.min(x, b.width())), cy = Math.max(0, Math.min(y, b.height()));
            int col = Color.argb((int) (255 * 0.15f * p), 255, 255, 255);
            radial.setShader(new RadialGradient(cx, cy, r, new int[]{col, col, Color.TRANSPARENT}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(b, radial);
        }
        @Override public void setAlpha(int a) {} @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
```
Use: corner buttons `LiquidPress.attach(btn, 24f, 4.8f)`; capsule buttons `(4f, 4f)`; composer/fields `(3f, 3f)`; popover panel `new LiquidPress(panel, 6f, 6f, 0.08f, false)`.
Clip children to the squircle so the glow stays inside (`setClipToOutline(true)`).
- Row press: `ValueAnimator` of background color transparent ↔ `onSurface@8%`, 500ms, on DOWN/UP; `setForeground(null)`, no ripple.
- Material-mode squish: `SpringAnimation` on SCALE_X/Y to 0.92, `DAMPING_RATIO_MEDIUM_BOUNCY`, `STIFFNESS_LOW`.
- Transitions: fragment `setCustomAnimations` with slide 100% + fade, 360ms `FastOutSlowInInterpolator`; reverse for pop.
- Dialog in overlayHost: scrim view alpha 0→1 (120ms linear) + panel scale 1.04→1 (240ms FastOutSlowIn).
- Popover in overlayHost: animate panel `LayoutParams` width/height + outline radius from anchor rect to target over 240ms; children alpha after 25%.
- Top bar bg: vertical `GradientDrawable` (surfaceContainerHigh 100% at top → α65% at button middle → 0) above a BlurView with a matching mask; alpha 0↔1 driven by `RecyclerView.OnScrollListener` (280ms).
- Toggle/slider: custom views following §6 (thumb as white pill; on press animate scale and swap fill to a small BlurView pill).

### 12.3 Web / Vue / Telegram Mini App
```css
:root{
  --bg:#FFF8F2; --s-low:#FBF2E7; --s-high:#EFE6DB; --s-highest:#E9E1D5;
  --primary:#785A0B; --on-primary:#fff; --p-cont:#FFDF9E; --sec-cont:#F4E0BB;
  --tertiary:#4E6542; --on-s:#1F1B13; --on-s-var:#4D4639; --outline-var:#D0C5B4; --error:#BA1A1A;
  --ease:cubic-bezier(.4,0,.2,1); --ease-out:cubic-bezier(0,0,.2,1);
}
@media (prefers-color-scheme:dark){:root{--bg:#17130B;--s-low:#201B13;--s-high:#2F2A21;--s-highest:#3A342B;--primary:#EAC16C;--on-primary:#3F2E00;--p-cont:#5B4300;--sec-cont:#53452A;--tertiary:#B4CEA5;--on-s:#EBE1D4;--on-s-var:#D0C5B4;--outline-var:#4D4639;--error:#FFB4AB}}

.glass{ position:relative; isolation:isolate; overflow:hidden;
  -webkit-backdrop-filter:blur(var(--blur,16px)) saturate(1.5); backdrop-filter:blur(var(--blur,16px)) saturate(1.5);
  background:color-mix(in srgb,var(--s-high) var(--surf,64%),transparent);
  box-shadow:0 6px var(--shadow-r,20px) rgb(0 0 0/var(--shadow-a,.12)), inset 0 1px 0 rgb(255 255 255/.35), inset 0 0 0 .5px rgb(255 255 255/.18); }
.glass.tinted::before{ content:""; position:absolute; inset:0; z-index:-1; background:color-mix(in srgb,var(--p-cont) 32%,transparent); mix-blend-mode:hue; }
.glass-btn{ --blur:4px; width:48px; height:48px; border-radius:50%; background:rgb(255 255 255/.22); }
.composer{ border-radius:36px; min-height:72px; }
.dialog{ --blur:12px; --surf:76%; --shadow-r:16px; --shadow-a:.24; border-radius:48px; padding:24px; max-width:360px; }
.popover{ --blur:24px; background:rgb(255 255 255/.22); border-radius:20px; }
.liquid-glow{ position:absolute; inset:0; pointer-events:none; mix-blend-mode:plus-lighter; opacity:0; }
```
Liquid press in JS (same math):
```js
export function liquid(el,{pressScale=4,maxDrag=4,damping=0.05,glow=true}={}){
  let p=0,pv=0,target=0,sx=0,sy=0,x=0,y=0,tx=0,ty=0,raf=0;
  const g=glow?Object.assign(el.appendChild(document.createElement('div')),{className:'liquid-glow'}):null;
  const k=300,c=2*0.5*Math.sqrt(k);                       // damping 0.5, stiffness 300
  function step(){ const dt=1/60;
    pv+=(-k*(p-target)-c*pv)*dt; p+=pv*dt;
    if(target===0){ const fx=-k*(x-sx), fy=-k*(y-sy); tx+=(fx-c*tx)*dt; ty+=(fy-c*ty)*dt; x+=tx*dt; y+=ty*dt; }
    const r=el.getBoundingClientRect(), w=r.width/ (el._s||1), h=r.height/(el._s||1), mn=Math.min(w,h), mx=Math.max(w,h);
    const ox=x-sx, oy=y-sy, s=1+(pressScale/h)*p, a=Math.atan2(oy,ox), d=maxDrag/h;
    const sX=s+d*Math.abs(Math.cos(a)*ox/mx)*Math.min(w/h,1), sY=s+d*Math.abs(Math.sin(a)*oy/mx)*Math.min(h/w,1);
    el._s=s; el.style.transform=`translate(${mn*Math.tanh(damping*ox/mn)}px,${mn*Math.tanh(damping*oy/mn)}px) scale(${sX},${sY})`;
    if(g){ g.style.opacity=1; g.style.background=`radial-gradient(circle at ${x}px ${y}px, rgb(255 255 255/${.15*p}) 0, rgb(255 255 255/${.15*p}) ${mn*.75}px, transparent ${mn*1.5}px), rgb(255 255 255/${.08*p})`; }
    if(Math.abs(p-target)>0.001||Math.abs(pv)>0.001||Math.hypot(x-sx,y-sy)>0.5) raf=requestAnimationFrame(step); else raf=0; }
  const loc=e=>{const r=el.getBoundingClientRect();return [e.clientX-r.left,e.clientY-r.top]};
  el.addEventListener('pointerdown',e=>{[sx,sy]=loc(e);x=sx;y=sy;target=1;el.setPointerCapture(e.pointerId);if(!raf)raf=requestAnimationFrame(step)});
  el.addEventListener('pointermove',e=>{if(target)[x,y]=loc(e)});
  const end=()=>{target=0;navigator.vibrate?.(8);if(!raf)raf=requestAnimationFrame(step)};
  el.addEventListener('pointerup',end); el.addEventListener('pointercancel',end);
}
```
Telegram Mini App: use `Telegram.WebApp.HapticFeedback.impactOccurred('light')` instead of `navigator.vibrate`, and read `themeParams` only for dark/light, not colors (colors come from the seed).

### 12.4 Flutter
`ColorScheme.fromSeed(seedColor: Color(0xFFFABD00), dynamicSchemeVariant: DynamicSchemeVariant.tonalSpot)`; glass = `ClipPath(squircle) + BackdropFilter(ImageFilter.blur) + tint layers`; overlays in an `Overlay` of the same route (not `showDialog` with a new route if it breaks the backdrop); liquid press = `Listener` + two `SpringSimulation`s (damping ratio 0.5, stiffness 300) feeding a `Transform` with the §4.2 math; glow = `CustomPaint` with `RadialGradient` and `BlendMode.plus`.

---

## 13. Voice and density
- Short sentence-case copy: "Choose a Provider", "AI requests to run an operation", "Allow once", "Deny", "Just now", "15m ago".
- Errors and risks as `error`-colored text lines, never red banners.
- Settings are generous (16–20dp), chat is tighter (3–12dp).

## 14. Do / Don't
**Do:** let content scroll under every floating glass surface; render overlays inside the screen; morph popovers from their button; give every tappable thing liquid or row press plus haptic; keep the top bar to two buttons; keep dark mode warm.

**Don't:** use system dialogs/sheets/popup windows; use opaque floating surfaces; use ripples; use docked app bars or bottom bars; use 1dp dividers between rows; use plain rounded rectangles; put assistant text in bubbles; use more than one solid primary per view; exceed 360ms on routine transitions.

## 15. Final checklist
- [ ] One captured content layer per screen; all floating surfaces sample it.
- [ ] Dialog, popover and sheet are rendered in the screen's overlay host and visibly blur the page.
- [ ] Every glass surface uses its preset (blur, lens, Hue tint, surface, highlight, shadow).
- [ ] Every glass element has liquid press (spring 0.5/300, swell, lean, stretch, glow) with the right per-component style.
- [ ] Flat rows use 8% / 500ms press, no ripple; haptic on all taps.
- [ ] Top bar: two glass corner buttons, centered title, progressive blur only after scroll.
- [ ] Component choice follows §3 (dialog vs popover vs sheet vs segmented vs pushed page).
- [ ] G2 squircles with the radius table.
- [ ] Seed-based tonal colors, accents as low-alpha tints.
- [ ] Motion timings from §11, direction-aware navigation.
- [ ] Fallbacks for devices without blur keep shape, shadow and press.
