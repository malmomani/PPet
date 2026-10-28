# Maya

Maya is an AI companion who lives on your Android phone as a little pixel girl. She roams over your screen, notices what you're doing, chats with you (in text or out loud), and slowly develops her own personality and feelings about you.

Her **brain** runs on your PC with a local AI model (nothing goes to the cloud). Her **body** is an Android app that talks to the brain over your Wi-Fi.

```
 Phone (Maya app)  <── Wi-Fi / Tailscale ──>  PC (Maya brain + Ollama)
  body, senses                                 thinking, memory, voice
```

This page gets you from zero to Maya on your phone. The detailed guides are:

- PC: [`brain/README.md`](brain/README.md)
- Phone: [`android/README.md`](android/README.md)

---

## What you need

| | |
|---|---|
| **PC** | Windows, Linux or macOS. An NVIDIA GPU with 8 GB+ VRAM (for example an RTX 3070) is recommended. |
| **Phone** | Android 10 or newer. Made and tested for a Samsung Galaxy S23 Ultra. |
| **Software on the PC** | [Python 3.11–3.13](https://www.python.org/downloads/), [Ollama](https://ollama.com/download), [Git](https://git-scm.com/downloads). |
| **Network** | Phone and PC on the same Wi-Fi. Or use [Tailscale](https://tailscale.com) on both to reach her away from home. |
| **GitHub** | Sign in to GitHub to download the app from the **Actions** tab. |

Setup takes about 30 minutes, mostly waiting for downloads.

---

## Part 1: Set up the brain on your PC

Commands below are for **Windows PowerShell**. Linux and macOS commands are in [`brain/README.md`](brain/README.md).

**1. Get the code**

```
git clone https://github.com/malmomani/PPet.git
cd PPet
git checkout ccr-001d5d18-2dun4m
cd brain
```

**2. Create a Python environment and install**

```
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -e .
```

> If PowerShell blocks the activate script, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once, then try again. In **cmd**, use `.venv\Scripts\activate.bat` instead.

**3. Download the AI models** (about 5 GB)

```
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

**4. Create your config**

```
copy maya.example.toml maya.toml
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Open `maya.toml` in a text editor. Under `[server]`, replace `token = "change-me"` with the long string the second command printed. This token is the password between your phone and PC; keep it secret.

**5. (Optional) Give her a voice**

```
pip install -e ".[voice]"
python -m maya_brain.fetch_voice
python -m maya_brain.voice_lab --play
```

The last command plays sample voices. Put the one you like into `maya.toml`, for example:

```toml
[voice]
voice = "af_sky:0.6,af_nicole:0.4"
pitch = 2.5
```

See [Voice](brain/README.md#voice) for details.

**6. Start the brain**

```
python -m maya_brain --config maya.toml
```

It prints lines like `ws://192.168.1.20:8765/ws`. Write down the address (`192.168.1.20`); you'll need it on the phone. Leave this window open while Maya is running.

> **Optional test:** `python -m maya_brain.sim` lets you chat with her in the terminal, without the phone.

**7. Open the Windows firewall** (once)

Run **PowerShell as administrator** and paste:

```
New-NetFirewallRule -DisplayName "Maya brain" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -Profile Private
```

Then make sure your Wi-Fi is set to **Private**: Settings → Network & internet → Wi-Fi → your network → Private network.

**Bigger brain (optional).** The default model (`qwen3:8b` on Ollama) works on most PCs. With a strong PC (for example an RTX 3070 8 GB and 32 GB of RAM) you can give Maya a much bigger model that can also look at pictures, using llama.cpp's `llama-server`. The full steps are in [`brain/README.md`](brain/README.md#bigger-brain-with-llama-server-optional).

---

## Part 2: Install the app on your phone

**1. Download the APK**

1. In this repo, open the **Actions** tab, then the **android** workflow.
2. Open the newest run with a green tick on branch `ccr-001d5d18-2dun4m`.
3. Under **Artifacts**, download **maya-debug-apk** and unzip it.

**2. Install it**

Copy the `.apk` to the phone (USB, Google Drive, or download it straight on the phone) and tap it. Allow installing from that source when Android asks. If Play Protect warns you, choose **Install anyway**.

**3. Pair it with your PC**

Open **Maya** and fill in **PC address** (from Part 1 step 6), **Port** `8765` and **Token** (from `maya.toml`). Tap **Save**.

**4. Grant permissions**

Tap **Grant** next to each line in the app:

| Permission | Needed for |
|---|---|
| Draw over other apps | **Required.** Showing Maya on screen. |
| Usage access | Knowing which app you're in. |
| Notifications | Her status notification (Shh / Hide buttons). |
| Ignore battery optimisation | Keeping her alive in the background. |
| Microphone | Holding her to talk out loud (optional). |
| Notification access | Noticing who messages you (optional; app and sender only, never the text). |
| Screen access | Reading the screen and roaming over buttons (optional; see Privacy). |

> **"Restricted setting" blocked?** Android blocks Notification access and Screen access for apps installed from an APK. Go to Android **Settings → Apps → Maya → ⋮ (top right) → Allow restricted settings**, then grant them again.

**5. Samsung battery settings** (important, or she'll fall asleep)

- Settings → Battery → Background usage limits: make sure Maya is **not** in Sleeping or Deep sleeping apps.
- Settings → Apps → Maya → Battery: choose **Unrestricted**.

**6. Start her**

Tap **Start Maya**. She appears on screen. The notification says **"Maya is here"** once she's connected to your PC; **"napping"** means she can't reach it yet.

---

## Using Maya

- **Tap** her to chat by typing.
- **Hold** her to talk out loud; let go to send.
- **Drag** or **throw** her around.
- She wanders over buttons, comments on what you're doing and who messages you, and sometimes teases you by sitting on your Send button. Tap her to shoo her off.
- The notification has **Shh** (quiet for an hour) and **Hide**.
- You can turn each sense off in her settings, and add apps she must never watch (banking apps, for example) to the **do-not-watch** list.

---

## Updating

PC, in the `PPet` folder:

```
git pull
```

Then restart the brain. On the phone, download the newest APK (Part 2 step 1) and install it over the old one; your settings are kept.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| She's asleep and the notification says "napping" | Is the brain window open on the PC? Are the phone and PC on the same Wi-Fi? Is the PC address still right (run `ipconfig`)? Is the firewall rule in place and your Wi-Fi set to Private? |
| The notification mentions the token | The token in the app doesn't match `maya.toml`. Re-enter it and tap Save. |
| She's stuck asleep but talking | Stop and start Maya from the app. |
| First reply is very slow | The model is loading into the GPU. Later replies are faster. |
| `ollama` is not recognized | Install Ollama, then open a new terminal. |
| `pip install` says "does not appear to be a Python project" | Run it from inside the `brain` folder. |
| No voice | Check `[voice]` is in `maya.toml`, then run `python -m maya_brain.fetch_voice`. |
| She disappears after a while | Redo the Samsung battery settings (Part 2 step 5). |

More is in [`android/README.md#troubleshooting`](android/README.md#troubleshooting).

---

## Privacy

- Everything stays between your phone and **your own PC**. The AI runs locally through Ollama (or llama-server). Pointing the brain at a cloud AI service in `maya.toml` would send her prompts there; the default does not.
- **Never use a router port-forward** to reach the brain from outside. Use Tailscale instead.
- **Notifications:** she sees only the app name and sender, never the message text.
- **Screen:** she never reads passwords, what you type, do-not-watch apps or the lock screen. Only a short headline per glance goes into her memory; the full text is never saved.
- Her memory lives in `brain/maya.db` on your PC. Delete it to give her a fresh start.

---

## Project layout

| Folder | Contents |
|---|---|
| `brain/` | The Python brain: personality, memory, emotions, initiative, voice, WebSocket server. |
| `android/core/` | Pure Kotlin logic (protocol, body behaviour, screen parsing), unit-tested. |
| `android/app/` | The Android app: overlay, senses, voice, settings. |
| `art/maya/` | Sprite sources and the brief for new animations. |
| `docs/superpowers/` | Design specs and implementation plans. |
