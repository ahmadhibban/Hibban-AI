# Hibban AI 🤖⚡
### Autonomous AI Developer & Universal Computing System with Standalone Linux & OmniRoute Gateway

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-API%2028%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Python](https://img.shields.io/badge/Python-3.14.6-3776AB?style=flat-square&logo=python&logoColor=white)](https://python.org)
[![Node.js](https://img.shields.io/badge/Node.js-v26.4.0-339933?style=flat-square&logo=nodedotjs&logoColor=white)](https://nodejs.org)
[![OmniRoute](https://img.shields.io/badge/Gateway-OmniRoute%20v3.8.51-8A2BE2?style=flat-square)](https://github.com/ahmadhibban/Hibban-AI)
[![Virtualization](https://img.shields.io/badge/Engine-PRoot%20User--Space-orange?style=flat-square)](https://proot-me.github.io)
[![Release](https://img.shields.io/badge/Release-v3.2.0-brightgreen?style=flat-square)](https://github.com/ahmadhibban/Hibban-AI/releases/latest)
[![Author](https://img.shields.io/badge/Author-Ahmad%20Hibban-4f46e5?style=flat-square)](https://github.com/ahmadhibban)

> 🚀 **Direct APK Download**: [Download Hibban_AI.apk (Latest)](https://github.com/ahmadhibban/Hibban-AI/releases/latest) — *Install directly on any Android ARM64 device.*

**Hibban AI** is an unrestricted autonomous AI software developer and pair-programming assistant running natively on Android devices. It embeds a complete, real Linux distribution directly inside the application, enabling true autonomous terminal execution, script compilation, filesystem manipulation, and multimodal vision without requiring Termux, root permissions, or any external cross-app dependencies.

Developed with passion by **[Ahmad Hibban (আহমাদ হিব্বান)](https://github.com/ahmadhibban)**.

---

## 🏷️ Tags & Topics

`android` • `ai-assistant` • `autonomous-agent` • `omniroute` • `python3` • `nodejs` • `proot` • `standalone-linux` • `multimodal-vision` • `live-web-search` • `bengali-typography` • `arabic-rtl` • `coding-agent`

---

## 🌟 Key Highlights & Architecture

- **100% Standalone Embedded Linux System**:
  Bundles an authentic, isolated ARM64 Linux rootfs inside the application assets. On first launch, the environment automatically extracts to the app's private sandbox (`/data/data/com.gcode.ai/files/usr/`) with a native progress setup screen.
- **Built-in Local OmniRoute AI Gateway**:
  Runs OmniRoute v3.8.51 on `127.0.0.1:20128` powered by bundled Node.js v26.4.0. Delivers keyless unlimited access, intelligent model cascading, automatic failover handling (HTTP 429 quota exhaustion), and dynamic model loading.
- **Zero Termux Dependencies**:
  Runs out-of-the-box on ANY user's Android phone without requiring Termux or any cross-app permissions.
- **Real-Time Live Web Search**:
  Google AI Mode style live web search without requiring any API keys. Fetches real-time search snippets, titles, and links via fast on-device web scraping.
- **PRoot User-Space Virtualization Engine**:
  Executes shell commands and binaries via PRoot in user-space using `ptrace`/`seccomp`. Bypasses Android SELinux denials without requiring root access.
- **Minimal Core Bootstrap + On-Demand Package Installation**:
  Bundles core runtimes (GNU Bash 5.3, Python 3.14.6 + Pip, Node.js v26.4.0 + npm, GNU Coreutils, Curl, Git, and Android SDK tools). Any additional packages (such as `requests`, `numpy`, `beautifulsoup4`, npm packages) are installed autonomously by the AI on demand.
- **Autonomous Multimodal Vision**:
  Inspects and analyzes any photo, camera capture, or screenshot on the phone using multimodal vision via the `view_image` tool.
- **Foreground Service & CPU WakeLock**:
  Device screen can be turned off without interrupting autonomous tasks, supported by Android Foreground Service notifications and WakeLock permissions (`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `WAKE_LOCK`).
- **Dark Glassmorphism Interface & Modern Controls**:
  - Live status dot indicator: Solid Green (`Active`), Pulsing Yellow (`Connecting...`), Red (`Offline`).
  - Instant task Pause and Stop controls during background agent execution.
  - Intelligent Auto History Compaction to protect context window and optimize memory.
- **Bilingual Typography & Native Arabic RTL**:
  - **Bengali**: High-legibility *Kalpurush* font applied seamlessly to both chat rendering and input textarea.
  - **Arabic**: Classical *Amiri* calligraphy font with automatic Right-to-Left (RTL) alignment for Arabic answers and dynamic input direction switching.
- **Persistent Chat History**:
  Conversation state is securely saved and restored across app restarts and reboots.

---

## 📦 What's Inside the Bundled Environment?

| Component | Bundled Version / Tools | Purpose |
| :--- | :--- | :--- |
| **Shell** | GNU Bash 5.3 & Dash | Scripting and command orchestration |
| **Python Runtime** | Python 3.14.6 + Pip 26.2.1 | AI script execution, data manipulation, automation |
| **JavaScript Runtime**| Node.js v26.4.0 + npm | OmniRoute AI Gateway server, JavaScript execution |
| **AI Gateway** | OmniRoute v3.8.51 (`127.0.0.1:20128`) | Keyless AI routing, quota cascade, dynamic models |
| **Virtualization** | PRoot (ARM64 user-space) | Filesystem translation and sandboxed execution |
| **Core Utilities** | GNU Coreutils (`ls`, `cat`, `grep`, `sed`, `awk`, `find`, `tar`, `gzip`, `cut`, `sort`, `uniq`, etc.) | Essential file and system operations |
| **Networking** | Curl & Git with CA TLS Certificates | Secure HTTPS requests and repository cloning |
| **Android Tools** | `aapt`, `apksigner`, `d8` | On-device Android APK compilation and signing |

---

## 🛠️ Built-in Agent Tools

1. `run_command` — Execute bash/terminal commands in the built-in Linux environment, run Python/Node scripts, install packages, and manage files.
2. `search_web` — Live real-time internet search without requiring any search API keys.
3. `read_file` — Read any text file on device storage with 1-indexed line numbers and smart path resolution.
4. `write_file` — Create or overwrite files anywhere on device storage (`/storage/emulated/0`, Download, DCIM).
5. `edit_file` — Precise text substring replacement with collision prevention.
6. `list_directory` — Explore directories and subdirectories.
7. `view_image` — Multimodal vision inspection of photos and screenshots on device storage.

---

## 📱 Download & Releases

The prebuilt, signed APK is available in the **[Releases](https://github.com/ahmadhibban/Hibban-AI/releases/latest)** section:

| Asset | Platform | Version | Status |
| :--- | :--- | :--- | :--- |
| **[Hibban_AI.apk](https://github.com/ahmadhibban/Hibban-AI/releases/latest)** | Android (ARM64, Android 5.0+) | **Latest** | Stable Release |

---

## 📂 Repository Structure

```
Hibban-AI/
├── AndroidManifest.xml   # Standalone Android manifest (API 28, full storage & notifications)
├── assets/
│   ├── index.html        # Glassmorphic Chat UI, Agent engine, RTL & custom fonts
│   ├── setup.html        # Standalone extraction setup screen
│   ├── icon.png          # App 3D branding icon
│   ├── fonts/            # Kalpurush (Bengali) & Amiri (Arabic) fonts
│   ├── edge_tts_bundle.tar.gz.part_aa # Neural Edge TTS dependencies
│   ├── tts_synthesizer.py # Microsoft Edge Bengali neural voice synthesizer
│   ├── system_bootstrap.tar.gz.part_a[a-d] # Embedded Linux rootfs (split parts)
│   ├── omni_bootstrap.tar.gz.part_a[a-h]   # OmniRoute AI Gateway package
│   ├── node_libs_bootstrap.tar.gz          # Node.js runtime libraries
│   └── home_bootstrap.tar.gz.part_aa       # Bootstrap home files
├── src/com/gcode/ai/
│   ├── MainActivity.java # Setup progress extractor, OmniRoute lifecycle & WebView host
│   ├── WebAppInterface.java # Standalone PRoot process engine, live web search & vision bridge
│   ├── InAppSpeechRecognizer.java # Autonomous AudioRecord neural STT with fast silence cutoff
│   ├── HibbanAccessibilityService.java # Screen reading, UI node clicks & gestures
│   ├── HibbanNotificationListener.java # Notification stream auditing & interception
│   └── HibbanDaemonService.java        # Background OmniRoute watchdog & CPU wake engine
├── res/                  # App drawables, launcher icons, strings
├── build_apk.sh          # Native Android build script (aapt + javac + d8 + apksigner)
├── debug.keystore        # Keystore for release APK signing
├── HIBBAN_FEATURES.md    # Complete microscopic technical specification & capabilities
└── README.md             # Project documentation
```

---

## 🛠️ Build from Source (Android / Linux)

Prerequisites:
- Android SDK build tools (`aapt`, `d8`, `apksigner`)
- Java compiler (`javac`)
- `android.jar` (API 28+)

To compile, package, and sign the standalone APK:
```bash
bash build_apk.sh
```
The output APK will be placed in `apk/Hibban_AI.apk` and `/storage/emulated/0/Download/Hibban_AI.apk`.

---

## 👤 Author

**Ahmad Hibban (আহমাদ হিব্বান)**
- GitHub: [@ahmadhibban](https://github.com/ahmadhibban)
- Portfolio: [ahmadhibban](https://github.com/ahmadhibban/ahmadhibban)

---

## 📄 License

Open-source under the MIT License. Copyright © Ahmad Hibban.
