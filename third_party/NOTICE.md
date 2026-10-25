# Third-party components

> Chinese translation: [`NOTICE.zh-CN.md`](NOTICE.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

This repository bundles or depends on the following third-party work. All
rights remain with their authors; the licenses below are reproduced in spirit,
please consult the upstream projects for the full texts.

## Bundled binaries / assets

### KernelSU

- Upstream: https://github.com/tiann/KernelSU (GPL-3.0)
- Bundled files:
  - `payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko` — official prebuilt
    LKM from the KernelSU v3.3.1 release (unmodified binary, 375,328 bytes).
  - `payload/lkm/aarch64-next-android17-6.18_kernelsu.ko` — official
    KernelSU-Next v3.4.2 LKM (unmodified binary, 473,552 bytes); upstream
    https://github.com/KernelSU-Next/KernelSU-Next (GPL-3.0).
  - `payload/lkm/sukisu-aarch64-android17-6.18.ko` — official SukiSU-Ultra
    v4.2.1-r2 LKM (unmodified binary, 569,752 bytes); upstream
    https://github.com/SukiSU-Ultra/SukiSU-Ultra (GPL-3.0).
  - `app/assets/ksu-manager.apk` — the KernelSU manager APK (v3.3.1,
    32680, 13,637,206 bytes), installed by the app when no manager is present.
- Modifications are applied **at runtime by our payload**, not to the files in
  this repository: every undefined symbol is rewritten to an absolute address
  for the running kernel. v3.0 loads the module **unmodified** (no init patch):
  because the payload keeps `policycap` clean, KernelSU's own `setenforce(1)`
  does not break networking. The legacy `O3P_CODEPATCH=1` path (v1.7–v2.1
  default) additionally patched one instruction in `.init.text` (`mov w0,#1` at
  `+0x144`, or `+0x178` for SukiSU) to `mov w0,#0`; only the generated copy was
  ever modified. If you redistribute a pre-patched `.ko`, keep the GPL-3.0
  obligations in mind.

### Shizuku

- Upstream: https://github.com/RikkaApps/Shizuku (Apache-2.0)
- Consumed as Maven artifacts `dev.rikka.shizuku:{api,provider,aidl,shared}:13.1.5`
  (fetched by `scripts/fetch-toolchain.sh`, not vendored in this repo).

## Build-time dependencies

| component | license | usage |
|---|---|---|
| Android SDK build-tools (aapt2, d8, zipalign, apksigner) | Android SDK License | app build |
| Android platform 34 `android.jar` | Android SDK License | app compile |
| Android NDK (clang) | LLVM/NDK licenses | payload build |
| JDK | GPL-2.0 with Classpath Exception | javac, keytool |
| Python 3 | PSF license | build scripts |
| pyelftools | public domain (Unlicense) | `relink_ksu.py`, `mksymtab.py`, `kernel_cfg_btf.py` |
| capstone | BSD-3-Clause | `kernel_cfg_btf.py` disassembly |
| vmlinux-to-elf | GPL-3.0 | external, optional: recover symbols from the raw kernel |

## Not included

- The Xiaomi OTA package and the vendor kernel image (proprietary). Only the
  kernel SHA-256, symbol tables/offsets derived from it and the analysis
  documents are included.
- `android.jar`, build-tools, NDK and the Shizuku AARs — fetched locally by the
  scripts.
