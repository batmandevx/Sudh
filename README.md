<p align="center">
  <strong>🔬 SHUDDH — The People's Lab</strong>
</p>

<h3 align="center">
  Your phone is a laboratory. No internet. No cloud. No excuses.
</h3>

<p align="center">
  <a href="https://github.com/batmandevx/Sudh/releases/latest"><img src="https://img.shields.io/badge/Download-Latest%20APK-brightgreen?style=for-the-badge&logo=android" alt="Download APK"></a>
  <a href="https://github.com/batmandevx/Sudh/releases"><img src="https://img.shields.io/badge/All%20Releases-blue?style=for-the-badge&logo=github" alt="All Releases"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208+-34A853?style=flat-square&logo=android&logoColor=white" alt="Android 8+">
  <img src="https://img.shields.io/badge/Architecture-arm64-orange?style=flat-square" alt="arm64">
  <img src="https://img.shields.io/badge/AI-100%25%20On--Device-blueviolet?style=flat-square&logo=tensorflow&logoColor=white" alt="On-Device AI">
  <img src="https://img.shields.io/badge/Internet-Not%20Required-critical?style=flat-square" alt="Offline First">
  <img src="https://img.shields.io/badge/Kotlin-123%20files%20•%2031k%20LOC-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/License-Open%20Source-green?style=flat-square" alt="Open Source">
</p>

---

## 🌍 The Problem

> **68.7% of milk samples** in India are non-conforming with food safety standards.
> **70% of spices** are adulterated.
> **163 million people** drink water that doesn't meet safety norms.
>
> — *FSSAI survey data, Lok Sabha reply, July 2026*

Lab testing costs ₹500–2,000 per sample. The nearest FSSAI lab is hours away. Results take weeks. Most people simply don't test — they trust and hope.

**Shuddh turns every smartphone into a food, water, and health safety laboratory.**

No internet connection. No cloud account. No subscription. No data leaves the phone — ever.

---

## ⚡ What Shuddh Does — in 60 Seconds

| You want to... | Shuddh uses... | You get... |
|---|---|---|
| **Test milk** for water, detergent, urea, starch | Flash + camera + spectroscopy | Contamination type + exact % added water |
| **Check honey** is real vs syrup | Polariser rotation via gyroscope | Purity % between your saved references |
| **Test water** for chlorine, nitrate, fluoride, arsenic | Reagent strip reader + spectrum | mg/L readings from Beer–Lambert standards |
| **Spot adulterated spices** (turmeric, salt) | Colorimetric screening (540 nm) | Lead chromate / iodine presence |
| **Check fruit** for wax coating | Specular gloss + hot-water haze | Waxed vs. natural |
| **Check grain purity** | Computer vision blob analysis | % stones, husks, insects, broken grains |
| **Test cooking oil** freshness | CIELAB colorimetry | Reuse count + discard advisory |
| **Tap a coconut** to check fill | Acoustic FFT + ring-down | Full / half / empty |
| **Knock a watermelon** | Resonant frequency + dye test | Ripe / unripe + injected dye check |
| **Detect mosquitoes** by wingbeat | Harmonic frequency analysis | Aedes / Anopheles / Culex species ID |
| **Check for anaemia** | Nail-bed / eyelid pallor via camera | Pale / borderline / healthy screening |
| **Measure surface moisture** | Ultrasonic chirp echo analysis | Moisture index for walls, soil, grain |
| **Monitor air quality** | Flash nephelometry + Ångström exponent | Particulate level + smoke vs. dust |
| **Know when water is safe to drink** | Mic + barometer (altitude-adjusted) | WHO-safe boiling timer with warnings |
| **Detect falls & send SOS** | Accelerometer pattern detection | Auto-alert via Bluetooth mesh + SMS + GPS |
| **Count pressure cooker whistles** | Mic + FFT tonal detection | Alert at target count + cooking timeline |

**Every single test runs entirely on the phone.**

---

## 🧪 The Science — Real Instruments, Not Gimmicks

Shuddh isn't a toy. Each module implements published analytical methods using the phone's existing hardware as laboratory instruments:

### Instrument Suite

| Module | Hardware Used | Scientific Method | What It Measures |
|---|---|---|---|
| **Spectrum** | Flash + camera + CD grating | Column-binned RGB → wavelength calibration via CFL Hg lines (435.8 / 546.1 / 611.6 nm) → absorbance via Beer–Lambert law | Chlorine, nitrate, iron, fluoride (mg/L). Milk detergent, starch, urea |
| **Polar** | Flash + camera + gyroscope + 2 polarisers | Malus's law least-squares fit on gyro-integrated rotation angle | Honey purity % (between saved references) |
| **NIR** | IR blaster + camera | 38 kHz lock-in detection, A = log₁₀(S_ref / S_sample) | % added water (between pure and diluted references) |
| **Purity** | Multi-sensor fusion | Five-stage test: spectrum + lock-in + echo + magnetometer → three-way vote (sensors + physics + AI) | Comprehensive milk safety with 0–100 calibration ladder |
| **Echo** | Microphone | Hann-window FFT (8192 samples), parabolic peak, −20 dB ring-down | Coconut fill, watermelon ripeness, container level |
| **Strips** | Torch + camera | Pad colour ÷ white patch → CIELAB → projection onto shade chart | pH, hardness, chlorine, nitrate, arsenic, fluoride |
| **Nami** | Speaker + microphone | 10 linear chirps (2→18 kHz) → matched-filter → transfer function H(f) in 16 bands | Surface moisture (soil, walls, grain, laundry) |
| **Hawa** | Flash + camera at 90° | Lock-in nephelometry (6 OFF/ON cycles, ambient cancellation, MAD outlier rejection) | Air particulates + RGB Ångström exponent (smoke vs. dust) |
| **Float** | Lactometer reading | CLR = LR + 0.2·(T − 27), SNF via Richmond formula | % added water vs. FSSAI minimum SNF |
| **Magneto** | Magnetometer | \|B − B₀\| over all three axes (direction-aware) | Food-grade vs. magnetic steel (304/316 detection) |

### What Makes This Credible

- **Robust frame averaging** — Outlier frames (shadow, flicker, auto-exposure) rejected via MAD rule; 20% trimmed mean
- **Accelerometer-gated capture** — Only steady frames are used
- **Kalman-filtered tracking** — Smooth, jitter-free measurements across all vision modules
- **Confidence scores on every verdict** — Quality & calibration checks weighted 1.5×; inconclusive capped at 40
- **Hysteresis** — One bad scan is *provisional*. Two matching scans within 15 min = **CONFIRMED**
- **Evidence ladder** — Every verdict shows: observation → pattern → quality checks → hypothesis → confirmation
- **The app says INCONCLUSIVE** when a check fails — dark band, clipped reference, no calibration, off-chart colour. **It never guesses.**

---

## 🤖 On-Device AI — Three Models, Zero Cloud

Everything runs locally using MediaPipe LLM Inference. Nothing is sent anywhere.

| Model | Size | Role | Speed |
|---|---|---|---|
| **Qwen2.5-1.5B-Instruct** | int8, GPU | Conversational AI, recipes, detailed answers | ~16 tok/s |
| **Qwen2.5-0.5B-Instruct** | int8, CPU | Tool router — picks the right function call | Fast routing |
| **Phi-4-mini 3.8B** | int8, GPU | Pro mode — deep recipes, long-form answers | ~4 tok/s |
| **MiniCPM5-2B** | LiteRT-LM | Multimodal chat via Kotlin 2.2 | ~7 tok/s |

### Ask Shuddh — Your Private AI Assistant

Voice or text → the router picks from **12+ tools** (kitchen score, vendor history, contaminant facts, open instruments, mesh broadcast, download report, analyse photo, recipes, calculator, unit converter, calendar, shopping list, timer, alarm, flashlight, QR code, and more) → Kotlin executes on local data → the chat model answers using only those facts.

- **Recipes** with one-tap step timers
- **Multi-step plans** ("…and then…") executed step by step
- **Plain-language test requests** — "Check my milk" opens the right instrument
- **Markdown rendering** in chat (headings, bullets, bold, code)
- **Repetition guard** — streaming stops at the first repeated line
- **Web search** (optional, off by default) — Bing RSS, no API key, no conversation history sent

---

## 🌾 CrispRoots — Farm Intelligence Hub *(v5.6)*

Shuddh isn't just for kitchens. **CrispRoots** brings the same offline-first, sensor-driven philosophy to **Indian farmers**:

- **3D Farm Twin** — Live weather, soil view, and crop overlay
- **Crop Ranking** — Profit-vs-risk analysis with yield waterfall charts
- **Weather-Year Range** — Historical comparison with back-test vs. real harvests
- **Sell Timing** — Live mandi prices + news integration
- **Gene Trait Lab** — CRISP fit score for crop varieties
- **Leaf Doctor** — MobileNetV2 plant disease classifier (80 classes, 85% top-1 accuracy, runs on-device)
- **Animal Health** — Vet-sign checker, camera breathing rate, herd book
- **Field Kit** — GPS area walk, slope, compass rows, sunlight, canopy vigour, spray window
- **Crop Calendar** — Season planning with government scheme finder + form helper

---

## 🛡️ Trust & Safety Layer

### For Your Family
- **Kitchen Health Score** — Weekly safety score right on the home screen
- **Family Exposure Ledger** — Track nitrate, fluoride, arsenic intake as % of daily safe limits, per family member, over 30 days
- **True Price** — See the real price per litre after accounting for dilution, and the money lost per month
- **Milkman Ledger** — Daily litres, water-test calendar, fair month-end bill you can send to your milkman
- **Milk Watch** — Learns a vendor's baseline. Flags changed days and slow drift (CUSUM algorithm)
- **Pantry & Expiry Tracker** — OCR scan or manual entry, countdown rings, food-saved score, restock predictions, seasonal buy timing
- **Anaemia Screening** — Nail-bed or eyelid pallor measurement (flash + white-card normalised erythema index)
- **Family Care** — One-tap pre-filled SMS to a family member
- **Boil Guard** — Hears a pot reach rolling boil, uses barometer for altitude-adjusted WHO timing
- **Whistle Counter** — Learns your cooker's pitch, alarms at N whistles, predicts the next one

### For Your Community

- **🐝 Hive — Offline Community Safety Network**
  - **Bluetooth Mesh** — Multi-hop chat, alerts, photos, and SOS over BLE 5. No pairing, no internet.
  - **Vendor Purity Seals** — Smoothed 0–100 trust score. Trusted / Watch / Avoid with QR verification.
  - **Outbreak Watch** — Anonymous household illness reporting. Area-level 72h vs. 14-day baseline. Automatic escalation for clusters (≥5 people in ≥3 homes, ≥2 jaundice cases, illness + unsafe water)
  - **Batch Alerts** — Auto-triggered when 2+ independent reports flag the same vendor
  - **Purity Beacon** — A shop broadcasts its seal to passers-by via BLE
  - **Alert QR** — Share warnings by scanning a QR code. No server, no accounts, no personal data.

### For Accountability

- **Purity Passport** — SHA-256 hash-chained PDF report generated on the phone. Tamper-evident.
- **Vendor Memory** — "Ramesh Dairy: failed 3 of your last 9 scans. First failure… Trend: worsening"
- **File a Complaint** — Pre-filled FSSAI complaint in English or Hindi, PDF attached
- **CSV Export** — Full scan history for further analysis
- **Haptic Verdicts** — Two pulses = safe. Three = caution. One long = danger.

---

## 📱 Vision Lab — Machine Vision at 15 FPS

Four MediaPipe models running simultaneously on GPU+CPU:

| Model | What It Does |
|---|---|
| **EfficientDet-Lite0** | 80-class object detection with auto-suggestion for matching Shuddh test |
| **Hand Tracking** | 21-joint × 2 hands, 9 gestures (👍 ✌️ ☝️ 👌 ✊ ✋ 🤘 🤙), air-drawing with point gesture |
| **Face Mesh** | 478-point mesh, smile/blink/head-turn detection |
| **Body Pose** | 33-joint tracking, posture analysis, arm-raise rep counter |

Also: **Label Lens** (sharpest-frame selection, OCR with digit-confusion repair, FSSAI licence/expiry/MRP/veg-mark extraction), **Grain Scan** (blob analysis for stones, husks, insects, broken grains), **Fruit Shine** (wax detection via specular gloss).

---

## 🏥 Guardian — Emergency Safety *(v5.2+)*

- **Fall Detection** — Free fall → impact → stillness pattern recognition
- **30-second check** — "Are you OK?" prompt before triggering SOS
- **Multi-channel SOS** — Bluetooth mesh + SMS with GPS coordinates + siren + torch Morse SOS
- **Disaster Beacon** — Broadcast/receive SOS from nearby phones
- **Mosquito Radar** — Harmonic wingbeat analysis, mains-hum rejection, time-of-day Bayesian prior

---

## 🗣️ Five Languages, Fully Offline

| Language | Voice (TTS + Speech) | UI Labels | Verdicts & Advice |
|---|---|---|---|
| 🇬🇧 English | ✅ | ✅ | ✅ |
| 🇮🇳 Hindi | ✅ | ✅ | ✅ |
| 🇮🇳 Kannada | ✅ | ✅ | ✅ |
| 🇮🇳 Telugu | ✅ | ✅ | ✅ |
| 🇮🇳 Tamil | ✅ | ✅ | ✅ |

110 voice phrases + 140 on-screen labels, fully translated. The AI assistant can switch languages mid-conversation.

---

## 🔒 Privacy — Uncompromising

```
┌─────────────────────────────────────────────────┐
│  INTERNET PERMISSION: NONE (by default)         │
│                                                 │
│  • AI runs on the phone                         │
│  • Scans, photos, audio never leave the phone   │
│  • No accounts, no sign-up, no telemetry        │
│  • ML Kit's telemetry uploader excluded at build │
│  • Hash-chained history stored only on device    │
│  • Optional map/web search gated by switches     │
│  • Even the community mesh uses BLE, not WiFi    │
└─────────────────────────────────────────────────┘
```

---

## 🎨 Design — Dark Aurora Theme

- Slowly drifting colour fields with frosted-glass cards
- Spectrum-gradient branding with animated orb and shimmering rays
- Per-instrument animated diagrams (prism rays, rotating polariser, IR waves, sound bars, sonar pings, bobbing lactometer)
- Spring-animated verdict gauge with pulsing labels
- Celebration burst (safe), shockwaves (unsafe), ripple (caution)
- Light/dark mode with five accent themes (Emerald, Saffron, Violet, Rose, Ocean)
- Full haptics engine with heartbeat, sonar ping, moisture rumble, Geiger clicker, and more
- 12 achievement badges with progress rings, XP bar, and animated shine

---

## 🚀 Quick Start

### Install from APK (easiest)

```bash
# Download the latest release
# https://github.com/batmandevx/Sudh/releases/latest
# Transfer to phone → open → allow "install unknown apps"
```

### Build from source

```bash
git clone https://github.com/batmandevx/Sudh.git && cd Sudh
./scripts/fetch_models.sh          # downloads 6 public MediaPipe models → app/src/main/assets
./gradlew assembleDebug            # requires JDK 17, Android SDK (platform 35)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Optional: On-device LLMs

Push model files to `/sdcard/Android/data/com.shuddh.lab/files/models/`:
- `Qwen2.5-1.5B` — main chat
- `Qwen2.5-0.5B` — tool router
- `Phi-4-mini` — pro answers
- `MiniCPM5-2B` — multimodal

See **AI Models** in the app for exact file names. The app never downloads anything automatically.

---

## 📋 Honest Limits

> We'd rather you know these before any judge finds them.

- All results are **screening-grade**. Accuracy depends on reagents, fixed geometry, and your own calibration.
- Many rear cameras have IR-cut filters. Test with a TV remote; try the front camera for NIR.
- Hindi and Kannada speech need offline voice packs (Settings → Text-to-speech).
- FSSAI milk standards vary by state. Float uses common minimums.
- Anaemia screening bands are heuristics pending clinical calibration.
- Web search (when enabled) uses Bing's RSS feed for personal, non-commercial use.

---

## 📖 Full Changelog

<details>
<summary><b>v5.6</b> — CrispRoots farm hub, guided Purity, health hazards + PDF report, mesh photos</summary>

- **CrispRoots tab** — 3D farm twin with live weather, crop ranking (profit-vs-risk), yield waterfall, weather-year range, back-test vs real harvests, sell timing with live mandi + news, Gene Trait Lab with CRISP fit score, crop calendar, government schemes & form helper, rice-fish combos
- **Leaf Doctor** — MobileNetV2 plant disease classifier (80 classes, 85% top-1 / 98% top-3 on reference photos) + leaf damage %
- **Animal health** — Vet-sign checker, camera breathing rate, herd book
- **Field Kit** — GPS area walk, slope, compass rows, sunlight, canopy vigour, spray window
- **Purity wizard** — Liquid → milk type → animated test wizard with science explanation; educated-guess model calibrated on reference samples
- **Verdict** — Health-hazard body map, designed PDF report with QR, mesh broadcast
- **Mesh** — Redesigned chat with radar, alert cards and photo sharing over BLE
</details>

<details>
<summary><b>v5.5</b> — Simpler Home, AI opens the right test, Fruit Shine, smart pantry</summary>

- Home redesign: hero, Ask bar, four headline tests, single "All tools" entry
- Assistant routes plain-language test requests to the correct instrument
- **Fruit Shine Check** — Flash specular gloss + optional hot-water haze vs. unwaxed reference
- **Pantry** — Learned restock predictions, daily reminder, shopping list share, seasonal buy timing
- Full light/dark theming with Settings toggle
- Purity: training mode, robust verdicts, hidden AI second opinion
</details>

<details>
<summary><b>v5.4</b> — Purity five-stage sensor test, Milk Watch, Sensor Lab</summary>

- **Purity** — Five animated stages (spectrum + lock-in, ring light, echo, magnetometer, fused verdict), voice narration, haptics, health risks
- **0–100 calibration ladder** (isotonic fit, leave-one-out accuracy), per milk type
- Spoiled-milk (curd speckle) and detergent (foam half-life) checks
- **Milk Watch** — Learns a vendor's milk, flags changed days and slow drift (CUSUM)
- **Sensor Lab** — Phone-sensor-only checks with estimate + confidence
- Water clarity, bleach dosing (EPA), WHO ORS & danger signs
- Watermelon flow: knock ripeness + tissue dye check
</details>

<details>
<summary><b>v5.3</b> — Organised Home, Milkman Ledger, new tests, India impact card</summary>

- Home grouped into Health checks, Kitchen safety, Community, AI & learning with animated line icons
- **Mosquito Radar** — Harmonic wingbeat detection with species classification
- **Guardian** — Fall detector + 30s check + multi-channel SOS
- **Family Exposure Ledger** — Daily intake tracking as % of health limits
- **True Price** — Real cost per litre accounting for dilution
- **Milkman Ledger** — Water-test calendar + fair monthly bill
- New tests: lead chromate in turmeric (DPC, 540 nm), iodized salt (starch–iodine blue)
- **MiniCPM5-2B** on-device chat (LiteRT-LM, Kotlin 2.2)
</details>

<details>
<summary><b>v5.1</b> — Outbreak Watch, Anaemia Screen</summary>

- **Outbreak Watch** — Anonymous illness reporting, area-level epidemiological monitoring, automatic escalation, epidemic curve, action checklist, ASHA/health worker messaging
- **Anaemia Screen** — Nail-bed or eyelid pallor measurement, three-reading average, animated blood drop, iron-rich food guidance, link to Anaemia Mukt Bharat
</details>

<details>
<summary><b>v5.0</b> — Grain Scan, sharper Label Lens, vector Magneto</summary>

- **Grain Scan** — Blob analysis for purity %, donut chart, annotated photo
- **Label Lens** — Sharpest-frame selection (Laplacian focus), OCR digit-confusion repair
- **Magneto** — Direction-aware disturbance detection across all three axes
</details>

<details>
<summary><b>v4.9</b> — Boil Guard, Oil Check, Telugu & Tamil, new Hive</summary>

- **Boil Guard** — Acoustic boil detection + barometric altitude-adjusted WHO timing
- **Oil Check** — CIELAB browning index with discard advisory and RUCO collection pointer
- Telugu (తెలుగు) and Tamil (தமிழ்) full translation (110 voice phrases + 140 labels)
- **Hive** redesign: animated neighbourhood, safety-pulse donut, vendor trust board, mesh alerts
</details>

<details>
<summary><b>v4.8</b> — Confidence scores, robust averaging</summary>

- MAD-rule outlier rejection + 20% trimmed mean for all frame averaging
- Confidence score on every verdict with animated ring and fix hints
- Animated verdicts: celebration, shockwaves, ripples, trend chart
- Animated lactometer visualisation for Float
</details>

<details>
<summary><b>v4.7</b> — Pro model (Phi-4-mini), cleaner AI, Hawa lock-in</summary>

- **Phi-4-mini 3.8B** (MIT, int8, ~4 tok/s GPU) for long-form answers
- Markdown chat rendering, repetition guard, exact action results
- **Hawa 2.0** — Lock-in nephelometry with ambient cancellation, Ångström exponent
</details>

<details>
<summary><b>v4.6</b> — Vision Lab, Kalman filters, agentic AI, haptics, whistle 2.0</summary>

- Four MediaPipe models at ~15 fps (object detection, hand tracking, face mesh, body pose)
- Kalman-filtered smoothing for all vision landmarks
- Agentic AI with recipes, calculator, unit converter, calendar, notes, shopping list
- Full haptics engine: heartbeat, sonar ping, moisture rumble, Geiger clicker
- Whistle counter 2.0 with animated cooker and cooking timeline
</details>

<details>
<summary><b>v4.5</b> — Pulse (PPG heart rate), Nami 2.0</summary>

- PPG heart rate from fingertip + flash, BPM by autocorrelation, HRV (RMSSD)
- Nami 2.0: per-band SNR gating, inverse-variance weighting, level-invariant projection, phone calibration
</details>

<details>
<summary><b>v4.4</b> — Pantry, Badges, Guide, Settings redesign</summary>

- Pantry & expiry tracker with countdown rings and food-saved score
- 12 achievement badges with XP bar
- FSSAI DART adulteration guide (16 home tests)
- Share score card (1080×1350 Kitchen Health image)
</details>

<details>
<summary><b>v4.3</b> — Real map, mesh rooms, new History</summary>

- Full-screen OpenStreetMap with GPS heatmap and nearby-places search
- Mesh rooms with invite QR and private channels
- Redesigned History with hash-chain badge, live search, vendor memory
</details>

<details>
<summary><b>v4.2</b> — Media search, video moments, new Home</summary>

- Gallery photo search via ML Kit labels + OCR + Universal Sentence Encoder
- Video Moment Finder (1 fps sampling, relevance timeline)
- Redesigned Home with Kitchen Health hero, quick actions, instrument groups
</details>

<details>
<summary><b>v4.0</b> — On-device AI, new sensors, new brand</summary>

- Two local LLMs (Qwen2.5 1.5B + 0.5B) via MediaPipe LLM Inference
- Ask Shuddh pipeline: voice → router → 12 tools → chat model
- Vision: ML Kit image labeller + OCR (Latin + Devanagari)
- Label Lens: FSSAI licence, expiry, MRP, veg mark extraction
- Accelerometer-gated frames, gravity level, light sensor, wave-to-capture
- Whistle Counter, new brand (Unbounded + Manrope), dark aurora theme
</details>

<details>
<summary><b>v3.x</b> — Bluetooth mesh, edge AI, Echo/Nami accuracy, Magneto, PDF</summary>

- Shuddh Mesh (BLE 5): multi-hop chat, alerts, SOS, Purity Beacon
- On-device speech recognition + offline intent engine (EN/HI/Hinglish)
- Echo: 4-feature classifier trained on-device, probability and separation score
- Nami: 3×10 chirps pooled, MAD outlier rejection, 95% confidence interval
- Magneto: 5-sample median filter, food-grade steel detection
- Downloadable PDF with infographic summary + hash-chain integrity badge
</details>

<details>
<summary><b>v2.x</b> — App structure, Hive, corroboration engine</summary>

- Five-tab layout with onboarding, step tracker, "How it works" panels
- Verdict screen with evidence ladder, cross-check, vendor memory, complaint filing
- Corroboration engine: same sample read by 2+ instruments → corroborated or flagged
- Community Hive: QR-based alert/seal exchange, batch alerts
- Spectrum fingerprints: spectral-shape correlation to catch refills and counterfeits
</details>

---

## 🤝 Contributing

Shuddh is built for the people who need it most. Contributions welcome:

1. **Fork** the repo
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing-feature`)
5. **Open** a Pull Request

---

## 📊 Project Stats

- **123 Kotlin source files** • **31,000+ lines of code**
- **10 scientific instruments** using phone hardware
- **4 on-device AI models** (zero cloud dependency)
- **5 languages** with full voice + UI + verdict support
- **Bluetooth mesh** for offline community safety
- **SHA-256 hash-chained** tamper-evident records
- **Zero internet required** for any core function

---

<p align="center">
  <em>Built in India. For India. For everywhere that needs clean food and safe water.</em>
</p>

<p align="center">
  <strong>Every family deserves to know what they're eating.</strong>
</p>
