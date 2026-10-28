# Maya — missing poses brief (rows 12–20)

These are **all the animation rows Maya still needs**. Rows 0–11 already exist and stay as they are:
idle, walk, sit, nap, wave, peek, happy, sulk, surprised, talk, listen, think.

Until a row exists, the app draws a stand-in from a row it already has. Delivering a row replaces its
stand-in with no code change needed.

| Row | Animation | Used when | Stand-in until delivered |
| --- | --- | --- | --- |
| 12 | jump | jumping between buttons and edges | surprised |
| 13 | fall | the edge under her vanished, or she was dropped | surprised |
| 14 | perch | sitting on a button edge, legs dangling | sit |
| 15 | poke | poking a button (tease, or pressing it for the user) | wave |
| 16 | tease | sitting on a button to cover it, tongue out | happy |
| 17 | look | studying a picture on the screen (vision) | think |
| 18 | ask | asking "Can I press Send?" and waiting for ✔ / ✖ | listen |
| 19 | type | typing text into a field for the user | poke, then wave |
| 20 | held | being carried or dragged by the user | surprised |

Rows 12–16 are wired in the app already. Rows 17–20 are wired in the vision and phone-actions build
that is in progress now.

## What stays the same

- **Cells:** 64 × 64 px, 8 columns. Frames start in column 0 with no gaps. Unused cells are fully transparent.
- **Rows 0–11:** unchanged, pixel for pixel. The new rows are appended below them.
- **Character:** the same Maya at the same scale. She is about 2.5 heads tall and a standing pose is
  44–48 px high. She has the same hair, ribbon, dress, boots and sparkle, and a 1 px dark plum
  outline.
- **Palette:** the same 16 colours (`maya_palette.gpl`) plus full transparency. Alpha is binary
  (0 or 255) and there is no ICC profile.
- **Facing:** every frame faces screen right, using a three-quarter view for emotive poses. The app
  mirrors her when she faces left, so do not mirror any frames.
- **Anchor:** (32, 60) in every cell. The app puts pixel row 60 on the edge she stands on and centres
  column 32 on her position. Grounded poses have their feet on y = 60. Nothing below y = 63 is shown.
- **Scaling:** nearest-neighbour, with no antialiasing or soft shadows.

## Atlas size

| | Now | After |
| --- | --- | --- |
| Rows | 12 (0–11) | 21 (0–20) |
| PNG size | 512 × 768 | 512 × 1344 |
| Occupied frames | 43 | 72 |

The app's sprite test requires the PNG height to be exactly 64 × (highest row + 1).

- **All nine rows:** the height is 64 × 21 = 1344.
- **Partial delivery:** you may deliver the rows in order, for example only 12–16 first (height
  1088). Don't skip rows.

## The rows

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

### Row 17 — `look` (4 frames, 4 fps, loop)

**Vision.** She is studying the picture on the screen: a photo, a video thumbnail or a post. She
plays this while the vision model looks at a screenshot, which takes about 3–15 s.

- She stands on her edge with her feet on y = 60, leaning forward slightly toward the right, which is
  the screen she's looking at.

0. **Peer:** one hand shading her eyes like a visor, eyes narrowed with curiosity, mouth a small line.
1. **Lean in:** the body 1 px further forward, eyes wide, a tiny cyan sparkle near her eye.
2. **Hmm:** a finger tapping her chin, eyes looking up-right, head tilted 1 px.
3. **"Ooh!":** a small open-mouth smile, eyebrows up and the hand dropping. This loops back to 0.

### Row 18 — `ask` (3 frames, 4 fps, loop)

**Asking permission.** She shows this while her bubble asks "Can I press Send?" with ✔ and ✖, for
up to 15 s.

- She stands with her feet on y = 60, facing the viewer in three-quarter view, and looks hopeful and
  a little sheepish.

0. **Please?:** hands clasped under her chin, big eyes, head tilted 1–2 px toward the viewer.
1. **Bounce:** the same pose 1 px lower, toes lifting, a tiny pink blush on her cheeks (#FF9EAA).
2. **Waiting:** fingers tapping together, eyes glancing between the viewer and the button
   (down-right).

### Row 19 — `type` (4 frames, 8 fps, loop)

**Typing for the user.** She shows this while she types text into a field, for about 1–3 s.

- She stands or kneels on the field's top edge with her feet on y = 60, typing on a tiny glowing
  cyan keyboard (#6FF2FF) floating in front of her at about y = 50–56, x = 36–52.
- Her hands alternate. Show 1–2 px key "pops" as small cyan dots above the keys.

0. **Left tap:** left hand down and right hand up, eyes on the keys, focused.
1. **Right tap:** the hands swap and a key-pop sparkle appears.
2. **Left tap:** like frame 0, with her tongue poking out of the corner of her mouth in concentration.
3. **Right tap:** like frame 1, with a small proud smile.

### Row 20 — `held` (2 frames, 4 fps, loop)

**Being carried.** She shows this while the user drags her around, and before a throw. The finger
holds her by the scruff or the ribbon.

- She hangs from the top of the cell like a picked-up kitten. Draw the grab point (the back of her
  collar or the ribbon) at about (32, 6). Her body dangles below it, and her feet are around
  y = 52–58, not on the ground.
- She looks surprised but not scared: puffed cheeks, arms crossed or limp, legs swinging.

0. Legs swung forward, arms crossed, a pout.
1. Legs swung back, one arm flailing, eyes squeezed shut. A 1 px sway of the body.

## Timing summary

| Row | Animation | Frames | FPS | Loop | onComplete | totalDurationMs |
| --- | --- | --- | --- | --- | --- | --- |
| 12 | jump | 4 | 8 | No | hold | 500 |
| 13 | fall | 2 | 6 | Yes | repeat | 333.333 |
| 14 | perch | 2 | 2 | Yes | repeat | 1000 |
| 15 | poke | 4 | 6 | Yes | repeat | 666.667 |
| 16 | tease | 4 | 6 | Yes | repeat | 666.667 |
| 17 | look | 4 | 4 | Yes | repeat | 1000 |
| 18 | ask | 3 | 4 | Yes | repeat | 750 |
| 19 | type | 4 | 8 | Yes | repeat | 500 |
| 20 | held | 2 | 4 | Yes | repeat | 500 |

## `sprites.json` changes

Change these top-level fields (the values are for all nine rows):

```json
  "height": 1344,
  "rows": 21,
  "frameCount": 72,
```

Then append these entries to `"animations"`, after `"think"`:

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
    },
    "look": {
      "row": 17,
      "frames": 4,
      "fps": 4,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 1088,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 1088,
          "w": 64,
          "h": 64
        },
        {
          "x": 128,
          "y": 1088,
          "w": 64,
          "h": 64
        },
        {
          "x": 192,
          "y": 1088,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 1000
    },
    "ask": {
      "row": 18,
      "frames": 3,
      "fps": 4,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 1152,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 1152,
          "w": 64,
          "h": 64
        },
        {
          "x": 128,
          "y": 1152,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 750
    },
    "type": {
      "row": 19,
      "frames": 4,
      "fps": 8,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 1216,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 1216,
          "w": 64,
          "h": 64
        },
        {
          "x": 128,
          "y": 1216,
          "w": 64,
          "h": 64
        },
        {
          "x": 192,
          "y": 1216,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 500
    },
    "held": {
      "row": 20,
      "frames": 2,
      "fps": 4,
      "loop": true,
      "onComplete": "repeat",
      "cells": [
        {
          "x": 0,
          "y": 1280,
          "w": 64,
          "h": 64
        },
        {
          "x": 64,
          "y": 1280,
          "w": 64,
          "h": 64
        }
      ],
      "totalDurationMs": 500
    }
```

The app reads `row`, `frames`, `fps` and `loop`, and the `anchor` object for the feet row. The other
fields are for other tools.

## Deliverables

Update all of these together, the same way as rows 0–11:

- **Atlas:** `art/maya/maya_sprites.png`, 512 × 1344. Copy it to `android/app/src/main/assets/sprites/maya.png`.
- **Manifest:** `art/maya/sprites.json`. Copy it to `android/app/src/main/assets/sprites/sprites.json`.
- **Frames:** 29 new frames in `art/maya/frames/`, named `jump_00.png` … `held_01.png`.
- **PSD:** new named layers in `art/maya/maya_source.psd`.
- **Previews:** regenerate `maya_preview.gif` and `maya_sprites_4x.png`, and regenerate `validation.json`.
- **README:** add rows 12–20 to the table in `art/maya/README.md`.

## Acceptance checks

- **Format:** the PNG is RGBA with only the 16 palette colours, binary alpha and no ICC profile.
- **Layout:**
  - Rows 0–11 are byte-identical to the current atlas.
  - New rows start at column 0, and unused cells are transparent.
  - The PNG height is 64 × (highest row + 1).
- **Anchor:**
  - Grounded rows (jump frames 0 and 3, fall, perch, poke, look, ask, type) keep their contact point on y = 60 at x ≈ 32.
  - `tease` covers the cell centre.
  - `held` hangs from about (32, 6).
- **Mirroring:** no frame is mirrored, and every frame faces right.
- **Tests:** `cd android/core && gradle test` passes.
