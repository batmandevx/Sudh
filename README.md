# Shuddh — The People's Lab (Android)

> 📲 **Download the app:** [latest release APK](https://github.com/batmandevx/Sudh/releases/latest) (Android 8+, arm64) · [all releases](https://github.com/batmandevx/Sudh/releases)


An offline phone lab for testing milk, water, honey, air and produce. It uses the phone's flash, camera, IR blaster, gyroscope, mic and vibration motor as measuring instruments. Verdicts are spoken in English, Hindi, Kannada, Telugu or Tamil, and the app stores a hash-chained history on the phone.

AI and lab processing run on the phone. Optional maps and assistant web search use the internet; their switches are off by default.

## Quick setup (build from source)

```bash
git clone https://github.com/batmandevx/Sudh.git && cd Sudh
./scripts/fetch_models.sh          # downloads the 6 public MediaPipe models into app/src/main/assets
./gradlew assembleDebug            # JDK 17, Android SDK 35
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Optional on-device LLMs (Qwen2.5-1.5B / 0.5B, Phi-4-mini `.task`) are pushed to
`/sdcard/Android/data/com.shuddh.lab/files/models/` — see **AI Models** in the app for the exact file names.

## Assistant web search (v3.6)

Enable **Web access** in Ask Shuddh (or Settings → Privacy dashboard), then select **Search web** before sending any query, or say “Search the web for …”. Results include source titles, excerpts, links and a fetch time. The fetch time is not the page's publication date. Failure and empty-result states offer a browser search; no answer is fabricated.

Only the current search text is transmitted to Bing. Conversation history, local device context, scans and attachments are never appended. Feed excerpts cannot execute agent tools. No API key or cloud model is required. This personal build displays Bing's search RSS feed; that feed specifies personal, non-commercial use. A distributed/commercial edition should use a licensed search API. Feed availability and search relevance depend on the provider.

## Build

Requirements: JDK 17 and the Android SDK (platform 35).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On this machine the toolchain lives in `~/toolchain` (JDK 17 and Gradle 8.11.1, no extra download needed):

```bash
export JAVA_HOME=~/toolchain/jdk/jdk-17.0.20.1+1/Contents/Home
~/toolchain/gradle-8.11.1/bin/gradle assembleDebug testDebugUnitTest
~/Library/Android/sdk/platform-tools/adb install -r Shuddh-debug.apk
```

A prebuilt `Shuddh-debug.apk` sits in the repo root. Install it by USB with adb, or copy it to the phone and open it (allow "install unknown apps").

## Instruments

| Module | Hardware | Method | Output |
|---|---|---|---|
| Spectrum | Flash + camera + CD grating | Column-binned RGB band → wavelength axis from the CFL Hg lines (435.8 / 546.1 nm, plus the 611.6 nm Eu line) → absorbance A = log10(I₀/I). The optional self-reference band corrects lamp drift. | Chlorine, nitrate, iron, fluoride in mg/L (from Beer–Lambert standards). Milk detergent, starch and urea (presence vs a pure-milk blank). |
| Polar | Flash + camera + gyro + 2 polarisers | The phone is the rotating analyser. The gyroscope integrates the angle, and a Malus's-law least-squares fit gives the transmission maximum. α = sample − blank. | % of the way from pure honey to syrup (between saved references) |
| NIR | IR blaster + camera | 38 kHz bursts switched on and off about every 300 ms. Lock-in signal = mean(on) − mean(off). A = log10(S_ref / S_sample). | % added water (between pure and diluted references) |
| Echo | Mic | Onset-triggered capture, Hann-window FFT of 8192 samples, parabolic peak, −20 dB ring-down time | Coconut fill, watermelon ripeness, container level |
| Strips | Torch + camera | Pad colour divided by an in-frame white patch → CIELAB → projected onto the kit's own shade chart (captured once) | pH, hardness, chlorine, nitrate, arsenic, fluoride |
| Nami (surface moisture) | Speaker + mic | 10 linear chirps (2→18 kHz) → matched-filter detection → transfer function H(f) = \|R\|/\|C\| in 16 bands → projected between your dry and wet references | Moisture index for soil, wall dampness, grain storage, laundry |
| Hawa / Turbidity | Flash + camera at 90° | Side-scatter vs clean baseline, minus a dark frame | × baseline for air particulates or water cloudiness |
| Float | Lactometer | CLR = LR + 0.2·(T − 27). SNF = CLR/4 + 0.22·fat + 0.72 (Richmond). | % added water vs the FSSAI minimum SNF |

Every verdict comes with an **evidence ladder** (observation → pattern → quality/calibration checks → hypothesis → confirmation). The app returns **INCONCLUSIVE** instead of guessing when a check fails: dark band, clipped reference, no calibration, off-chart colour, or too short a sweep.

## Intelligence & trust layer
- **Hysteresis:** one bad scan is *provisional*. Two agreeing scans of the same sample within 15 minutes are *CONFIRMED*.
- **Vendor memory:** for example, "Ramesh Dairy: failed 3 of your last 9 scans. First failure… Trend: worsening".
- **Kitchen Health Score:** a weekly score on the home screen.
- **Purity Passport:** a PDF report with SHA-256 hash-chained records, built on the phone and shared through the Android share sheet. The History screen verifies the chain.
- **Family Care:** a pre-filled SMS to a family member, sent through the phone's SMS app.
- **Haptic verdicts:** two short pulses = safe, three = caution, one long = danger.
- **Vibration stirrer:** the phone vibrates for 10 s to mix a vial resting on it.

## v5.3: organised Home, new health & community tools

- **Home:** Discover is grouped into Health checks, Kitchen safety, Community, and AI & learning. Each group is a 4-column grid of hand-drawn animated line icons (no emoji, no sideways scrolling). Adds a "Why India needs this" card (FSSAI data from a Lok Sabha reply, July 2026).
- **Mosquito Radar:** harmonic wingbeat detection, with mains hum and wobbly sounds rejected. Pitch likelihood × time-of-day prior gives Aedes, Anopheles, Culex or male. Results can be reported to Hive.
- **Guardian:** a foreground fall detector (free fall → impact → stillness), a 30 s "Are you OK?" check, then an SOS over the Bluetooth mesh and SMS with GPS, a siren and torch Morse SOS. Includes a disaster beacon and a banner for SOS from nearby phones.
- **Family Exposure Ledger:** nitrate, fluoride and arsenic intake as a % of health-based daily limits, per family member, over 30 days.
- **True Price:** dilution verdicts show the real price per litre and the money lost per month.
- **Milkman Ledger:** daily litres, a calendar coloured by water tests, and a fair month-end bill you can send to the milkman.
- **New tests:** lead chromate in turmeric (DPC, 540 nm screening proxy) and iodized salt (starch–iodine blue).
- **MiniCPM5-2B** on-device chat via LiteRT-LM (Kotlin 2.2), about 7 tok/s on GPU.
- **Hive:** Scan, Warn and Share open full-screen panels. Warn and Share accept a quick hand-written alert.
- **Boil Guard:** altitude picker on phones without a barometer.
- **Wording:** NIR, Polar and Magneto claims rephrased as reference-relative screening.

## v5.1: Outbreak Watch and Anaemia Screen

- **Outbreak Watch:**
  - Households report illness anonymously (symptom, people affected, area). Reports travel over QR or the Bluetooth mesh as compact "case" items.
  - Per area, the last 72 h is compared with the area's own 14-day background rate.
  - **Watch** fires when cases climb above normal. **Outbreak alert** fires for ≥5 people in ≥3 homes above baseline, ≥2 jaundice cases, or stomach illness plus an unsafe-water test nearby.
  - Screen: pulsing status radar, 14-day epidemic curve stacked by symptom, likely cause, action checklist, one-tap message to an ASHA/health worker, and a demo outbreak.
  - Unit-tested: cluster, water-linked escalation, jaundice, other areas ignored, high-baseline damping.
- **Anaemia Screen** (screening, not diagnosis):
  - Nail-bed or inner-eyelid pallor measured with the flash as a white-card-normalised erythema index (log R − log G), robust 20-frame capture, three readings averaged.
  - Pale, borderline or healthy bands, an animated blood drop and pallor scale, iron-rich food guidance, and a pointer to free Hb testing (Anaemia Mukt Bharat).
  - The band cut-offs are heuristics pending clinical calibration.

## v5.0: Grain Scan, sharper Label Lens, vector Magneto

- **Grain Scan:**
  - Background polarity is read from the image border; objects are pixels beyond an Otsu-derived contrast margin; 4-connected components become blobs.
  - Each blob is compared with the median grain: stone, husk or insect (luma ±35 %), discoloured (chroma shift), broken (<55 % size), clump (split by area).
  - Shows purity %, a donut chart and rings on the photo. Unit-tested on synthetic rice-and-stone and dal scenes.
- **Label Lens accuracy:** snaps the sharpest frame of the last ~1.5 s (variance-of-Laplacian focus measure) with live sharpness guidance. OCR digit confusions (O→0, I/l→1, S→5, B→8) are repaired inside date-shaped tokens before parsing.
- **Magneto accuracy:** disturbance is now |B − B₀| over all three axes, not the change in magnitude, so steel that bends the field's direction is caught.

## v4.9: Boil Guard, Oil Check, Telugu & Tamil, new Hive

- **Boil Guard** (mic + barometer + haptics + voice):
  - Hears a pot reach a rolling boil: a learned room baseline, then heating, then the singing peak, then a ≥3 dB dip or irregular bubbling (spectral flux), held for several seconds.
  - The barometer gives altitude and the local boiling point (Clausius–Clapeyron).
  - Times the WHO safe boil (1 min; 3 min above 2,000 m) and warns if the boil stops early.
- **Oil Check** (camera colorimetry): CIELAB against a white card, a browning index and a darkening score versus your fresh oil give an estimated reuse count and a discard advisory. Points to RUCO collection for disposal.
- **Telugu (తెలుగు) and Tamil (தமிழ்):**
  - Every verdict, advice and voice phrase is translated (110), plus 140 on-screen labels (titles, sections, buttons, tabs) in Hindi, Kannada, Telugu and Tamil via `I18n` and `tr()`.
  - Voice (TTS) and speech recognition use te-IN / ta-IN. The agent can switch to Telugu or Tamil.
- **Hive redesign:**
  - Animated neighbourhood network, a "How Hive works" strip, and action tiles (Scan QR, Warn neighbours, Share result, Mesh chat).
  - Safety-pulse donut, vendor trust board (smoothed 0–100 score; Trusted, Watch or Avoid; seal QR), and an alert feed.
  - Alerts travel over the Bluetooth mesh in a compact <100-byte format and are imported automatically.
  - A sample neighbourhood is available for demos.

## v4.8: confidence on every verdict, robust averaging

- **Robust frame averaging** (`camera/Frames.kt` → `Robust`):
  - Frames whose brightness is an outlier (shadow, flicker, auto-exposure hunting) are dropped by the MAD rule, then each value takes a 20 % trimmed mean.
  - Spectrum now averages 20 frames and Strip 16.
- **Confidence score on every verdict:** computed from the instrument's own checks (quality and calibration checks weigh 1.5×; inconclusive results are capped at 40).
  - The verdict screen shows an animated ring plus every check (✓/✗) with a fix hint.
  - New quality checks for NIR (reference separation, signal, in-range), Float (lactometer range, temperature range) and Magneto (signal vs sensor noise).
- **Animated verdicts:**
  - Celebration burst for safe, shockwaves for unsafe, a ripple for caution.
  - The value counts up.
  - A trend chart of your past readings for the same test.
- **Float:** an animated lactometer floats lower and the milk pales as added water rises.
- **Voice:** speech requested while the TTS engine is starting is queued and spoken once it's ready.

## v4.7: Pro model, cleaner AI, Hawa lock-in

- **Third on-device model, Phi-4-mini Instruct 3.8B** (MIT, int8 `.task`, about 4 tok/s on the Adreno GPU), at `files/models/shuddh-pro.task`.
  - It writes recipes and long answers. Fast Qwen 1.5B keeps short replies; the 0.5B router keeps routing.
  - Switch it on or off under AI Models.
- **Chat rendering:** markdown (headings, bullets, numbered steps, bold, code). Literal `\n` is cleaned up and speech is spoken without symbols.
- **Repetition guard:** streaming stops at the first repeated line or loop, and the trimmed text is returned after the engine reports done.
- **Actions are exact:** alarm, timer, apps, notes and similar actions answer with the real result, never a model paraphrase. "2:34" picks the next 2:34. Earlier turns are included only for genuine follow-ups.
- **Shuddh Hawa 2.0:**
  - Flash lock-in nephelometry: 6 OFF/ON cycles, ON minus neighbouring OFF, so ambient light cancels. Settling frames are dropped, outliers rejected with MAD, and results carry a 95% CI.
  - RGB Ångström exponent separates fine smoke from coarse dust; sparkle counting catches large specks.
  - Animated beam scene, haze gauge, lock-in trace, smoke monitor with voice and vibration alerts.
- The privacy stub `CCTDestination` now implements `EncodedDestination`, which ML Kit requires.

## v4.6: Vision Lab, Kalman filters, agentic AI, haptics, whistle 2.0

- **Vision Lab (on-device machine vision, MediaPipe Tasks):** live, at about 15 fps on GPU+CPU with all four models.
  - EfficientDet-Lite0 object detection (80 classes), with suggestions for the matching Shuddh test.
  - 21-joint hand tracking ×2, with gestures 👍 ✌️ ☝️ 👌 ✊ ✋ 🤘 🤙. Point ☝️ to air-draw; make a fist to erase.
  - 478-point face mesh with smile, blink and head-turn detection.
  - 33-joint body pose with posture and an arm-raise rep counter.
  - Voice guidance.
  - Each model runs on the GPU and falls back to the CPU if it fails.
- **Kalman filters (`core/Kalman.kt`):**
  - Constant-velocity trackers smooth every vision landmark and box. A toggle shows the raw jitter for comparison.
  - A 1-D filter smooths the live BPM in Pulse, weighted by signal quality.
- **Agentic AI:**
  - Recipes and general knowledge now come from the open-knowledge chat prompt instead of a refusal.
  - New tools: recipe with one-tap step timers, exact calculator, unit converter, calendar events, notes, shopping list, open any app, dial.
  - Multi-step plans ("…and then…") run step by step, with progress shown in the chat.
- **Haptics engine:** primitives where the motor supports them.
  - Heartbeat on every pulse beat, plus "feel your pulse" replay.
  - Sonar ping, and a moisture rumble scaled to wetness.
  - A whistle-count thud and a stove alarm.
  - Gesture, blink and rep ticks.
  - A Geiger-counter clicker in Magneto.
  - A Settings switch to turn haptics off.
- **Whistle counter 2.0:**
  - Per-bin noise floor (fans and hum are subtracted), tonality with hysteresis, spectral flatness, a pitch-stability check and a refractory gap, unit-tested on synthetic sounds.
  - Animated cooker with a rattling weight and steam.
  - Cooking timeline with the next whistle predicted.
- **Privacy:** MediaPipe's usage logger is satisfied by a no-op `CCTDestination` stub. The CCT uploader is still excluded, so nothing is sent.

## v4.5: Pulse (heart rate) and Nami 2.0

- **Pulse (PPG):** cover the back camera and flash with a fingertip. The flash lights the finger and the camera samples it about 30 times a second. Shuddh resamples to 30 Hz, removes drift, and finds BPM by autocorrelation. It also detects beats and reports HRV (RMSSD) and signal quality.
  - Voice guidance in EN/HI/KN, a beating heart dial and a live waveform.
  - Finger detection requires the centre **and all four corners** of the frame to be deep red, plus 8 consistent frames. A flash reflection off a table no longer counts as a finger.
  - Wellness only, not a medical device.
- **Nami 2.0, accuracy:**
  - **Per-band noise floor:** the silence recorded before the chirps measures the room's noise. Bands drowned by a fan or a voice are down-weighted by a soft SNR gate.
  - **Inverse-variance weighting:** steady bands count more than jittery ones.
  - **Level-invariant projection:** the score uses the spectrum's shape, so holding the phone a few mm closer doesn't read as "wetter".
  - **Mic clipping detection.**
  - **Fixed loudness:** media volume is set to 85% during every ping, then restored, so references and readings are made at exactly the same level.
  - **Steady-only pings:** a ping where the phone moved is discarded and retaken (up to 6 attempts). Each ping is 12 chirps, so 36 are pooled.
  - **One-time phone calibration in open air:** removes the phone's own speaker and mic colouring from the quick estimate.
  - **Accuracy rating on every result:** ★★★ needs dry and wet references with 2+ captures each and a ±8% CI or tighter.
  - **Result popup after every ping:** shows %, DRY/DAMP/WET, advice and one-tap "This is DRY / WET". A clear warning explains failures, such as being on a call.
- **Nami 2.0, UI:**
  - Animated sonar scene: chirps radiate down, echoes return, and pores fill with water. The surface texture changes per material.
  - Liquid-fill gauge with a 95% confidence arc, plus live quality chips (volume, room noise, steadiness, clipping, chirps heard).
  - Per-band SNR strip and echo trace.
  - Spoken result.
  - **Every ping shows a %:** without references it shows a *quick estimate*. Wet pores reflect more high frequencies, so the estimate comes from the high-band vs low-band echo tilt. Once dry and wet references are saved, it switches to the calibrated value. A large result card shows %, ± range, DRY/DAMP/WET and advice.
  - **Spot survey:** save several spots and Shuddh ranks them to point at the leak or seepage source.

## v4.4: Pantry, Badges, Guide, Settings redesign
- **Pantry and expiry tracker:**
  - Add a packet from Label Lens (OCR expiry) or manually, with quick shelf-life chips or a date picker.
  - Countdown rings, a fresh / soon / expired donut, and a food-saved score from "Used" vs "Wasted".
  - An "Use soon" strip on Home.
- **Badges and levels:** 12 achievements computed from real use (streaks, instruments, confirmed catches, vendors, GPS scans, mesh, AI questions, languages), with progress rings, a shine and an XP bar.
- **Adulteration Guide:** 16 home tests adapted from FSSAI DART, searchable by food and category, with expandable steps and a link to the matching instrument.
- **Share score card:** a 1080×1350 Kitchen Health image rendered on the phone.
- **Insights:** an adulteration cost meter (₹/month lost to watered milk, from Float results) and a vendor safety leaderboard.
- **Settings:**
  - A profile card with your level, and live accent themes (Emerald, Saffron, Violet, Rose, Ocean).
  - Reduce motion, and a privacy dashboard (network mode, permission status, animated storage bars).
  - A weekly Sunday test reminder, and grouped settings rows.
- **Slide to join** the mesh: a drag-to-confirm control with a shimmering label, spring-back and a haptic tick.

## v4.3: real map, mesh rooms, new History
- **Map:** a full-screen OpenStreetMap street map (dark or light).
  - A blue location dot with a compass heading wedge, a my-location button and a GPS-tagged scan heatmap.
  - Category chips (Dairy, Grocery, Water, Market, Food) and a nearby-places sheet.
  - A place card with distance, walking time, direction and **Directions** (hands off to your maps app).
  - Places come from the Overpass mirrors first, falling back to Nominatim category search.
  - The map needs the internet, so it's gated by a Settings switch. Scans, photos and audio are never sent.
  - ML Kit's telemetry uploader is excluded at build time.
- **Mesh rooms:** the packet header carries a 16-bit room code, and every phone relays every room.
  - **Invite QR** (`SHUDDHMESH1;r=room;n=name`) and **Scan** to join, plus new private rooms.
  - A chat UI with nearby-people avatars and signal bars, bubbles showing hop counts, and SOS / share-verdict chips.
- **History:** donut summary, hash-chain badge, live search, filter chips with counts, vendor memory, a day-grouped timeline with instrument icons, and expandable evidence plus actions (PDF, QR, map, complaint).
- **AI guard:** greetings bypass the router. Action tools only run when your own message contains a matching trigger word, so a small-model mistake can't open the whistle counter on "hi".
- **Media search accuracy:**
  - Food-domain query expansion and optional LLM expansion.
  - Rarity-weighted keyword matching.
  - MobileNet visual embeddings for "similar photos".
  - More labels per photo, and a Rebuild index button.

## v4.2: media search, video moments, new home
- **Instant Media Search:** indexes your newest 200 gallery photos on the phone.
  - ML Kit labels + OCR → a content-only description → Universal Sentence Encoder vectors (6 MB, bundled).
  - Ranking uses common-component removal plus keyword and expiry/FSSAI boosts.
  - On-device test: "dairy product" → milk, "something sweet from bees" → honey, "people" → person (5 of 5 correct, with no shared words).
  - The agent tool `search_photos` opens it with the query filled in.
- **Video Moment Finder:** samples about 1 frame/s (up to 90 frames) and indexes each one the same way. Search shows an animated relevance timeline and top moments, and tapping one seeks the built-in player.
- **Home redesign:**
  - Logo, greeting and language menu
  - A Kitchen Health hero card with divided stats
  - An "Ask Shuddh" bar
  - Five quick actions with custom line icons
  - Instruments in three 4-column groups with distinct icons (cooker, magnet, toolbox, sparkle) and readiness dots
  - A recent-scans strip
  - The floating tab bar no longer has a backdrop strip.

## v4.1: the agent can act
The router model can call **action tools** as well as data tools:
- `set_timer` and `set_alarm` (reminders) run through the Android Clock app.
- `flashlight` toggles the torch.
- `generate_qr` shows a QR code in the chat.
- `message_family` opens a pre-filled SMS.
- `whistle_counter` opens the counter with a preset count.
- `daily_tip` gives a food-safety fact; `set_language` switches the app language.

Durations ("1 hour 20 min", "half an hour", "15 मिनट") and clock times ("6:30 am", "raat 8 baje") are parsed by deterministic code, not by the model.

Verified on the OnePlus 15R:
- The router picked the right tool and arguments on 5 of 5 test prompts.
- "Timer 10 min to boil water" started a real Clock-app timer without leaving Shuddh.

## v4: on-device AI, new sensors, new brand
- **Two local LLMs (MediaPipe LLM Inference):**
  - *Chat:* Qwen2.5-1.5B-Instruct, int8, on the GPU, about 16 tok/s on a Snapdragon SM8845.
  - *Tool router:* Qwen2.5-0.5B-Instruct, int8, on the CPU (its GPU output was garbled on this phone, so the router defaults to CPU).
  - **Ask Shuddh** pipeline: voice (on-device speech model) → router emits a JSON function call (12 tools: kitchen score, vendor history, contaminant facts, open an instrument, mesh broadcast, download report, analyse photo…) → Kotlin runs it on the phone's own data → the chat model answers using only those facts.
  - A word-boundary rule router takes over if a model is missing or its JSON is invalid.
  - Install the models with `adb push` (see the Models screen) or *Import file…*. The app never downloads anything.
  - Byte-level BPE repair fixes non-Latin text from the tokenizer.
  - For Hindi, the small model answers in English, and a deterministic Hindi line is added and spoken.
- **Vision:** ML Kit's bundled image labeller and OCR (Latin + Devanagari) run on the phone. **Label Lens** reads the FSSAI licence number, expiry date (or computes it from "best before N months"), MRP, a veg mark and the dominant colours, then gives a verdict or hands off to the AI.
- **Sensors for accuracy:**
  - The accelerometer gates camera frames (only steady frames are averaged).
  - A gravity bubble level shows tilt.
  - A light sensor warns about stray light.
  - **Wave-to-capture** uses the proximity sensor, so you never touch the phone mid-measurement.
- **Whistle Counter:** the mic plus an FFT detect loud, tonal whistles of 1–6 kHz lasting 0.5 s or more. It can learn your cooker's pitch, keeps the screen awake, and alarms at N whistles.
- **Brand:** Unbounded (display) + Manrope (body). New droplet-and-prism logo with a static spectrum rim and shimmering rays, and a matching adaptive icon.
- **UX:** screen entrance transitions, haptic ticks on buttons, a chat-style Ask Shuddh with a pinned composer, and a compact privacy pill.

## v3 additions
- **Shuddh Mesh (Bluetooth LE):** offline, multi-hop chat, alerts and SOS carried in BLE 5 extended advertisements. Each phone re-broadcasts new messages with TTL − 1, and duplicate IDs are dropped. There's an animated radar of nearby phones (distance estimated from RSSI) and a **Purity Beacon** that lets a shop broadcast its vendor seal to passers-by. Received alerts and seals feed the Hive and the area board. No pairing, no internet.
- **Ask Shuddh (edge AI):** voice questions go through Android's **on-device speech recognition model**. An offline intent engine (English, Hindi, Hinglish) answers only from the scan log, vendor memory, community data and a built-in fact sheet, labels each answer with what it's based on, and speaks it.
- **Echo accuracy:**
  - Each tap is described by 4 features: resonant peak, spectral centroid, ring-down time and high/low energy ratio.
  - A nearest-centroid classifier is **trained on the phone** from many reference taps, giving a probability and a separation score.
  - Clipped taps are rejected automatically.
  - New charts: tap waveform, spectrum, an animated cluster map, and a probability bar.
- **Nami accuracy:**
  - 3 pings × 10 chirps are pooled, with outlier chirps rejected (MAD rule).
  - References are running averages of several captures.
  - The moisture index comes with a **95% confidence interval**, shown on the meter and in a per-chirp histogram.
- **Magneto:** the magnetometer checks whether utensil steel is magnetic (food-grade 304/316 isn't). It has a live dial and trace, and a 5-sample median filter against spikes.
- **Downloadable PDF:** saved to Downloads/Shuddh, with an infographic summary page (verdict donut, stats, chain-integrity badge, per-test bars, vendor list) and colour-coded record cards.
- **Insights:** safety radar by category (water, milk, honey, air, produce, surfaces, utensils) and 14-day stacked activity bars.

## Look & feel (v2.1)
- Dark "aurora" theme with slowly drifting colour fields, frosted-glass cards, and a spectrum-gradient brand.
- **Home:** a rotating spectrum orb with an animated count-up Kitchen Health score. Each instrument card has its own animated diagram (prism rays, rotating polariser, IR waves, sound bars, strip swatches, drifting particles, bobbing lactometer, rippling droplet), and the cards enter in a staggered sequence.
- **Verdict:** a semicircle gauge with a spring-animated needle, a pulsing UNSAFE label, and the evidence shown as an animated vertical timeline.
- **Live charts:** gradient-filled. The spectrometer fills the area under the curve with the actual colour of each wavelength.
- **Insights:** an animated donut of all verdicts, plus bars that grow in.
- **Navigation:** a floating glass pill bar. Demo shortcut: `adb shell am start -n com.shuddh.lab/.MainActivity --es screen NAMI` (any screen name).

## App structure (v2.0)
- **Five tabs:** Lab, History, Insights, Community and Settings, plus a three-step first-run onboarding (language, area, permissions).
- **Lab tab:**
  - Kitchen Health ring with this week's digest and stat tiles
  - Batch-alert banner
  - Instrument grid with live **readiness badges** (which instruments are calibrated)
  - Recent scans
- **Each instrument:**
  - A **step tracker** showing what's done and what's next
  - A collapsible **"How it works"** panel explaining the science (doubles as School Lab Mode)
  - The camera preview starts with a "Starting camera…" state
- **Verdict:**
  - Animated verdict ring with a "what to do" card
  - Evidence ladder
  - **Cross-check** against other instruments that read the same sample
  - Vendor memory
  - Actions: Purity Passport, family SMS, **alert QR**, and **file a complaint** (a pre-filled FSSAI complaint in English or Hindi with the PDF attached)
- **Insights:**
  - Weekly digest
  - 30-day kitchen-score trend
  - Pass/fail bars per test
  - **Corroboration engine:** the same sample tag read by 2+ instruments within 24 h → corroborated, or flagged as a conflict
  - **Seasonal/monsoon watch**
  - **Area purity board** (your scans plus imported community reports)
  - Vendor league
- **Community (the offline Hive):**
  - Phones exchange **alerts** and vendor **Purity Seals** by showing and scanning QR codes, decoded on the phone with ZXing. No server, no accounts, no personal data.
  - **Batch alerts** appear when 2+ independent reports flag the same vendor and test.
- **Spectrum fingerprints:** save a trusted product's absorbance spectrum. Later bottles are compared by spectral-shape correlation → MATCH, PARTIAL or MISMATCH, to catch refills and counterfeits.
- **CSV export** from History, for the laptop / Office Kit console.

## Honest limits (say these before judges find them)
- All results are screening-grade. Accuracy depends on the reagents, a fixed geometry and your own calibration.
- Many rear cameras have IR-cut filters. Check with a TV remote, and try the front camera in the NIR screen.
- Hindi and Kannada speech needs the offline voice packs (Settings → Text-to-speech).
- FSSAI milk standards vary by state. The Float screen uses common minimums.

## Demo run sheet (rehearse this)
1. Turn on airplane mode. Point out the "Internet permission: NONE" pill.
2. Spectrum: point the slit at a CFL and tap Auto-calibrate. Show the 436/546 nm lines on the chart.
3. Milk detergent: capture the blank on pure milk + reagent, then the spiked sample → UNSAFE, spoken in Hindi. Scan again → CONFIRMED.
4. Polar: sweep the blank, then the honey sample → verdict (references saved during rehearsal).
5. Echo: a judge taps a coconut.
6. History: vendor memory → export the Purity Passport PDF.
