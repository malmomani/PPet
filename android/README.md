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

One-time note: if you installed an earlier Maya build (from before the app got a fixed signing key), uninstall it once first, otherwise Android refuses the install. After that, new builds install over the old one.

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
5. Do the Samsung battery steps below, then come back.
6. Tap **Start Maya**. She should appear on screen. The notification first says "Maya is napping" and changes to "Maya is here" once she's connected to your PC.

Tap Maya to chat. The notification has **Shh** (be quiet for a while) and **Hide** (hide her from the screen).

## 6. Samsung battery settings (important)

Samsung's One UI puts apps to sleep, which stops Maya. Menu names vary a little between One UI versions. Do both of these:

1. **Settings** → **Battery** → **Background usage limits**:
   - Make sure Maya is **not** in **Sleeping apps** or **Deep sleeping apps**. If she is, remove her.
   - Turn off **Put unused apps to sleep** (or add Maya to **Never sleeping apps**).
2. **Settings** → **Apps** → **Maya** → **Battery** → choose **Unrestricted**.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Notification says "Maya is napping (can't reach your PC)", or a bubble says "zzz…" | The phone can't reach the PC. Check: both on the same Wi-Fi? The brain window still running? The firewall rule from step 4 added, and Wi-Fi set to Private? The PC address correct (it can change; run `ipconfig` again)? Quick test: open `http://<PC address>:8765/health` in the phone's browser. If that page doesn't load, it's the network or firewall, not the app. |
| "Check the token in settings" | The token doesn't match `maya.toml`. Re-copy it exactly (no spaces), tap **Save**. |
| Maya doesn't comment on apps | **Usage access** is missing. In the app, tap **Grant** next to it and switch it on for Maya. |
| Maya isn't visible | **Draw over other apps** isn't granted, or **Hide** was tapped in the notification. Tap **Hide** again to bring her back, or open the app and check the permissions list. |
| Maya disappears after a while | Samsung put her to sleep. Redo the battery steps in section 6. |
| Nothing works after the PC restarts | Start the brain again (step 3). |

## Changing Maya's look (sprites)

Maya's pictures live in `android/app/src/main/assets/sprites/`:

- `maya.png`: one sheet with one row per animation.
- `sprites.json`: describes the sheet: `frame_w` and `frame_h` (size of one frame), and for each animation its `row`, number of `frames`, `fps` and whether it `loop`s.

To use your own art, replace those two files, keeping the animation names (idle, walk, sit, nap, wave, peek, happy, sulk, surprised). To get the placeholder art back, run this from the repo root: `python android/tools/make_placeholder_sprites.py` (on Windows; use `python3` on Linux/macOS).

Then commit and push (the phone only gets new art from a new APK). The **android** workflow builds one; download and install it as in steps 1 and 2. It installs over the old one and keeps your settings.
