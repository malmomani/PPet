# Maya: missing-pose generation prompts

Artwork generated with the built-in image-generation tool. Deterministic export then applied nearest-neighbour scaling, the original 16-color palette, binary alpha and the required cell anchors. The original 12 rows were copied without changing any pixel.

## mayaNewA

Use case: stylized-concept. Create NEW production pixel-art animation frames for Maya, the SAME digital-fairy character as the two supplied references. Image1 is the existing native sprite artwork enlarged with nearest-neighbour, for pixel style/identity/scale consistency. Image2 is the supplied original character design, for costume and identity. DO NOT redraw the existing atlas. Create ONLY the new sheet described below.
Art style: crisp genuine 64x64 pixel art scaled up by an integer for visibility, clean deliberate pixel clusters, 1 logicalpixel darkplum outline, NO antialiasing, no smooth anime illustration. Chibi about 2.5 heads tall. Standing character46 logicalpixels high. Deepindigo longhair with violet highlights and straightbangs, one red-pink ribbon, large cyan eyes with white highlights, blush, white sleeveless bell dress with lavender shadow andpinktrim andcyan circuit hem, shortwhiteboots, tiny cyan floating sparkle, no wings. Every pose faces screen RIGHT, threequarter view for emotion. All frames have the SAME model, scale, hair length, proportions and outfit. No alternating left-facing poses.
Format: ONE transparent PNG, portrait/square-ish 4:3 aspect ratio, exactly FOUR equal columns by THREE equal rows of SQUARE cells, no gutters. Each cell is logically64x64. All frames LEFT ALIGNED in each row startingcolumn0. Generous clear space separating sprites. No drawn grid, no labels, no numbering, no text, no button/surface/platform drawings, no floor shadows, no backgrounds. Empty cells must be COMPLETELY EMPTY/transparent. Actual transparency.
Use only the16 colors #241B3A #2B2350 #4B3F8C #1A1533 #6FF2FF #FFE3D3 #F2BFA8 #FF9EAA #5BC8F5 #1E3A5F #FFFFFF #F8F6FF #D9D2F0 #FF7FB0 #FF4F7A #9C4A6B, plus transparency.
Logical anchor x32 y60 in each cell. Grounded feet at y60, torso centered x32. Do not crop hair, hands, dress or feet.

SHEET A: exactly EIGHT sprites.
ROW0 JUMP: FOUR distinct frames, columns0-3. The app moves the whole sprite along an arc, so keep the full body in the same anchored area rather than raising it inside the cell.
Frame0 crouch, knees bent, both feet on y60, arms back, gaze up-right on landing spot.
Frame1 push-off, body stretches up, arms swing forward/up, toes just leaving y60, hair and dress trail down.
Frame2 apex tuck, knees drawn up toward torso, arms out balancing, hair and dress float, excited open smile. Full model same scale; tuck legs rather than shifting the whole sprite up.
Frame3 reaching down to land, both legs extend down, boots at y58-60, arms slightly up, dress flaring; held natural landing-ready pose.
ROW1 FALL: TWO distinct frames in columns0,1 only; columns2,3 completely transparent. Frame0 both arms FLUNG HIGH UP, hair and dress blown upward, wide eyes, little O mouth, legs slightly apart. Frame1 flailing variation, arms different angles, one knee bent, hair/dress shifted slightly. Keep boots near y60 in BOTH frames. This must look like falling down, not ordinary surprise or happy jumping.
ROW2 PERCH: TWO distinct frames columns0,1 only; columns2,3 transparent. Sitting on an INVISIBLE button edge. Very important geometry: seat at localy59, knees at y60, SHORT dangling chibi shins angle FORWARD/RIGHT, boots at y61-63, nothing below63. Body above seat from y21 to59, relaxed content face, hands resting either side of seat at edge y59. Frame0 leftleg forward/rightleg back. Frame1 swap legs, tiny head tilt. Unlike sitting on the ground, these boots dangle LOWER than the seat. Do NOT draw the supporting button or edge. Keep the legs very short so the whole pose fits the square cell.

## mayaNewB

Use case: stylized-concept. Create NEW production pixel-art animation frames for Maya, the SAME digital-fairy character as the two supplied references. Image1 is the existing native sprite artwork enlarged with nearest-neighbour, for pixel style/identity/scale consistency. Image2 is the supplied original character design, for costume and identity. DO NOT redraw the existing atlas. Create ONLY the new sheet described below.
Art style: crisp genuine 64x64 pixel art scaled up by an integer for visibility, clean deliberate pixel clusters, 1 logicalpixel darkplum outline, NO antialiasing, no smooth anime illustration. Chibi about 2.5 heads tall. Standing character46 logicalpixels high. Deepindigo longhair with violet highlights and straightbangs, one red-pink ribbon, large cyan eyes with white highlights, blush, white sleeveless bell dress with lavender shadow andpinktrim andcyan circuit hem, shortwhiteboots, tiny cyan floating sparkle, no wings. Every pose faces screen RIGHT, threequarter view for emotion. All frames have the SAME model, scale, hair length, proportions and outfit. No alternating left-facing poses.
Format: ONE transparent PNG, portrait/square-ish 4:3 aspect ratio, exactly FOUR equal columns by THREE equal rows of SQUARE cells, no gutters. Each cell is logically64x64. All frames LEFT ALIGNED in each row startingcolumn0. Generous clear space separating sprites. No drawn grid, no labels, no numbering, no text, no button/surface/platform drawings, no floor shadows, no backgrounds. Empty cells must be COMPLETELY EMPTY/transparent. Actual transparency.
Use only the16 colors #241B3A #2B2350 #4B3F8C #1A1533 #6FF2FF #FFE3D3 #F2BFA8 #FF9EAA #5BC8F5 #1E3A5F #FFFFFF #F8F6FF #D9D2F0 #FF7FB0 #FF4F7A #9C4A6B, plus transparency.
Logical anchor x32 y60 in each cell. Grounded feet at y60, torso centered x32. Do not crop hair, hands, dress or feet.

SHEET B: exactly TWELVE sprites, all four cells filled in each ofthree rows.
ROW0 POKE: FOUR distinct frames. She pokes the INVISIBLE BUTTON SURFACE beneath her feet, DOWN and FORWARD toward right, not out sideways. Keep boots groundedy60. Finger target about logicalx42 y59, as low as boots.
Frame0 crouched forward deeply at waist/knees, one indexfinger reaches down-forward, cheeky grin, eyes down. Her arm and fingertip must be LOW near her feet.
Frame1 tap: fingertip touches logical(42,59) with tinycyan contact sparkle. Body bends low enough for anatomical arm to reach. Feet remainy60.
Frame2 pulls hand slightly up, looks toward viewer cheekily.
Frame3 second tap fingertipdown(42,59), bigger grin, slight hair bounce. No button object or platform artwork.
ROW1 TEASE: FOUR distinct seated sprites. Seat at y59, body fills y20-60 and covers the middle ofcell(32,32). Same seated anatomy as existing SIT row, same modelscale, legs extended a little right.
Frame0 smug sit, arms crossed/hands knees, chin raised, closed mouth.
Frame1 teasing BLEH: a clearly visible PINK TONGUE OUT from mouth, one eye squeezed shut, head tilted toviewer.
Frame2 GIGGLE: hand overmouth, shoulders up, happy closed curvedeyes.
Frame3 same giggle seatedpose withbody1logicalpixel lower and tinycyan sparkle byhead.
ROW2 LOOK: FOUR distinct standing sprites feet y60, leaning towardright studying something on screen.
Frame0 PEER, one hand atforehead shadingeyes likevisor, narrowedcuriouseyes, mouthlittleline.
Frame1 LEAN IN slightlyfarther forward, eyeswide, tinycyan sparkle nearoneeye; shadinghand canstay.
Frame2 HMM, indexfinger tappingchin, eyes look up-right, tinyheadtilt.
Frame3 OOH, smallopen-mouth smile, eyebrowsraised, handdropping. Must read as study/inspect, not waving or covering ear.

## mayaNewC

Use case: stylized-concept. Create NEW production pixel-art animation frames for Maya, the SAME digital-fairy character as the two supplied references. Image1 is the existing native sprite artwork enlarged with nearest-neighbour, for pixel style/identity/scale consistency. Image2 is the supplied original character design, for costume and identity. DO NOT redraw the existing atlas. Create ONLY the new sheet described below.
Art style: crisp genuine 64x64 pixel art scaled up by an integer for visibility, clean deliberate pixel clusters, 1 logicalpixel darkplum outline, NO antialiasing, no smooth anime illustration. Chibi about 2.5 heads tall. Standing character46 logicalpixels high. Deepindigo longhair with violet highlights and straightbangs, one red-pink ribbon, large cyan eyes with white highlights, blush, white sleeveless bell dress with lavender shadow andpinktrim andcyan circuit hem, shortwhiteboots, tiny cyan floating sparkle, no wings. Every pose faces screen RIGHT, threequarter view for emotion. All frames have the SAME model, scale, hair length, proportions and outfit. No alternating left-facing poses.
Format: ONE transparent PNG, portrait/square-ish 4:3 aspect ratio, exactly FOUR equal columns by THREE equal rows of SQUARE cells, no gutters. Each cell is logically64x64. All frames LEFT ALIGNED in each row startingcolumn0. Generous clear space separating sprites. No drawn grid, no labels, no numbering, no text, no button/surface/platform drawings, no floor shadows, no backgrounds. Empty cells must be COMPLETELY EMPTY/transparent. Actual transparency.
Use only the16 colors #241B3A #2B2350 #4B3F8C #1A1533 #6FF2FF #FFE3D3 #F2BFA8 #FF9EAA #5BC8F5 #1E3A5F #FFFFFF #F8F6FF #D9D2F0 #FF7FB0 #FF4F7A #9C4A6B, plus transparency.
Logical anchor x32 y60 in each cell. Grounded feet at y60, torso centered x32. Do not crop hair, hands, dress or feet.

SHEET C: exactly NINE sprites.
ROW0 ASK: THREE distinct standing sprites columns0-2, column3 transparent. Feet ony60. Hopeful sheepish expression, bodyfacingright/threequarterface towardviewer.
Frame0 PLEASE, hands CLASPED together UNDERCHIN, hugehopefuleyes, smallheadtilt.
Frame1 samepose tinybounce1pxlower withoutmovingfeetbelowy60, brightpinkcheekblush.
Frame2 waiting, INDEXFINGERTIPS touch/tapTOGETHER infrontchest, eyesglancedown-right. No words, questionmarks, approvalsymbols or buttons.
ROW1 TYPE: FOUR sprites columns0-3. Standing/kneeling by invisiblefieldedge, feetony60. A SMALL CYAN PIXELKEYBOARD floats IN FRONT andtoRIGHT ofbody atlogicalx36-52,y50-56. It is a thin rectangularcyan outline with several distinct cyan squarekeys, not a laptop or computer. Hands activelytypeonkeyboardatlowerwaist.
Frame0 lefthanddownonthekeys,righthandraised, focusedeyesdown.
Frame1 righthanddown,lefthandraised,tinycyan1-2pxkeypopabovekey.
Frame2 lefthanddownagain,tinypinktonguepeeksfromcornerofmouth.
Frame3 righthanddownagain,smallproudsmileandkeypop. Keep keyboardatSAMEcoordinatesandSAMEsizeacrossfourframes.
ROW2 HELD: TWO sprites columns0,1 only; columns2,3 empty. HANGING IN AIR from the signatureRIBBON atlogical(32,6), like gently pickedupbyribbon. No hand or string or otherperson visible. The ribbon/grab point is at the TOP of thecell, y6, centeredx32; the BODY DANGLES BELOW it. Her boots endy52-58, visibly aboveground. Do NOT draw a normalstandingpose. Head/upperhairtiltedsidewaysandbodydanglingbeneathribbon. Puffedcheeks, surprisedbutnotfrightened.
Frame0 legs swingFORWARDtowardright, armscrossed, pouting.
Frame1 legs swingBACKtowardleft whilebodystillfacesRIGHT, onearmflails, eyes squeezedshut, onepixelbodysway. Ribbon stays atidenticalgrabpoint inbothframes. No mirroredcharacter. No injury or distress.

## Perch and held refinement

Use case: stylized-concept. Correct only the PERCH and HELD animation assets for Maya. Reference1 is existing Maya identity/pixel style; reference2 is recent perched poses; reference3 is recent hanging poses. Make ONE new square transparent PNG with exactly TWO columns and TWO rows, each cell an equal square. Four sprites total, no borders, labels, text, buttons, surfaces, extra characters, hands holding her, strings or backgrounds. Real64x64 pixelart scaledup, hard pixel edges, crispclusters, onepixel plumoutline, noantialiasing or softshadows. Eachsprite facesRIGHT, consistentmodelandscale.
Maya is the same indigo-haired cyan-eyed chibi girl in white sleeveless bell dress withlavendershadow,pinktrim,cyan circuithem,whiteboots,and ONE signature red-pink ribbon ON THE SIDE OF HER HEAD. Do not add a second ribbon on her crown. The character has only ONE ribbon.

TOP ROW, two PERCH frames:
She sits right on the very BOTTOM of each cell, SEAT at logical y59. The invisible edge is y60. Her hands restatseatlevel besideher. Her entire headandtorso ABOVEy59, bodyheightabout39pixels. Legs must be EXTREMELY SHORT dangling stubs: knees justathem/edge, onepixel shin and only2-3pixel boot-tips, TOTAL visible legandboot extent from seat tobottom NO MORETHAN FOUR LOGICALPIXELS. Boots at y62-63, nothing belowy63. No longshins. The shortboot-tips angle forwardtowardright. Frame0 one boot slightlyforward; frame1 swaptheboots and1pixelheadtilt. Relaxedcontentface, eyesopeninbothframes. Keeptheveryshort danglinglegs visuallydistinctfrom a normalgroundsit. No supportobject drawn.
BOTTOM ROW, two HELD frames:
The girl hangs from her EXISTING SIDE RIBBON, raisedto logicalx32,y6. NO NEW TOP BOW. Her ONLY ribbon is on the side of herhead and is the fixedgrabpoint. To achieve this, tilt the head/body so the existing side ribbon is the uppermost anchor while bodydanglesbeneathit. Longhairdroopsdown bygravity. Herwholebodyhangsunder theribbon with bootsatlogicaly52-58 and clearspacebelow. No floorcontact.
Frame0 poutingpuffedcheeks, armscrossed, bothlegs swingforwardtowardright.
Frame1 samefixedribbonanchor, legs swingbacktowardleft, ONEarmflailing, eyessqueezedshut, tinybodysway. Neitherframe shouldlookfearful orhurt. Keepfacingright evenwhenlegs swingback.
Keepfullhairandbootsvisibleinbothframes. No additional neckribbon, no crownribbon, no duplicatedsignaturebow.

Useexact16colorpaletteplusfulltransparency: #241B3A #2B2350 #4B3F8C #1A1533 #6FF2FF #FFE3D3 #F2BFA8 #FF9EAA #5BC8F5 #1E3A5F #FFFFFF #F8F6FF #D9D2F0 #FF7FB0 #FF4F7A #9C4A6B. Tinycyanstarnearshoulder onlyifitfits.

## Held ribbon correction

Edit this supplied four-sprite sheet. Change ONLY the two hanging figures in the BOTTOM ROW: each currently has TWO red bows, one at the top grab point and one at the right side of the head. REMOVE the SIDE BOW next to the face on each bottom figure, replacing that area with matching indigo hair. KEEP the TOP red bow as the hanging grab point, so each bottom figure has exactly ONE red bow. The red top bow is the original signature ribbon relocated upward as she is picked up by it. The upper two seated sprites each keep their existing single side bow and must remain unchanged.
Preserve the exact four-cell layout, character proportions, all poses, face expressions, dress, boots, sparkle, pixel-art style and all other details. Do not introduce a second bow again. The only edit is removing the side bows on the two bottom figures, replacing those small regions with indigo hair. Real transparent background, no added marks or text.

