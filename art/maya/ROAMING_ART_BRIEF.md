# Maya roaming art brief (rows 12–16)

Maya now roams the whole phone screen. She jumps between the edges of buttons and cards, falls when
the edge under her goes away, perches on edges, and teases the user by sitting on or poking buttons
such as Send and Like. This brief asks for five new animation rows in the existing pack. They use the
same character, grid, palette and conventions as rows 0–11 (see `README.md` and
`source/generation_prompts.md`).

Until these rows exist, the app draws stand-ins from rows it already has:

| New row | Stand-in |
| --- | --- |
| jump | surprised |
| fall | surprised |
| perch | sit |
| poke | wave |
| tease | happy |

Delivering the rows replaces the stand-ins. No code change is needed.

## What stays the same

- **Cells:** 64 × 64 px, 8 columns. Frames start in column 0 with no gaps. Unused cells are fully transparent.
- **Rows 0–11:** unchanged, pixel for pixel. The new rows are appended below them.
- **Character:** the same Maya, at the same scale and proportions. She is about 2.5 heads tall and a
  standing pose is 44–48 px high. She has the same hair, ribbon, dress, boots and sparkle, and a
  1 px dark plum outline.
- **Palette:** the same 16 colours (`maya_palette.gpl`) plus full transparency. Alpha is binary
  (0 or 255) and there is no ICC profile.
- **Facing:** every frame faces screen right, using a three-quarter view for emotive poses. The app
  mirrors her when she faces left, so do not mirror any frames.
- **Anchor:** (32, 60) in every cell, the same as now. The app puts pixel row 60 on the edge she
  stands on, and centres column 32 on her position. Draw grounded poses with their feet on y = 60.
  Nothing below y = 63 is shown.
- **Scaling:** nearest-neighbour, with no antialiasing or soft shadows.

## What changes in the atlas

| | Now | After |
| --- | --- | --- |
| Rows | 12 (0–11) | 17 (0–16) |
| PNG size | 512 × 768 | 512 × 1088 |
| Occupied frames | 43 | 59 |

The app's sprite test requires the PNG height to be exactly 64 × (highest row + 1), which is 1088.

## The new rows

### Row 12 — `jump` (4 frames, 8 fps, no loop, hold last)

She jumps from one edge to another along an arc. The app moves the whole cell along the arc, so
draw the body motion only and do not raise her inside the cell. A jump takes 400–900 ms, so the
last frame is held for the rest of a long jump.

0. **Crouch:** knees bent, arms back, feet on y = 60, eyes on the landing spot ahead and up-right.
1. **Push-off:** body stretched up, arms swinging forward and up, toes just leaving y = 60, hair and
   dress trailing down.
2. **Apex tuck:** knees pulled up, arms out for balance, hair and dress floating, an excited open smile.
3. **Reach to land:** legs extended down with the boots at about y = 58–60, arms slightly up and the
   dress flaring. This frame is held until she lands.

### Row 13 — `fall` (2 frames, 6 fps, loop)

The edge under her vanished (the app scrolled or closed) or she was dropped. A fall can last more
than a second, so this row loops.

0. **"Eep!":** both arms flung up, hair and dress blown upward, eyes wide, mouth a small "o", legs
   slightly apart.
1. The same pose, flailing: arms at a different angle, the dress and hair edges shifted 1–2 px,
   and one leg bent.

Keep the boots near y = 60, because the app lands her by the anchor.

### Row 14 — `perch` (2 frames, 2 fps, loop)

She sits on the top edge of a button or card, with her legs dangling over the front of it. The app
holds this pose for 5–15 s.

- Her seat rests on y = 59, so the edge is at y = 60.
- Her lower legs hang over the edge in front of her (toward the right). The cell only has rows
  60–63 below the edge, so keep the dangling legs short and chibi: knees at the edge, shins
  angled forward and down, boots at y ≈ 61–63. Do not draw anything below y = 63.
- She looks relaxed and content, with her hands on the edge beside her.

0. Left leg swung forward, right leg back.
1. Legs swapped, with a 1 px head tilt.

### Row 15 — `poke` (4 frames, 6 fps, loop)

She stands on top of a button (a Send or Like button) and pokes it with one finger, like a cat
batting at something. The app plays this row for about 2.5 s, which is just under four loops. The
button is under her feet, so the poke goes down and forward to the surface at about
x = 38–44, y = 58–60.

0. **Lean in:** body bent forward over the button, index finger out, a mischievous grin and eyes
   looking down.
1. **Tap:** the finger touches the surface at about (42, 59), with a tiny 1–2 px cyan sparkle at the
   fingertip.
2. **Pull back:** the finger lifts and she glances toward the viewer with a cheeky look.
3. **Second tap:** the finger down again, a slightly bigger grin, the hair bounced 1 px.

### Row 16 — `tease` (4 frames, 6 fps, loop)

She sits right on a button to cover it and sticks her tongue out at the user. The app centres the
cell on the button, so the cell centre (32, 32) lands on the button's centre. Her body must cover
the middle of the cell.

- Draw a seated pose built like the `sit` row (row 2), with her seat on y ≈ 58–60 and her body
  filling about y = 20–60.
- She stays for 4–8 s. A tap on her switches to `sulk` (row 7), then she hops off.

0. **Smug sit:** arms crossed or hands on her knees, chin up.
1. **"Bleh":** tongue out (#FF7FB0 / #FF4F7A), one eye squeezed shut, head tilted toward the viewer.
2. **Giggle:** a hand over her mouth, shoulders up, eyes curved like happy arcs.
3. **Giggle bounce:** like frame 2, with the body 1 px lower and a tiny cyan sparkle near her head.

## Timing summary

| Row | Animation | Frames | FPS | Loop | onComplete | totalDurationMs |
| --- | --- | --- | --- | --- | --- | --- |
| 12 | jump | 4 | 8 | No | hold | 500 |
| 13 | fall | 2 | 6 | Yes | repeat | 333.333 |
| 14 | perch | 2 | 2 | Yes | repeat | 1000 |
| 15 | poke | 4 | 6 | Yes | repeat | 666.667 |
| 16 | tease | 4 | 6 | Yes | repeat | 666.667 |

## `sprites.json` changes

Change these top-level fields:

```json
  "height": 1088,
  "rows": 17,
  "frameCount": 59,
```

Then append these entries to `"animations"`, after `"think"`. They follow the same style as the
existing entries:

```json
    "jump": {
      "row": 12,
      "frames": 4,
      "fps": 8,
      "loop": false,
      "onComplete": "hold",
      "cells": [
        {
          "x": 0,
          "y": 768,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 768,
          "w": 64,
          "h": 64
        },
        {
          "x": 128,
          "y": 768,
          "w": 64,
          "h": 64
        },
        {
          "x": 192,
          "y": 768,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 500
    },
    "fall": {
      "row": 13,
      "frames": 2,
      "fps": 6,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 832,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 832,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 333.333
    },
    "perch": {
      "row": 14,
      "frames": 2,
      "fps": 2,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 896,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 896,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 1000
    },
    "poke": {
      "row": 15,
      "frames": 4,
      "fps": 6,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 960,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 960,
          "w": 64,
          "h": 64
        },
        {
          "x": 128,
          "y": 960,
          "w": 64,
          "h": 64
        },
        {
          "x": 192,
          "y": 960,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 666.667
    },
    "tease": {
      "row": 16,
      "frames": 4,
      "fps": 6,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 1024,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 1024,
          "w": 64,
          "h": 64
        },
        {
          "x": 128,
          "y": 1024,
          "w": 64,
          "h": 64
        },
        {
          "x": 192,
          "y": 1024,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 666.667
    }
```

The app reads `row`, `frames`, `fps` and `loop`, and the `anchor` object for the feet row. The
other fields are for other tools.

## Deliverables

Update all of the following together, the same way as rows 0–11:

- **Atlas:** `maya_sprites.png`, 512 × 1088. Copy it to `android/app/src/main/assets/sprites/maya.png`.
- **Manifest:** `sprites.json`, with the changes above. Copy it to `android/app/src/main/assets/sprites/sprites.json`.
- **Frames:** 16 new frames in `frames/`, named `jump_00.png` … `tease_03.png`.
- **PSD:** new named layers in `maya_source.psd`.
- **Previews:** regenerate `maya_preview.gif` and `maya_sprites_4x.png`.
- **Validation:** regenerate `validation.json` with the same checks.
- **README:** add rows 12–16 to the table in `README.md`, and update the atlas size (eight columns by seventeen rows).

## Acceptance checks

- **Format and palette:** the PNG is RGBA with only 16 visible colours from the palette, binary
  alpha, and no ICC profile.
- **Atlas layout:**
  - Rows 0–11 are byte-identical to the current atlas.
  - Every new row's occupied cells start at column 0, and every other cell is fully transparent.
- **Anchor:** `jump`, `fall`, `perch` and `poke` keep their contact point on y = 60 at x ≈ 32.
  `tease` covers the cell centre.
- **No mirroring:** no frame is mirrored, and all of them face right.
- **App tests:** `cd android/core && gradle test` passes. `BundledSpritesTest` checks that the PNG
  height matches the highest row.
- **On the phone:**
  - A jump shows the crouch, then the push-off, then the tuck, then the reach.
  - A fall loops the flail.
  - She perches on edges with her legs dangling.
  - She pokes the Send button and sticks out her tongue while sitting on it.
