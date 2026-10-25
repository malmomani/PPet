# Maya image-generation prompts

The artwork was created using the built-in image-generation tool. Export assembly used deterministic nearest-neighbour scaling, a fixed 16-color palette and binary alpha.

## Initial atlas

Use case: stylized-concept.
Asset type: production pixel-art sprite atlas for Maya, a floating phone companion, based on the two attached reference images.
Image 1 is the character identity and costume reference. Image 2 is the pixel-art and motion reference; do not reproduce its labels, black background, irregular cell layout, or oversized sprites.
Create ONE genuinely transparent PNG sprite atlas, ideally EXACTLY 512 x 768 pixels, portrait ratio 2:3. The layout is EXACTLY 8 equal columns by 12 equal rows. Each logical frame is 64 x 64 pixels (if a larger image is necessary, scale every logical pixel uniformly by the same integer). No visible grid, cell borders, labels, numbers, text, contact-sheet decoration, shadows below feet, watermark, background or titles. Empty cells MUST be fully transparent. There are exactly 43 occupied cells; no artwork in unused cells. Maintain the grid rigorously.

Maya: cute curious mischievous chibi digital fairy, 2.5 heads tall, long deep indigo hair with violet highlights and straight bangs, red-pink ribbon on one side, huge cyan eyes with white highlights, small blush, white sleeveless bell-shaped dress, lavender shading, pink collar and hem trim, sparse cyan circuit lines along hem, short white boots, tiny cyan floating sparkle beside shoulder, sparse cyan hair rim lighting. No wings. Faithful to the supplied references. Charming coherent handcrafted 64x64 pixel-art sprites, crisp pixel clusters, 1 logical-pixel dark plum outline, no smooth illustration, no antialiasing. Each standing sprite occupies 44-48 logical pixels in height, centered at cell x32 with feet baseline y60, with generous clear space above. Same model, proportions, details and scale in every frame. Face toward screen RIGHT in all rows, a subtle three-quarter view for emotive poses so both eyes are visible; walk profile right. Do not mirror alternating frames. Keep consistent feet anchor for all grounded poses. Hops intentionally raise her. Use the following 16 colors only, plus full transparency: #241B3A #2B2350 #4B3F8C #1A1533 #6FF2FF #FFE3D3 #F2BFA8 #FF9EAA #5BC8F5 #1E3A5F #FFFFFF #F8F6FF #D9D2F0 #FF7FB0 #FF4F7A #9C4A6B.

Read rows from TOP to BOTTOM, 0 through 11. Occupied frames always start at the LEFTMOST cell, with no gaps. Keep all animation frames of a single row at exactly the same size and anchor.
Row 0 IDLE: 4 frames in columns 0-3; columns 4-7 empty. Standing neutral, tiny one-pixel breathing bob, blink with eyes closed in frame 2, eyes open in frame 3.
Row 1 WALK: 6 frames columns 0-5; 6-7 empty. Six distinct phases of a right-facing walk cycle, alternating front and back legs, opposite arm swing, hair and dress swaying subtly; feet at consistent baseline.
Row 2 SIT: 2 frames columns 0-1; 2-7 empty. Sitting on ground, legs extended forward/right, lower body and hair spread naturally, two subtly different sway poses. Keep correct sitting anatomy.
Row 3 NAP: 2 frames columns 0-1; 2-7 empty. Curled up sleeping on ground, eyes shut, tiny Z drifting up, second slightly different breathing pose.
Row 4 WAVE: 6 frames columns 0-5; 6-7 empty. Frame 0 natural standing; 1 raises arm; 2 hand waves outward; 3 waves inward; 4 waves outward again; 5 settles to comfortable arm-down smiling rest.
Row 5 PEEK: 4 frames columns 0-3; 4-7 empty. ONLY head and shoulders emerge from cell bottom: frame 0 top of hair visible at bottom; frame 1 forehead and eyes peek; frame 2 face emerges more; frame 3 head and shoulders visible at bottom. Cut at cell bottom deliberately; no full body in this row.
Row 6 HAPPY: 4 frames columns 0-3; 4-7 empty. Frame 0 slight anticipation crouch; 1 small hop arms raised and happy closed ^^ eyes; 2 landing; 3 comfortable standing delighted smile. Show actual height change, no cropped hair.
Row 7 SULK: 2 frames columns 0-1; 2-7 empty. Pouting puffed cheeks, head down, arms crossed, second natural resting sulk pose.
Row 8 SURPRISED: 2 frames columns 0-1; 2-7 empty. Frame 0 tiny startled jump, very round wide eyes and O mouth; frame 1 grounded surprised resting pose.
Row 9 TALK: 3 frames columns 0-2; 3-7 empty. Identical standing pose except mouth CLOSED, HALF OPEN, OPEN respectively, eyes lively, slight gesture allowed but no shape drift.
Row 10 LISTEN: 4 frames columns 0-3; 4-7 empty. Lean forward/right, hand cupped at ear, four gentle nod phases with minimal changes, attentive eyes.
Row 11 THINK: 4 frames columns 0-3; 4-7 empty. Finger on chin, eyes looking up/right, tiny cyan sparkle moves around her head in four positions.

Production priorities: exact 8x12 atlas, correct frame counts and occupied columns, consistent sprite scale and identity, distinct readable poses, crisp pixel edges, true transparent background, all empty cells transparent. Do NOT fill all eight columns. Do NOT insert an additional row. Make the entire atlas including the last THINK row visible.

## Motion correction sheet

Use case: stylized-concept. Production pixel-art motion corrections for Maya, matching the supplied character reference and the supplied generated atlas exactly in character identity, outfit, indigo hair, cyan eyes, red ribbon, white/pink/cyan dress and boots.
Create one transparent PNG with EXACTLY 6 columns and 4 rows of equal square cells, aspect ratio 3:2, no labels or grid lines. The first image is the identity reference; the second image is the existing atlas to MATCH visually. This is a NEW correction sheet with ONLY the four rows listed below, not the entire existing atlas. All sprites at the SAME scale, about 46 of64 logicalpixels tall in each cell, feet at local y60, centered x32. All character faces turned toward screen RIGHT, including the non-walking poses. Pixel art with large clean pixel clusters and hard un-antialiased edges, 1px darkplum outline, no smooth anime rendering. Actual transparent background, no colored gradients, no shadows, no borders, no text. Full standing body in every cell, except that unused cells remain completely EMPTY.

ROW 0 WALK: exactly SIX right-facing walking sprites. Six DISTINCT anatomical phases:
frame0 near leg extended FORWARD toward right, far leg back; near arm back.
frame1 near leg landing forward, far knee lifting/passing.
frame2 near leg planted vertical, far leg passing toward right.
frame3 FAR leg extended FORWARD toward right, NEAR leg extended BACK toward left; near arm forward.
frame4 far leg landing forward, near knee lifting/passing.
frame5 far leg planted vertical, near leg passing toward right.
It is ESSENTIAL that frames0 and3 have OPPOSITE near/far leg positions: do not repeat the same step six times. White boots each visible. Hair and dress sway subtly. Keep torso/head at same scale, baseline and position, so animation has no jitter. 
ROW 1 SULK: exactly TWO sprites in columns0 and1, columns2-5 transparent. Genuinely pouting, tiny downturned mouth, eyebrows lowered, cheeks puffed, arms visibly CROSSED against chest, head tilted down. Frame1 has a small sulky head tilt. Eyes open in both, no smile, no closed happy eyes.
ROW 2 TALK: exactly THREE sprites in columns0,1,2; columns3-5 transparent. Copy EXACTLY the same standing pose, head, eyes, hands and costume to all three cells; change ONLY mouth: frame0 mouth CLOSED, frame1 mouth HALFOPEN a small pink opening, frame2 mouth distinctly OPEN a vertical darkplum oval showing pink tongue. No talking gestures, no head pose changes. Mouth must read at64px.
ROW 3 LISTEN: exactly FOUR sprites in columns0-3; columns4-5 transparent. Attentive right-facing threequarter stance, leaning slightly toward right, one hand CLEARLY CUPPED NEXT TO THE EAR on side of head, palm facing right to catch sound. Hand must be level with the ear near temple/sidehair, not on cheek or chin. Tiny head nod across four poses. Mouth closed. Keep cup-to-ear silhouette recognizable.

Use only these16 colors plus transparency: #241B3A #2B2350 #4B3F8C #1A1533 #6FF2FF #FFE3D3 #F2BFA8 #FF9EAA #5BC8F5 #1E3A5F #FFFFFF #F8F6FF #D9D2F0 #FF7FB0 #FF4F7A #9C4A6B. Cyan sparkle beside shoulder in eachcell. All poses consistent, crisp real pixelart. Exactly15 occupiedcells, no extra frames.

## Thinking correction sheet

Use case: stylized-concept. Asset type: Maya THINK animation sprites, matching the attached character identity and pixel-art correction reference. Generate a square transparent PNG with exactly FOUR full-body pixel-art sprites arranged in a regular 2-column by 2-row grid of equal square cells. There is one sprite per cell. No extra sprites, no labels, no text, no numbers, no decorations, no visible grid or background. ACTUAL transparent background.
Most important: all FOUR sprites must show Maya's ENTIRE BODY, including the complete hem of her dress, BOTH LEGS and BOTH WHITE BOOTS with clear empty space beneath the boots. Do not crop any body part, hair, star or boots. Each sprite occupies about 46/64 of its square cell's height, grounded at local baseline60/64 with generous padding above and below. Consistent scale and anchor throughout.

Maya is the same cute chibi digital fairy in the references, facing screen RIGHT in a three-quarter view, long deepindigo hair with violet highlights and a red-pink ribbon, large cyan eyes with white highlights, tiny blush, sleeveless WHITE bell-shaped dress with lavender shadow, pink trim and a cyan circuit-line hem, short WHITE BOOTS, no wings. Ref1 establishes identity; ref2 establishes pixel-art style and proportions. Crisp 64x64-style real pixelart with hard pixel clusters, darkplum outline, no antialiasing, no smooth anime illustration. Use ONLY these16 colors: #241B3A #2B2350 #4B3F8C #1A1533 #6FF2FF #FFE3D3 #F2BFA8 #FF9EAA #5BC8F5 #1E3A5F #FFFFFF #F8F6FF #D9D2F0 #FF7FB0 #FF4F7A #9C4A6B, plus full transparency.

All four sprites THINK: standing full-body, one index finger rests on CHIN, other arm relaxed, mouth closed, eyes look up and toward RIGHT, slightly furrowed curious brows. Pose almost identical across frames with only subtle head tilt and a cyan sparkle circling around head. Top-left frame sparkle to upper-left of head. Top-right sparkle directly above head. Bottom-left sparkle to upper-right. Bottom-right sparkle beside right shoulder. Sparkle tiny 2-3 logicalpixels, no punctuation. Keep body and both boots at identical positions in all four frames. Make the last row fully visible with sufficient bottom margin.

