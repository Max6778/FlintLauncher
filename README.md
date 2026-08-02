<div align="center">

<img src="logo.png" alt="FlintLauncher Logo" width="600"/>

### Run Minecraft: Java Edition on Android.

![License](https://img.shields.io/badge/license-GPL--3.0-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%208%2B-brightgreen?style=flat-square)
![Status](https://img.shields.io/badge/status-In%20Development-yellow?style=flat-square)
![Forks](https://img.shields.io/github/forks/FlintLauncherDev/FlintLauncher?style=flat-square)
![Stars](https://img.shields.io/github/stars/FlintLauncherDev/FlintLauncher?style=flat-square)

**Free. Open Source. Performance.**

[Download](#download) • [Features](#features) • [Compatibility](#compatibility) • [Contributing](#contributing) • [Community](#community)

</div>

---

## ⚠️ Legal Disclaimer

> **FlintLauncher is not affiliated with, endorsed by, or associated with Mojang Studios or Microsoft in any way.**
>
> Minecraft is a trademark of Mojang Studios. All Minecraft assets, game files, and intellectual property belong to their respective owners.
>
> FlintLauncher does **not** include, distribute, or provide Minecraft game files. You must **own a legitimate copy of Minecraft: Java Edition** through [minecraft.net](https://minecraft.net) to use this launcher. Using FlintLauncher to play without purchasing the game is a violation of the [Minecraft End User License Agreement (EULA)](https://www.minecraft.net/en-us/eula).
>
> By using FlintLauncher, you agree to comply with Mojang's EULA and all applicable terms of service.

---

## What is FlintLauncher?

FlintLauncher is a free, open-source Minecraft: Java Edition launcher for Android, forked from [ZalithLauncher](https://github.com/ZalithLauncher/ZalithLauncher). Built with one goal: **make Minecraft Java accessible to every Android user**, especially those on low-end or older devices that other launchers ignore.

No PC. No expensive phone. Just you and the game.

---

## Features

### Performance
- **Auto RAM profiling** — detects your device's memory and sets safe JVM limits automatically
- **GPU-based renderer switching** — picks the best renderer for your GPU (GL4ES, Zink, VirGL)
- **Optimized JVM arguments** — tuned garbage collection and heap settings for low-end hardware
- **CPU thread control** — prevents the JVM from choking slower processors

### Compatibility
- Supports **Android 8.0+** (API 26+)
- **ARM32 and ARM64** device support
- Works with **Mali, Adreno, PowerVR, and Dimensity** GPUs
- Compatible with **Forge, Fabric, Quilt, and NeoForge**

### 🌐 Open Source
- Fully open source under **GPL-3.0**
- Community-driven development
- Clean, documented codebase
- Easy to fork and contribute to

---

## Screenshots

> Screenshots coming soon. Want to contribute some? Open a pull request!

---

## Compatibility

| Device Tier | RAM | Android | Expected Performance |
|---|---|---|---|
| High-end | 6GB+ | 10+ | Excellent |
| Mid-range | 3–6GB | 8+ | Great |
| Low-end | 2–3GB | 8+ | Playable with optimizations |
| Very low-end | Under 2GB | Any | Not supported |

> ⚠️ Devices with under 2GB RAM or Android below 8.0 are not supported. This is a hardware limitation, not a launcher limitation.

### Recommended Low-End Mods (Auto-suggested on weak devices)

| Mod | Effect |
|---|---|
| Sodium | Massive FPS boost via renderer replacement |
| Lithium | Game logic optimization |
| FerriteCore | Significant RAM usage reduction |
| Starlight | Rewrites the lighting engine |
| EntityCulling | Skips rendering entities behind walls |

---

## Download

> Releases coming soon.

Watch this repo for updates, or [build from source](#building-from-source).

---

## Building from Source

FlintLauncher uses GitHub Actions for cloud builds — no PC required.

1. Fork this repository
2. Push any change to trigger a build
3. Go to **Actions → Build APK → Artifacts** and download your APK

Or build locally with Android Studio:

```bash
git clone https://github.com/FlintLauncherDev/FlintLauncher.git
cd FlintLauncher
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/`

---

## Contributing

FlintLauncher is open to everyone. Whether you fix a typo or add a whole feature — all contributions are welcome.

1. Fork the repo
2. Create a branch: `git checkout -b feature/your-feature`
3. Commit your changes
4. Open a Pull Request

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting.

---

## Community

> Discord server coming soon!

Use GitHub Issues for bug reports and feature requests in the meantime.

---

## Credits

FlintLauncher is built on the shoulders of these amazing projects:

- [PojavLauncher](https://github.com/PojavLaunch/PojavLauncher) — the original Android Java launcher,
- [ZalithLauncher](https://github.com/ZalithLauncher/ZalithLauncher) — the modern fork we built on
- [GL4ES](https://github.com/ptitSeb/gl4es) — OpenGL to OpenGL ES translation
- [Boardwalk](https://github.com/zhuowei/Boardwalk) — early iOS/Android Java MC research

---

## License

FlintLauncher is licensed under the **GNU General Public License v3.0**.
See [LICENSE](LICENSE) for full details.

---

<div align="center">

<img src="assets/logo.png" alt="FlintLauncher" width="300"/>

*Hello :D*

</div>

