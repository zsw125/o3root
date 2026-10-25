# NOTICE — third-party components

> Chinese translation: [`NOTICE.zh-CN.md`](NOTICE.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

This repository is security research tooling. Run it only on hardware you own.
Several bundled or build-time components are third-party software with their own
licenses and redistribution terms.

## KernelSU (GPL-3.0)

- Project: KernelSU — https://github.com/tiann/KernelSU
- Version used: v3.3.1 (kernel API 32680)
- Files in this repository derived from KernelSU release artifacts:
  - `payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko` — official prebuilt LKM
    for GKI `android17-6.18` (release asset, aarch64).
  - `app/assets/ksu-manager.apk` — KernelSU manager APK from the same release.
  - `release/o3root2-v1.9.apk` embeds both the manager APK and the unmodified
    official LKM; the payload generates a *runtime copy* with rebased symbols
    (via `/proc/kallsyms`) plus one patched init immediate, then loads that
    copy. The offline `payload/ksu/relink_ksu.py` does the same rebasing.
- The runtime/offline rebasing only rewrites ELF symbol values (and one
  `mov` immediate in `.init.text`); it does not modify KernelSU source code. KernelSU itself is not
  vendored here — fetch it from the project if you need the sources, as
  required by GPL-3.0 when redistributing binaries.

## Shizuku client libraries (Apache-2.0)

- Project: Shizuku — https://github.com/RikkaApps/Shizuku-API
- Artifacts: `dev.rikka.shizuku:api`, `:provider`, `:aidl`, `:shared` (13.1.5),
  fetched from Maven Central by `scripts/fetch-toolchain.sh`.
- Not committed to this repository; compiled classes from these jars are
  embedded in `release/o3root2-v1.9.apk`.

## Android SDK (Android SDK License)

- `android.jar` (platform 34) and build-tools (`aapt2`, `d8`, `zipalign`,
  `apksigner`) are fetched by `scripts/fetch-toolchain.sh` and are not
  committed here.

## Python dependencies

- `pyelftools` (public domain / Unlicense) is required by
  `payload/ksu/mksymtab.py` and `payload/ksu/relink_ksu.py`.

---

## Disclaimer

Published for security research and education only; use it only on hardware you
own. The software is provided **as is, without warranty of any kind**, and the
authors accept **no liability** for device damage, data loss, warranty
invalidation, or legal consequences. You are responsible for complying with the
laws and contracts that apply to you. See the full disclaimer in
[`README.md`](README.md#disclaimer).

# 中文说明

本仓库是安全研究工具，仅可用于你本人拥有的设备。

- 内置的 KernelSU LKM 与管理器 APK 来自 KernelSU v3.3.1 官方发布物，遵循
  GPL-3.0；本仓库不包含 KernelSU 源码，再分发二进制时请自行遵守 GPL 条款
  （提供对应源码或指向官方仓库）。
- Shizuku 客户端库（Apache-2.0）由构建脚本从 Maven Central 拉取，不随仓库分发；
  发布 APK 中内含其编译产物。
- Android SDK 组件（android.jar、build-tools）由脚本拉取，不随仓库分发。
- 本仓库自有代码为 MIT（见 LICENSE）。作者不对任何设备损坏、数据丢失或
  法律后果负责。
