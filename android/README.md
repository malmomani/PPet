# Maya phone app: install and pairing guide

Maya lives on your phone but thinks on your PC. This guide gets her running on a Samsung phone (made for a Galaxy S23 Ultra) with a Windows PC. It takes about 15 minutes.

You need: the Maya brain already set up on your PC (see [`../brain/README.md`](../brain/README.md)), and the phone and PC on the same Wi-Fi.

## 1. Download the app (APK)

1. On GitHub, open the repo `malmomani/PPet` and click the **Actions** tab (you must be signed in to GitHub).
2. Click the workflow **android** on the left.
3. Open the newest run with a green tick on the branch `ccr-001d5d18-2dun4m`.
4. Scroll down to **Artifacts** and click **maya-debug-apk**. A `.zip` file downloads.
5. Unzip it. Inside is a file ending in `.apk`.

## 2. Install it on the phone

1. Get the `.apk` onto the phone. Any one of these works:
   - USB cable: connect the phone, then copy the file into the phone's **Download** folder.
   - Google Drive: upload the file on the PC, then open it in the Drive app on the phone.
   - Or open the artifact link directly in the phone's browser (signed in to GitHub) and download the zip there; unzip it with the **My Files** app.
2. On the phone, open **My Files** (or the app you used) and tap the `.apk`.
3. Android will say it can't install unknown apps. Tap **Settings**, switch on **Allow from this source**, go back, and tap **Install**.
4. If Play Protect warns about the app, choose **Install anyway**. It's your own app.

One-time note: if you installed an earlier Maya build (from before the app got a fixed signing key), uninstall it once first, otherwise Android refuses the install. Uninstalling also erases Maya's saved PC address and token and her permissions, so after installing the new build redo **5. Pair the app** (address, token and every permission) and **6. Samsung battery settings**. After that, new builds install over the old one and keep everything.

## 3. Start the brain on the PC

1. Open PowerShell in the `brain` folder and activate it: `.venv\Scripts\Activate.ps1`
2. Run: `python -m maya_brain --config maya.toml`
3. It prints lines like `ws://192.168.1.20:8765/ws`. The numbers in the middle are your **PC address** (here `192.168.1.20`). Ignore `127.0.0.1`.
   - Not sure which one? Open a PowerShell window and run `ipconfig`. Use the **IPv4 Address** under **Wireless LAN adapter Wi-Fi**.
   - Away from home? If you use Tailscale on the PC and phone, you can use the PC's Tailscale address instead (see the brain guide).
4. The **port** is `8765`.
5. The **token** is the long secret you put under `[server]` in `maya.toml`.

Leave this window open while Maya is running.

## 4. Let the phone through the Windows firewall

Windows blocks incoming connections by default, so Maya would nap forever. Fix it once:

1. Click Start, type **PowerShell**, right-click **Windows PowerShell** and choose **Run as administrator**.
2. Paste this and press Enter:

```
New-NetFirewallRule -DisplayName "Maya brain" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -Profile Private
```

3. Make sure your Wi-Fi is set to **Private**: Windows **Settings** → **Network & internet** → **Wi-Fi** → your network → **Network profile type** → **Private network**. (The rule only works on Private networks.)

## 5. Pair the app

1. Open **Maya** on the phone.
2. Under **Your PC**, fill in:
   - **PC address**: the address from step 3 (for example `192.168.1.20`)
   - **Port**: `8765`
   - **Token**: the token from `maya.toml`
3. Tap **Save**.
4. Under **Permissions**, tap **Grant** next to each line until it shows a tick (✓):
   - **Draw over other apps**: find Maya, switch it on.
   - **Usage access (which app is open)**: find Maya, switch it on. This lets her comment on the apps you use.
   - **Notifications**: tap **Allow**.
   - **Ignore battery optimisation**: this opens a list of apps. At the top, switch the filter to **All apps**, find **Maya**, and choose **Don't optimise** (the wording varies on Samsung). The Samsung **Unrestricted** step in section 6 matters most, so do that too.
   - **Microphone (hold Maya to talk)**: tap **While using the app** (or **Allow**). Only needed if you want to talk to her out loud; see section 7.
   - **Notification access (who messages you)**: find Maya, switch it on. Optional; see section 8.
   - **Screen access**: find Maya in the accessibility list, switch her on. Optional; see section 9.
5. Do the Samsung battery steps below, then come back.
6. Tap **Start Maya**. She should appear on screen. The notification first says "Maya is napping" and changes to "Maya is here" once she's connected to your PC.

Tap Maya to chat. The notification has **Shh** (be quiet for a while) and **Hide** (hide her from the screen; the button then says **Show** to bring her back).

## 6. Samsung battery settings (important)

Samsung's One UI puts apps to sleep, which stops Maya. Menu names vary a little between One UI versions. Do both of these:

1. **Settings** → **Battery** → **Background usage limits**:
   - Make sure Maya is **not** in **Sleeping apps** or **Deep sleeping apps**. If she is, remove her.
   - Turn off **Put unused apps to sleep** (or add Maya to **Never sleeping apps**).
2. **Settings** → **Apps** → **Maya** → **Battery** → choose **Unrestricted**.

## 7. Talking to Maya

You can talk to Maya instead of typing, and she can answer out loud.

1. Grant **Microphone** in the app's permission list (section 5).
2. Hold her and talk: press and hold Maya for about half a second, without moving your finger. Her bubble shows "…" while the microphone starts; when the phone gives a small buzz and the bubble shows 🎤, speak. The bubble shows what she hears as you go.
3. Let go, or just pause for about 2 seconds, to send. She gets what you said and answers. (A quick tap still opens the chat box, and dragging or throwing her works as before.)

If she says "…didn't catch that 🤔", hold her again and speak a little louder or closer. Listening stops by itself after 30 seconds.

The phone turns your speech into text and only the text goes to your PC. On Android 13 and newer, Maya first uses the phone's strict offline speech recognition, so your voice stays on the phone (see **Offline English speech** below). If that isn't available, she uses Google voice typing instead (see **Allow Google voice typing** below). On older Android she always uses your phone's speech service, which may use the internet if the offline model isn't installed.

**Maya's voice.** In the app, under **Maya's voice**, choose when she speaks her replies:

- **Off**: text bubbles only.
- **Only when I talk to her** (the default): she answers out loud when you spoke to her, and in text when you typed.
- **Always**: she also speaks her own remarks (about the apps you use and so on).

She stays quiet when the phone is on silent or vibrate, when you tap **Shh** or **Hide**, and the moment you start talking to her. A reply that arrives while you are still talking waits: it shows (and plays) right after what she heard. Changing this setting restarts Maya for a second.

**Allow Google voice typing.** Under **Maya's voice** (Android 13 and newer) this switch is on by default. Many phones, Samsung in particular, don't offer the strict offline recognizer Maya uses first, or it has no English, even after you downloaded the offline English pack for **Speech Services by Google**. With the switch on, Maya then uses the phone's normal Google voice typing: it is asked to work offline, and with the English pack installed it usually does, but Google may sometimes process your voice online. Once the strict recognizer has failed, Maya uses Google voice typing straight away until the app restarts or you flip the switch. Turn the switch off if your voice must never leave the phone: talking to Maya then only works with the strict offline recognizer, and on phones without it she can't hear you. When the switch is on and the strict recognizer is missing, the app shows **Using Google voice typing** instead of **Offline English speech: not installed**.

**Offline English speech.** On Android 13 and newer, with **Allow Google voice typing** off, talking to Maya needs the phone's offline English speech model, which comes from **Speech Services by Google**. The app checks for it: if it shows **Offline English speech: not installed**, tap **Set up** next to it. The first tap asks the phone to download English (US) in the background; give it a minute, then reopen the app. If the line is still there, tap **Set up** again: it opens the voice input settings, where you pick **Speech Services by Google** → **Offline speech recognition** → download **English (US)**. On Samsung the place is usually **Settings** → **General management** → **Keyboard list and default** (or **Voice input**). The wording varies between One UI versions; searching Settings for "offline speech" also finds it. If **Speech Services by Google** is missing or disabled, install or enable it from the Play Store first.

**Her voice comes from your PC.** The PC brain makes the speech audio. Set it up once on the PC as described in the brain guide's [Voice](../brain/README.md#voice) section; without it she answers in text only.

## 8. Let Maya see who messages you

Maya can notice when someone messages you (WhatsApp, SMS, Telegram, email, a missed call) and may comment on it now and then. She never announces every message.

1. In the app, under **Permissions**, tap **Grant** next to **Notification access (who messages you)**, find **Maya** and switch it on. If Android says it's a **restricted setting** (common for apps installed from an APK), go to **Settings** → **Apps** → **Maya**, tap **⋮** (top right) → **Allow restricted settings**, then try again.
2. Make sure **Let Maya see who messages me** (under **Messages**) is on. It is on by default; switch it off to stop this completely (Maya restarts for a second).

**Privacy.** Only the app name and the sender's name (and the group name for group chats) go to your PC. The message text is never read, not even on the phone. Apps in the **Never watch these apps** list (and anything with "bank" in its package name) are ignored.

## 9. Let Maya see your screen

Maya can read what's on the screen of the app you're using: its visible text (so she can talk about what you're looking at) and its buttons (so she can stand on them, sit on a **Send** or **Like** button to tease you, and fall off when you scroll). She never presses anything.

1. In the app, under **Permissions**, tap **Grant** next to **Screen access**. Android opens **Accessibility**. On Samsung, tap **Installed apps** (or **Downloaded apps**), then **Maya**, and switch it on. Android warns that the app can see your screen; tap **Allow**.
2. If the switch is greyed out or Android says it's a **restricted setting** (common for apps installed from an APK), go to **Settings** → **Apps** → **Maya**, tap **⋮** (top right) → **Allow restricted settings**, confirm with your PIN or fingerprint, then go back and switch Maya on.
3. Make sure **Let Maya read my screen** (under **Screen**) is on. It is on by default; switch it off to stop this completely (Maya restarts for a second). With it off she only walks along the bottom of the screen.

**Privacy.** Only the visible text of the app you're in (at most 40 short lines) and a short list of button kinds (send, like, play, search, share, comment, follow) go to your PC, and only now and then: soon after you switch apps, then at most every 3 minutes if the text changed. She never reads passwords or what you type, and never looks at the lock screen, the notification shade, her own settings, or apps in the **Never watch these apps** list (and anything with "bank" in its package name). On your PC she keeps one headline per glance in her memory; the full text is never saved, only held as "the latest screen" until the next glance. She only looks while she is on screen (not while hidden from the notification). To stop it, switch off **Let Maya read my screen** or turn Maya off in **Accessibility**.

## 10. Maya looks at pictures

With **Screen access** on and **Let Maya read my screen** on, Maya can also *look* at the screen: she takes a screenshot and your PC describes it in a sentence or two, so she can talk about the photo, video or meme you're looking at, not just the text around it. This needs **Android 11 or newer** (the S23 Ultra has it); on older phones she only reads the text.

**When she looks.**
- **On her own**, when the screen is mostly pictures (a gallery, a photo feed, a video): at most once every 5 minutes, and at most once every 10 minutes in the same app.
- **When you ask**, for example "look at this" (typed or spoken): she looks within a second, at most once every 20 seconds. If she isn't allowed to look right then, she tells you she couldn't see it.
- While she waits for what she makes of it, she holds a "looking" pose.

She never looks while she is hidden (from the notification), while the keyboard is open (so never while you type a password), on the lock screen or the notification shade, at Android's Settings or her own settings, or in apps in the **Never watch these apps** list (and anything with "bank" in its package name).

**Privacy.** The screenshot is shrunk (at most 1024 pixels on its long side) and only ever held in memory: it is never saved on the phone or the PC and never written to a log. It goes to your PC once, the PC's vision model describes it, and the picture is thrown away. Only a short description (at most 160 characters) stays in Maya's memory. If your PC brain uses an online AI service instead of a local one, the screenshot goes to that service; see the brain guide. Maya herself is in the screenshot (she's on top of the app); the PC is told to ignore her. To stop it, switch off **Let Maya read my screen** or turn Maya off in **Accessibility**.

## 11. Banking apps

Some banking apps refuse to open, or close themselves, while Maya is running. That's the bank being careful, not Maya doing anything: banks block apps that were **installed from an APK (not the Play Store) and have Accessibility switched on**, because phone-banking malware uses exactly that to read the screen and press buttons, and they also watch for **windows drawn over their app** (another malware trick). Maya has both.

**She steps away in do-not-watch apps.** While an app from the **Never watch these apps** list (or anything with "bank" in its package name) is in front, Maya takes her window off the screen completely, as if you had tapped **Hide**, and comes back when you switch to another app. If you tap **Hide** in the notification meanwhile, she stays hidden until you tap **Show**. She never reads, looks at or does anything in those apps. If your bank's package name doesn't contain "bank", add it to the list (in the app, under **Never watch these apps**, one package per line; the package name is the `id=` part of the app's Play Store link).

**If the bank still refuses** (it sees that Accessibility is on, even though Maya ignores it), set up the volume-key shortcut to switch Maya's **Screen access** off and on in a second:

1. Open **Settings** → **Accessibility** → **Advanced settings** → **Volume up and down buttons**.
2. Switch it on and pick **Maya**.
3. Before opening the bank, press and hold **both volume keys** for 3 seconds: Screen access turns off (Android tells you). Do the same after banking to turn it back on.

While Screen access is off, Maya only walks along the bottom of the screen and doesn't read or look at anything.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Notification says "Maya is napping (can't reach your PC)", or a bubble says "zzz…" | The phone can't reach the PC. Check: both on the same Wi-Fi? The brain window still running? The firewall rule from step 4 added, and Wi-Fi set to Private? The PC address correct (it can change; run `ipconfig` again)? Quick test: open `http://<PC address>:8765/health` in the phone's browser. If that page doesn't load, it's the network or firewall, not the app. |
| "Check the token in settings" | The token doesn't match `maya.toml`. Re-copy it exactly (no spaces), tap **Save**. |
| Maya doesn't comment on apps | **Usage access** is missing. In the app, tap **Grant** next to it and switch it on for Maya. |
| Maya never mentions messages | Check **Notification access** has a tick (✓) and **Let Maya see who messages me** is on. If the switch for Maya in Android's notification access list is greyed out, allow restricted settings (section 8). |
| Maya never talks about what's on screen, or only walks along the bottom | Check **Screen access** has a tick (✓) and **Let Maya read my screen** is on. If the switch for Maya in Android's accessibility list is greyed out, allow restricted settings (section 9). After the app is updated, Android sometimes turns screen access off: switch it on again. |
| Maya isn't visible | **Draw over other apps** isn't granted, or **Hide** was tapped in the notification. Tap **Hide** again to bring her back, or open the app and check the permissions list. In a banking app (or another app in **Never watch these apps**) she steps away on purpose and comes back when you leave it (section 11). |
| A banking app won't open or closes itself | See section 11: use the volume-key shortcut to switch Screen access off while banking. |
| Maya disappears after a while | Samsung put her to sleep. Redo the battery steps in section 6. |
| Nothing works after the PC restarts | Start the brain again (step 3). |
| Holding Maya shows "…" but never "🎤 …" | Keep holding: the 🎤 and a short buzz mean she is listening, so start talking then. If it never appears, see the next rows. |
| Holding Maya says "I can't hear you — check mic permission" | Grant **Microphone** in the app (section 5). If it's already granted, check **Settings** → **Apps** → **Maya** → **Permissions** → **Microphone** is allowed, and that the phone's microphone access is on (quick settings panel). |
| Holding Maya says "I can't hear you — mic busy, try again" | Another app (a call, a recorder, a voice assistant) is using the microphone, or Android didn't hand it over this time. Close that app and hold her again. |
| Holding Maya says "set up offline English speech" | **Allow Google voice typing** is off and the phone's strict offline recognizer is missing or has no English (common on Samsung, even with the Speech Services by Google English pack downloaded). Turn **Allow Google voice typing** on (section 7), or see **Offline English speech** in section 7. |
| Holding Maya says "speech recognition failed, try again" | Google voice typing couldn't work: usually no internet and no offline English pack. Connect to the internet, or download English (US) for **Speech Services by Google** (see **Offline English speech** in section 7), then hold her again. |
| Holding Maya says "this phone has no speech recognizer" | The phone has no speech service at all. Install or enable **Speech Services by Google** from the Play Store, and pick it as the voice input in the phone's settings. |
| She answers in text but never out loud | Check **Maya's voice** isn't **Off**, the phone isn't on silent or vibrate, and the PC voice is set up (brain guide, Voice section). |

## Changing Maya's look (sprites)

Maya's pictures live in `android/app/src/main/assets/sprites/`:

- `maya.png`: one sheet with one row per animation.
- `sprites.json`: describes the sheet: `frameWidth`/`frameHeight` (or `frame_w`/`frame_h`), and for each animation its `row`, number of `frames`, `fps` and whether it `loop`s.

The current art is the 64×64 Maya pack (12 animations); its sources, PSD, palette and preview live in `art/maya/`. To use new art, replace those two files keeping the animation names: idle, walk, sit, nap, wave, peek, happy, sulk, surprised, plus talk (while her voice plays), listen (while you hold her to talk) and think (while she works on a reply). Optional rows the app uses when present (otherwise it shows the row in brackets): jump and fall (surprised), perch (sit), poke (wave), tease (happy), look (think; after she took a screenshot), ask (listen), type (poke) and held (surprised; while you drag her). She's drawn at a whole-number scale about 64 dp tall.

Then commit and push (the phone only gets new art from a new APK). The **android** workflow builds one; download and install it as in steps 1 and 2. It installs over the old one and keeps your settings.
