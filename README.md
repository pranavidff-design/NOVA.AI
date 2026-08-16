# Nova — Building entirely from your phone (no computer needed)

Android Studio itself can't run on a phone — but you can build the APK in the
cloud using GitHub, entirely from your phone's browser, for free.

## Part A — Get the code onto GitHub (~10 min)

1. Open your phone browser, go to **github.com**, create a free account if you don't have one
2. Tap the **+** icon (top right) → **New repository**
   - Name it `nova-app` (or anything)
   - Keep it **Private** if you want (your choice)
   - Tap **Create repository**
3. On the new repo page, tap **"uploading an existing file"** (a link in the middle of the empty repo page)
4. From your phone's file browser, select the `NovaApp` folder **zipped as one file** — but GitHub's uploader doesn't unzip automatically, so instead:
   - Extract the zip on your phone first (most Android file manager apps can "Extract" a zip in place — long-press the zip → Extract)
   - Then use GitHub's upload page and select **all the extracted files/folders** — modern mobile browsers (Chrome) let you multi-select files including nested folders when you tap "choose files"
   - If your browser won't let you select whole folders: install the free **"Working Copy"-style app "GitHub" itself is not needed** — instead use a file manager app like **Solid Explorer** or **Files by Google**, which can upload folder structures more reliably, OR ask me and I'll walk you through the alternate "create file one by one" method for the ~25 files
5. Commit the upload (there's a "Commit changes" button after selecting files)

## Part B — Trigger the cloud build

1. In your repo, tap the **Actions** tab
2. You should see **"Build Nova APK"** listed — tap it
3. Tap **"Run workflow"** → **Run workflow** (confirm)
4. Wait 3-6 minutes (refresh the page) — a green checkmark means it succeeded
5. Tap into the completed run → scroll down to **Artifacts** → tap **nova-debug-apk** to download it
   (this downloads a `.zip` containing the APK — extract it to get `app-debug.apk`)

## Part C — Install the APK on your phone

1. Open the downloaded `app-debug.apk` file from your phone's Downloads
2. Android will ask to allow installing from this source (your browser or file manager) — allow it once
3. Tap **Install**

## Part D — Load Nova's AI brain (no adb needed)

1. In your phone browser, go to **huggingface.co/litert-community/Gemma3-1B-IT**
2. Sign in / create a free Hugging Face account if prompted, then click "Acknowledge license" (needed once to accept Google's Gemma license)
3. Go to the **Files** tab and download exactly this file: **`gemma3-1b-it-int4.task`** (~555MB — use WiFi).
   ⚠️ Don't download any file ending in **`-web.task`** or **`.litertlm`** — those are for browsers/a different runtime and will NOT load in Nova.
4. Open the **Nova app** → tap **"Load AI Model"** → a file picker opens → navigate to your Downloads folder → select `gemma3-1b-it-int4.task`
5. Nova copies it into her own storage and loads it — this takes maybe 30-60 seconds for a file this size, watch the status text

**Why this specific model:** Google put the older MediaPipe LLM Inference API (which Nova's code uses) into maintenance mode and newer models (Gemma 4, Gemma 3n) mostly ship in a different `.litertlm` format for a separate library. Gemma 3 1B is the newest/largest model still confirmed to ship a real Android-compatible `.task` file for Nova's exact setup — so it's the safest choice without changing Nova's code or dependencies.

After this one-time setup, Nova works fully offline for her core thinking (this is her fallback brain — see Part E for her smarter, primary cloud brain).

## Part E — Cloud brain setup (Gemini via Firebase, recommended, ~10 min)

This makes Nova genuinely smart (Gemini) instead of just the small offline model, with
**no backend to write or host** and your API key never sitting inside the app.

1. Phone browser → **console.firebase.google.com** → sign in with any Google account → **"Add project"** → name it anything (e.g. "Nova") → finish the wizard (you can skip Google Analytics)
2. In your new project, tap **"Add app" → Android** → Android package name: `com.nova.assistant` → Register app
3. It will offer a **`google-services.json`** file to download — download it
4. In the left sidebar, go to **Build → AI Logic** → follow the guided setup → this enables the Gemini Developer API for your project (free tier: no credit card, ~1,000+ requests/day) and turns on **App Check** automatically, which is what keeps your key safe without a server of your own
5. Back in your GitHub repo: upload the downloaded `google-services.json`, overwriting the placeholder at `app/google-services.json` (same upload method as Part A)
6. Re-run the build (Part B). Once installed, open Nova → **"Cloud Brain Setup"** button confirms it's connected — Nova will now use Gemini automatically whenever you're online, and quietly fall back to the offline model when you're not.

Nothing else changes — no code to edit, no key to type anywhere.

## Teaching Nova custom rules

Open Nova → **Memory** button → type a rule in plain Hinglish/English, e.g.
*"Whenever I say ghar aa gaya hoon, turn mobile data off and Wi-Fi on"* → tap
**Teach Rule**. Nova shows you exactly what it understood before saving —
nothing runs until you confirm. Manage/rename/disable/delete any taught or
learned rule from the **Routines** screen at any time; nothing is hidden.

## If a build fails on GitHub Actions

Tap into the failed run → tap the red ✕ step → copy the error text shown →
send it to me. Same idea as before — I read the real error and tell you the fix,
just no Android Studio needed on your end.

## Re-building after future updates

Whenever I give you new code, repeat Part A (upload the changed/new files to
the same repo — GitHub lets you overwrite existing files) then Part B again.
