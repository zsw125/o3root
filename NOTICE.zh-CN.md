# NOTICE — 第三方组件（中文版）

> 英文原版：[`NOTICE.md`](NOTICE.md)。若两者不一致，以英文版为准并同步本文件。

本仓库是安全研究工具。请只在你本人拥有的硬件上运行。仓库内置或构建时依赖的若干
组件属于第三方软件，各有自己的许可证与再分发条件。

## KernelSU（GPL-3.0）

- 项目：KernelSU — https://github.com/tiann/KernelSU
- 使用版本：v3.3.1（内核 API 32680）
- 本仓库中源自 KernelSU 发布产物的文件：
  - `payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko` —— GKI
    `android17-6.18` 的官方预编译 LKM（release 资产，aarch64）。
  - `app/assets/ksu-manager.apk` —— 同一 release 的 KernelSU 管理器 APK。
  - `release/o3root2-v3.0.apk` 同时内置管理器 APK 与未修改的官方 LKM；载荷在
    加载时生成*运行时副本*（经 `/proc/kallsyms` 重定位符号）再加载；v3 默认不改 init
    立即数（`O3P_CODEPATCH=1` 可回退旧补丁）。离线 `payload/ksu/relink_ksu.py` 做同样的重定位。
- 运行时/离线重定位只改写 ELF 符号值（以及 `.init.text` 的一处 `mov` 立即
  数），不修改 KernelSU 源码。KernelSU 本身并未 vendored 到本仓库 —— 如需源码请从上游获取；按
  GPL-3.0 要求，再分发二进制时应一并满足源码提供义务。

## KernelSU-Next（GPL-3.0）

- 项目：KernelSU-Next —— https://github.com/KernelSU-Next/KernelSU-Next
- 使用版本：v3.4.2
- 仓库中来自 KernelSU-Next 发布产物的文件：
  - `payload/lkm/aarch64-next-android17-6.18_kernelsu.ko` —— GKI
    `android17-6.18` 官方预编译 LKM（aarch64，473,552 B）。
- 与上文相同，运行时重定位与一条 init 立即数补丁只作用于生成的副本；仓库内
  文件保持未修改。

## SukiSU-Ultra（GPL-3.0）

- 项目：SukiSU-Ultra —— https://github.com/SukiSU-Ultra/SukiSU-Ultra
- 使用版本：v4.2.1-r2（管理器 41068）
- 仓库中来自 SukiSU-Ultra 发布产物的文件：
  - `payload/lkm/sukisu-aarch64-android17-6.18.ko` —— GKI `android17-6.18`
    官方预编译 LKM（aarch64，569,752 B）。
- 生成的副本同样做运行时重定位与 init 立即数补丁。

## Shizuku 客户端库（Apache-2.0）

- 项目：Shizuku — https://github.com/RikkaApps/Shizuku-API
- 制品：`dev.rikka.shizuku:api`、`:provider`、`:aidl`、`:shared`（13.1.5），由
  `scripts/fetch-toolchain.sh` 从 Maven Central 拉取。
- 不提交到本仓库；`release/o3root2-v3.0.apk` 中内嵌了由这些 jar 编译出的类。

## Android SDK（Android SDK License）

- `android.jar`（platform 34）与 build-tools（`aapt2`、`d8`、`zipalign`、
  `apksigner`）由 `scripts/fetch-toolchain.sh` 拉取，不提交到本仓库。

## Python 依赖

- `pyelftools`（public domain / Unlicense）为 `payload/ksu/mksymtab.py` 与
  `payload/ksu/relink_ksu.py` 所需。

---

## 免责声明

本仓库仅用于安全研究与教育目的，且只可用于你本人拥有的硬件。本仓库自有代码为
MIT（见 [`LICENSE`](LICENSE)）；软件按“现状”提供，不附带任何明示或默示担保。
作者与贡献者不对设备损坏、数据丢失、保修失效或法律后果承担任何责任；请自行确认
你所在司法辖区与设备/运营商条款下的合规性。完整免责声明见
[`README.zh-CN.md`](README.zh-CN.md#免责声明)。
