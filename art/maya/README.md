# Maya sprite pack

Maya is a right-facing digital-fairy companion based on the supplied character reference and design brief.

## Files

- **maya_sprites.png**: native production atlas, 512 x 1344 px, 32-bit RGBA; 21 animations and 72 frames.
- **sprites.json**: animation names, row numbers, frame counts, timing, loop behavior, crop rectangles, anchor and palette.
- **maya_source.psd**: editable Photoshop document with 72 named raster frame layers, each positioned in the atlas.
- **maya_preview.gif**: animated overview of all 21 states, displayed at integer 2x scale. The overview restarts every four seconds.
- **maya_new_poses_preview.gif**: animated overview of the nine appended states at integer 3x scale.
- **maya_sprites_4x.png**: enlarged nearest-neighbour atlas for visual inspection. Use the native atlas in the app.
- **frames/**: all 72 frames as individual 64 x 64 transparent PNGs.
- **maya_palette.gpl**: the brief's exact 16 colors, importable into compatible pixel-art editors.
- **validation.json**: format, palette, alpha, occupancy and timing checks.
- **source/**: supplied character reference, generated artwork and the image-generation prompts.

## Atlas contract

The atlas has eight columns and twenty-one rows. Every cell is 64 x 64 px.
Frames run left to right from column 0. All unused cells are fully transparent.
The coordinate origin is the top-left corner; x increases to the right and y increases downward.
The nominal body/feet anchor is x = 32, y = 60 in every cell.
Sitting, sleeping, peeking and jumping intentionally change the visible body position.
The peek frames emerge through the bottom edge of their cell.
Mirror horizontally when moving left. Use nearest-neighbour scaling and disable image smoothing.

| Row | Animation | Frames | FPS | Loop |
| --- | --- | --- | --- | --- |
| 0 | idle | 4 | 4 | Yes |
| 1 | walk | 6 | 8 | Yes |
| 2 | sit | 2 | 2 | Yes |
| 3 | nap | 2 | 1 | Yes |
| 4 | wave | 6 | 8 | No; hold last |
| 5 | peek | 4 | 6 | No; hold last |
| 6 | happy | 4 | 8 | No; hold last |
| 7 | sulk | 2 | 2 | No; hold last |
| 8 | surprised | 2 | 6 | No; hold last |
| 9 | talk | 3 | 8 | Yes |
| 10 | listen | 4 | 4 | Yes |
| 11 | think | 4 | 4 | Yes |
| 12 | jump | 4 | 8 | No; hold last |
| 13 | fall | 2 | 6 | Yes |
| 14 | perch | 2 | 2 | Yes |
| 15 | poke | 4 | 6 | Yes |
| 16 | tease | 4 | 6 | Yes |
| 17 | look | 4 | 4 | Yes |
| 18 | ask | 3 | 4 | Yes |
| 19 | type | 4 | 8 | Yes |
| 20 | held | 2 | 4 | Yes |

All frame counts, FPS values and loop flags follow the supplied brief.
The native atlas uses the specified 16 colors, plus full transparency.
There are no semi-transparent pixels or embedded ICC color profile.

Rows 0-11 retain every original RGBA pixel. All 43 original individual frame PNGs
also retain their original file bytes. Twenty-nine new frames occupy rows 12-20.

The new poses use the anchor rules in MISSING_POSES_BRIEF.md:
grounded frames contact y = 60, perch seats at y = 59 with short boot tips below,
and held frames hang from the ribbon near (32, 6) with their boots above ground.
Jump translation is handled by the app; the frames contain the body motion.
The held ribbon is pulled upward to act as the grab point, with one ribbon per frame.

The previews sample the animation timelines at 24 fps so both 6 fps and 8 fps
states can be displayed. GIF time is quantized to centiseconds; sprites.json
contains the authoritative playback FPS values.

Run the independent asset checks from the project root:

    python3 art/maya/verify_assets.py

## Loading sprites.json

The JSON is a self-contained atlas manifest, without a dependency on a particular game engine.
Each animation has row, frames, fps, loop, onComplete and cells fields.
Here, frames is the numeric frame count, while cells contains the crop rectangles.
Adapt these keys to your app's loader if its schema differs.

For example, in a canvas renderer:

    const animation = manifest.animations[state];
    const step = Math.floor(elapsedMs * animation.fps / 1000);
    const index = animation.loop
      ? step % animation.frames
      : Math.min(step, animation.frames - 1);
    const cell = animation.cells[index];
    context.imageSmoothingEnabled = false;
    context.drawImage(
      spriteImage,
      cell.x, cell.y, cell.w, cell.h,
      anchorX - 32 * scale, anchorY - 60 * scale,
      64 * scale, 64 * scale
    );

Reset elapsedMs whenever the animation changes.
For voice playback, select talk while speaking, listen while receiving speech,
and think while waiting for a response.

## Editable source and provenance

The artwork was created with AI image generation from the supplied references,
then isolated, resized with nearest-neighbour sampling, mapped to the specified palette,
and assembled into the exact atlas geometry.
The PSD contains raster frame layers for editing; it is not a hand-pixelled Aseprite project.
No separate commissioned-artist exclusivity agreement is included.

The native PNG and JSON have been checked for their technical file contract.
Integration into the phone app has not been tested because app source code was not supplied.
The two Android asset copies are provided under android/app/src/main/assets/sprites/.
No Android application or Gradle project is included, so android/core Gradle tests
could not be run. The asset verification script checks both copies.
