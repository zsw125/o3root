# 第三方组件（中文版）

> 英文原版：[`NOTICE.md`](NOTICE.md)。若两者不一致，以英文版为准并同步本文件。

本仓库内置或依赖以下第三方的作品。所有权利归各自作者；下面的许可证描述仅作概括，
完整条款请查阅上游项目。

## 内置的二进制 / 资产

### KernelSU

- 上游：https://github.com/tiann/KernelSU（GPL-3.0）
- 内置文件：
  - `payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko` —— KernelSU v3.3.1
    release 的官方预编译 LKM（未修改的二进制，375,328 字节）。
  - `payload/lkm/aarch64-next-android17-6.18_kernelsu.ko` —— KernelSU-Next
    v3.4.2 的官方 LKM（未修改的二进制，473,552 字节），上游
    https://github.com/KernelSU-Next/KernelSU-Next（GPL-3.0）。
  - `payload/lkm/sukisu-aarch64-android17-6.18.ko` —— SukiSU-Ultra v4.2.1-r2
    的官方 LKM（未修改的二进制，569,752 字节），上游
    https://github.com/SukiSU-Ultra/SukiSU-Ultra（GPL-3.0）。
  - `app/assets/ksu-manager.apk` —— KernelSU 管理器 APK（v3.3.1、32680、
    13,637,206 字节），在没有管理器时由 App 安装。
- 修改是**由我们的载荷在运行时施加**的，不是改本仓库里的文件：每个未定义符号被
  改写为当前内核的绝对地址。v3.0 加载的是**未修改**的模块（不打 init 补丁）：
  载荷保持 `policycap` 干净，KernelSU 自己的 `setenforce(1)` 不影响网络。旧
  `O3P_CODEPATCH=1` 路径（v1.7–v2.1 默认）还会把 `.init.text` 中的一条指令
  （`+0x144`，SukiSU 为 `+0x178`）从 `mov w0,#1` 改为 `mov w0,#0`；只改生成的
  副本。若你再分发预打过补丁的 `.ko`，请注意 GPL-3.0 义务。

### Shizuku

- 上游：https://github.com/RikkaApps/Shizuku（Apache-2.0）
- 以 Maven 制品 `dev.rikka.shizuku:{api,provider,aidl,shared}:13.1.5` 使用
  （由 `scripts/fetch-toolchain.sh` 拉取，不在本仓库 vendored）。

## 构建时依赖

| 组件 | 许可证 | 用途 |
|---|---|---|
| Android SDK build-tools（aapt2、d8、zipalign、apksigner） | Android SDK License | App 构建 |
| Android platform 34 `android.jar` | Android SDK License | App 编译 |
| Android NDK（clang） | LLVM/NDK licenses | payload 构建 |
| JDK | GPL-2.0 with Classpath Exception | javac、keytool |
| Python 3 | PSF license | 构建脚本 |
| pyelftools | public domain（Unlicense） | `relink_ksu.py`、`mksymtab.py`、`kernel_cfg_btf.py` |
| capstone | BSD-3-Clause | `kernel_cfg_btf.py` 反汇编 |
| vmlinux-to-elf | GPL-3.0 | 外部可选：从原始内核恢复符号 |

## 不包含的内容

- 小米 OTA 包与厂商内核镜像（专有）。仓库只包含内核 SHA-256、由它派生的符号表/
  偏移，以及分析文档。
- `android.jar`、build-tools、NDK 与 Shizuku AAR —— 由脚本在本机拉取。
