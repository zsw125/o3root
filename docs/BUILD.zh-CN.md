# 构建（中文版）

> 英文原版：[`BUILD.md`](BUILD.md)。若两者不一致，以英文版为准并同步本文件。

本项目**不用** Gradle。全部是 `javac` + Android SDK build-tools + NDK，用 shell
脚本驱动，便于检查与修改产物。

> **自动化贡献者：** 先读 [`AGENTS.md`](../AGENTS.md)（不变量与构建陷阱），提交
> 前运行 `bash scripts/verify.sh`。配置好下面的环境后，verify 还会重建 payload 与
> APK，并比对 hash/版本。

## 前提

| 组件 | 已验证 | 备注 |
|---|---|---|
| JDK | 17 / 21 / 27 | `javac`、`keytool`；App 目标为 Java 17 |
| Python | 3.10+ | 仅 `relink_ksu.py` / `mksymtab.py` 需要 `pyelftools` |
| Android SDK build-tools | r34 | `aapt2`、`d8`、`zipalign`、`apksigner` |
| `android.jar` | platform 34（ext12） | App 的编译 SDK |
| Android NDK | r26+（r28c 已验证） | 仅用于构建 native payload |
| Shizuku jars | 13.1.5 | `dev.rikka.shizuku:{api,provider,aidl,shared}` |
| 操作系统 | Linux / macOS / Windows | Windows 用 Git Bash；脚本自动选 `.exe` |

## 1. 工具链

```bash
bash scripts/fetch-toolchain.sh          # 下载到 .toolchain/
```

它会拉取：

- `android.jar`（来自 Google 的 `platform-34-ext12_r01.zip`）
- 当前系统对应的 `build-tools_r34`
- Maven Central 上的 Shizuku 13.1.5 `api`、`provider`、`aidl`、`shared` AAR
- `pyelftools` 到 `.toolchain/pylib`

离线时，把同样的文件手动放进 `.toolchain/` 即可 —— 具体命名见
`scripts/fetch-toolchain.sh`。

## 2. 环境

```bash
cp scripts/env.example.sh env.sh
$EDITOR env.sh
. ./env.sh
```

示例文件对每个变量都有说明。关键项：

```sh
ANDROID_JAR=.../android.jar
BUILD_TOOLS=.../build-tools/34.0.0
SHIZUKU_JARS=.../shizuku          # 含四个 *-classes.jar 的目录
NDK_CC=.../aarch64-linux-android35-clang      # Windows 下或带 .cmd
KEYSTORE=.../release.p12
KS_PASS=...
KEY_ALIAS=...
KEY_PASS=...
PAYLOAD=.../payload/build/libo3p.so           # 默认即此
```

## 3. Payload

```bash
bash payload/build-payload.sh
# build/o3p.bin      约 62 KB
# build/libo3p.so    payload + LKM + trailer
```

`build-payload.sh` 只需要 `NDK_CC`。KernelSU LKM 位于
`payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko`；默认输出与
`release/o3root2-v2.0.apk` 里的参考构建一致（`libo3p.so` 的
`sha256 98936d14…`）。

你也可以直接在设备上（root/shell）运行载荷：

```sh
adb push build/libo3p.so /data/local/tmp/o3p.bin
adb shell 'chmod 755 /data/local/tmp/o3p.bin && /data/local/tmp/o3p.bin 10000 14000 300 1 6 1 8000 800'
```

参数：`NFILL g_pairs g_delay g_mode g_att g_reps g_churn g_chwait`（上面的默认值
即已验证配置；`g_att` 是 UAF 尝试次数）。

## 4. App

```bash
bash app/build.sh
# build/o3root2.apk
```

它会编译 Java 源码、dex、链接资源（含 `assets/ksu-manager.apk`）、把 payload
打成 `lib/arm64-v8a/libo3p.so`，再做 zipalign 与签名。

还没有 keystore 时：

```bash
keytool -genkeypair -keystore release.p12 -storetype PKCS12 \
  -alias o3root -keyalg RSA -keysize 4096 -validity 3650 \
  -storepass 'change-me' -keypass 'change-me' -dname 'CN=O3 Root'
```

## 5. 安装与运行

1. 设备：开启开发者选项 → USB 调试；安装 **Shizuku**（或 ShizukuX），并以
   **shell 模式**启动（无线调试或 ADB）。验证 server：
   `ps -A -o UID,LABEL,ARGS | grep shizuku_plus_server` → `2000 u:r:shell:s0`。
2. 安装 APK。MIUI 有时会拒绝：`INSTALL_FAILED_USER_RESTRICTED`。要么在开发者
   选项里允许“USB 安装”，要么在已 root 的设备上
   `pm install -r -d /data/local/tmp/o3root2.apk`。
3. 打开 App。它会显示 Shizuku / SELinux / KernelSU 状态，并做固件门禁（内核
   release 字符串 + `yingtian`/`lhasa` fingerprint）。按提示授予 Shizuku 权限。
4. 点 **Continue to UID 0** 等待。成功会打印 `★ UIDNOW uid=0`、
   `KSU_INSMOD_OUT:[rc=0]`、`★ KSU_MODULE LIVE`，然后打开 KernelSU 管理器。
5. 如果没装内置 KernelSU 管理器，按钮会变成 **安装 KernelSU 管理器**，点击安装
   内置 APK（需要确认系统安装器提示）。

## 验证

```sh
adb shell 'grep kernelsu /proc/modules; getenforce; id'
# kernelsu 192512 1 - Live 0x0000000000000000 (O)
# Permissive
# uid=0(root) ... context=u:r:ksu:s0   （仅当 KSU 授权 shell root 后）
```

App 的 LogBox 会显示载荷每个阶段；用 **导出日志** 写到
`Android/data/o3.st.root1/files/o3root-log-*.log`。

## 排错

| 现象 | 处理 |
|---|---|
| `固件门禁未通过` | 内核/fingerprint 不匹配 —— 见 `docs/ARCHITECTURE.md` 的 kernel profile |
| 反复 `VERDICT NO_RECLAIM` | 竞态是概率性的；再点 Continue（或重启），通常 1–6 次内成功 |
| 加载 KernelSU 后断网 | SELinux 被切回 Enforcing 且 `policycap` 被污染 —— 点恢复按钮（`su -c setenforce 0`）或重启 |
| Shizuku 变成 root 模式而非 shell | 在没有 `su` 的设备上以 adb/无线调试模式启动 Shizuku；root 模式会被链路拒绝 |
| `INSTALL_FAILED_USER_RESTRICTED` | 允许 USB 安装，或用 root `pm install` |
| `KSU_CODEPATCH … -2` | 没找到 `setenforce` 重定位（唯一扫描也失败）—— 核对 LKM/构建 |

## 可复现性

参考构建（v2.0，release/o3root2-v2.0.apk）：

- APK `14,222,864` 字节
- `sha256 7e900dd3ed222fb3864f4172fd0add16627466b93f69996940a94e118dba7099`
- payload `libo3p.so` `1,488,284` 字节（配官方 LKM），
  `sha256 98936d14b5f50b1fc4a9b35741404088a33e1a1fe68042b61e8e948c31f87dfa`
