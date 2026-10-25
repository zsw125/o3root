# yingtian OS4.0.16.0 OTA 内核提取与 EnneaO3V2 通路核验报告

> **Reference (partially superseded 2026-10-25)** — the OTA extraction and kernel constants are
> still accurate and used by the payload; the EnneaO3V2 route audit in this file is obsolete
> (both CVEs it covers are patched in OS4.0.16). Mainline: release v3.0.


- 生成时间：2026-10-23 14:50 (UTC+8)
- 对象：yingtian-ota_full-OS4.0.16.0.XBMCNXM-user-17.0-4215476d07.zip（10,373,766,105 B，A/B 全量 OTA）
- 目的：从 OTA 提取内核，核验现有 EnneaO3V2 越狱通路（脚本版 / APK 自动版）在该内核上是否可用

---

## 结论速览

1. **内核已完整提取并通过三重校验**：payload.bin 的 SHA-256 与 OTA 元数据 `FILE_HASH` 一致；所有解出分区的整体哈希与 payload manifest 中的 `new_partition_info.hash` 一致；重建出的 vmlinux 符号表与已知数据地址吻合。
2. 内核版本从 **`6.18.21-android17-5-g284297031c53-abogki553682993-4k`** 变为 **`6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`**（构建于 2026-09-23）。
3. **现有通路依赖的数据地址与结构偏移全部未变**：`fasync`、`init_task+0x7c8`、`notif_lock`、`selinux_state`、`misc_list`、`task_struct.cred/comm`（0x908/0x918）、`cred` 布局（uid 0x08、euid 0x18、security 0x80、size 0xb8）逐项核对一致。
4. **唯一破坏点：KASLR slide 检测常量。** `ksys_read` 入口的唯一调用点返回地址（静态）从 **0xffffffc080177190** 变为 **0xffffffc08016614c**（`__arm64_sys_read+0x1c`）。因此：
   - **脚本版**：只需改这 1 个常量即可继续用（已生成 `patched/scripts/` 副本）。
   - **APK 自动版**：必须改 `classes2.dex` 里的同一常量并重新签名；不改则 slide 检测会被自身校验明确拒绝（`SLD reject slide=…`），不会静默使用错误地址。
5. **尚未验证**：CVE-2026-64560 在该内核中是否已被修复；运行期 tracefs 权限 / SELinux 状态；竞态时序参数是否需要重新调参。

---

## 1. 提取流程与完整性校验

### 1.1 payload 结构（实测）

- `payload.bin` 在 OTA zip 内偏移 5373，未压缩（stored），大小 10,373,757,059 B。
- 头部为 **大端**：`CrAU` + u64 version=2 + u64 manifest_size=359,316 + u32 metadata_sig_size=267。
- 操作数据区起点 = payload 偏移 **359,607**（24 + manifest + metadata signature）；`InstallOperation.data_offset` 相对于该起点。
- 分区操作类型：`REPLACE_XZ(8)`、`REPLACE(0)`、`REPLACE_BZ(1)`；每个操作的 32 字节 SHA-256 实际校验的是**压缩后的存储块**（已实测确认）。
- 共 42 个分区；manifest 声明 `ota-type=AB`、`pre-device=yingtian`、SDK 37、安全补丁 2026-09-01、post-build `Xiaomi/yingtian/yingtian:17/CP2A.260605.016/17OS4.0.260928.215007722.XRPDCN.S`。

### 1.2 完整性结果

| 校验项 | 结果 |
|---|---|
| `payload.bin` SHA-256 | `5e654adf2ffeaca9d501d7d8524df178f32425521a55b53c05886fe7939fb9d5` = OTA `FILE_HASH` ✅ |
| boot / init_boot / vendor_boot / dtbo / vbmeta / vbmeta_system / vbmeta_vendor | 逐个与 manifest `new_partition_info.hash` 一致 ✅ |
| 反解出的内核符号地址 | 与已知静态表吻合（见第 3 节）✅ |

### 1.3 已解出分区

| 分区 | 大小 | SHA-256 |
|---|---|---|
| boot.img | 100,663,296 | `1e3a1111aaf7e17f843dd541ef663a46b3b1891d25568f905cbcb364cc911ce4` |
| init_boot.img | 16,777,216 | `a43380b0ad3952ea343cd78d2b7a87ef2a38a74301de678f6276c448bf15f3de` |
| vendor_boot.img | 205,520,896 | `e29635021ece4006112b6ca40aba65280e923d6c8ae2d0e5a4074faf6187b945` |
| dtbo.img | 25,165,824 | `7768f0db007399ba5dffb7256b80160447299dc223f6f2fe31978b9cc18e5d8b` |

---

## 2. 内核信息

- `boot.img`：header v4，kernel_size=42,940,928，ramdisk_size=0（内核在 boot，无 ramdisk），cmdline 为空。
- 内核为**未压缩的裸 arm64 Image**（0x38 处为 `ARMd` 魔数），image_size=0x29a0000。
- 版本字符串：

```
Linux version 6.18.21-android17-5-g805aa6adea19-abogki565292102-4k (kleaf@build-host)
(Android (15323222, +pgo, +bolt, +lto, +mlgo, based on r584948c) clang version 22.0.1 ...)
#1 SMP PREEMPT Wed Sep 23 09:23:45 UTC 2026
```

- 关键配置（从内核内嵌 IKCONFIG 解出，共 6681 项）：`CONFIG_KPROBES=y`、`CONFIG_KALLSYMS=y`、`CONFIG_KPROBE_EVENTS=y`、`CONFIG_DEBUG_INFO_BTF=y`、`CONFIG_SECURITY_SELINUX=y`、`CONFIG_TRACING=y`、`CONFIG_RANDOMIZE_BASE=y`、`CONFIG_ARM64_VA_BITS=39`、`CONFIG_MODULES=y`。
- 产物：`ota_extract/kernel`（42,940,928 B，SHA-256 `cde87cd7…c5ad88`）、`ota_extract/vmlinux.elf`（重建符号的 ELF，139,243 个带地址符号）。

---

## 3. 通路核验：基准 vs 4.0.16 内核

基准值来自 [README.md](../README.md)、`scripts/run-macos.sh` 与 APK 内注入的 `classes2.dex`；新值从重建的 `vmlinux.elf` / BTF / 内核配置中取得。

| 检查项 | 旧基准（4.0.15/4.0.18 内核） | 4.0.16 内核 | 结论 |
|---|---|---|---|
| ksys_read 调用点返回地址（slide 检测用） | 0xffffffc080177190 | **0xffffffc08016614c** | ❌ 已变 |
| ksys_read 调用点数量 | 1 | 1（`__arm64_sys_read` 内 BL @0xffffffc080166148） | ✅ 单点假设仍成立 |
| `fasync`（direct_parent） | 0xffffffc08296cca8 | 0xffffffc08296cca8 | ✅ |
| `init_task+0x7c8`（direct_child） | 0xffffffc0826bfa08 | 0xffffffc0826bfa08 | ✅ |
| `avc_latest_notif_update.notif_lock`（阶段一零写目标 +1） | 0xffffffc082959629 | 0xffffffc082959629 | ✅ |
| `selinux_state` | 0xffffffc082959630 | 0xffffffc082959630 | ✅ |
| `misc_list` | 0xffffffc0827eec98 | 0xffffffc0827eec98 | ✅ |
| `task_struct.cred` / `comm`（BTF） | 0x908 / 0x918 | 0x908 / 0x918 | ✅ |
| `cred` 布局（BTF） | size 0xb8；uid 0x08、gid 0x0c、euid 0x18、security 0x80 | 完全相同 | ✅ |
| `posix_cpu_timer_del` 符号 / +72 窗口探针点 | 存在 / 有效 | 0xffffffc0800c079c / +72=0xffffffc0800c07e4（合法指令 `ldr x0,[x0,#0x968]`） | ✅ 可挂 |
| `__set_task_comm`（阶段二 cred 捕获） | 存在 | 0xffffffc0802a485c | ✅ |
| APK `classes2.dex` `KSYS_READ_RET` | 0xffffffc080177190 | 需要 0xffffffc08016614c | ❌ 必须改 |
| APK `classes.dex` `__arm64_sys_getpid` 静态地址（备用 Java KASLR 路线） | 0xffffffc08023cc30 | 0xffffffc08023d960 | ⚠️ 已变（仅备用路线） |

> **为什么数据地址不变、代码地址变了？** 新内核仍是 6.18.21 基线，数据/BSS 的布局未变（很可能是链接顺序与配置未动）；但该构建启用了 +pgo/+bolt/+lto/+mlgo，代码段很可能被重新排序，因此文本符号（ksys_read、__arm64_sys_getpid 等）地址发生移动。这正说明"现有通路的常量必须按内核构建重新核对"。

---

## 4. 唯一断点与最小修复

### 4.1 证据链

1. 反汇编 `__arm64_sys_read`（0xffffffc080166130）→ 唯一一条 `bl ksys_read` 位于 0xffffffc080166148，返回地址 = **0xffffffc08016614c**。
2. APK 注入的 `classes2.dex` 中，`SlideDetector.parseSlide` 内的 `const-wide` 字面量位于 **文件偏移 0x0dde**，值 = 0xffffffc080177190（旧值）。
3. `parseSlide` 的校验：`slide > 0`、`slide >> 44 == 0`、`slide & 0x1fffff == 0`，否则打印 `SLD reject slide=… lr=…` 并返回 0 —— 因此常量不匹配会**明确失败**，不会静默使用错误 slide。

### 4.2 修复清单

| 目标 | 操作 |
|---|---|
| 脚本版（推荐） | 把 `STATIC_KSYS_READ_LR` / `$STATIC_KSYS_READ_LR` 从 `0xffffffc080177190` 改为 **`0xffffffc08016614c`**（macOS 第 23 行；Windows 第 31 行）。已生成副本：`patched/scripts/` |
| APK 自动版 | 改 `classes2.dex` 偏移 `0x0dde` 的 8 字节字面量为 `4c 61 16 80 c0 ff ff ff`；重算 DEX 头部 checksum（偏移 8）与 SHA-1（偏移 12）；重打包并**重新签名**（签名变化 → 需卸载重装） |
| 备用 Java KASLR 路线（可选） | `classes.dex` 中两处 `__arm64_sys_getpid` 常量 0xffffffc08023cc30 → **0xffffffc08023d960**（`runMiuiKsymCacheProbe` / `runPerfKaslrProbe`） |
| 升级后首次运行 | 删除/忽略旧 `scripts/.slide_cache`，重新执行 detect |

> 说明：脚本版对 yingtian 机型的 `check` 只会给出"机型不是 lhasa"的告警（代码路径是 warn 而非 die），不影响后续步骤；dijun 黑名单仍会直接拒绝。

---

## 5. 可用性判定

| 通路 | 判定 | 说明 |
|---|---|---|
| 脚本版（adb + run-macos/run-windows） | **改 1 个常量后可用** | 阶段一/阶段二所依赖的数据地址、结构偏移、探针符号全部未变；只需新的 slide 检测常量 |
| APK 自动版（v50-auto） | **必须改 dex + 重签后才可用** | 不改则 SLD 检测被拒绝，流程无法推进到 Permissive（更拿不到 root）；app 原生库不含 kernel VA 常量，静态表由 Java/脚本传入 |
| 内核漏洞本身（CVE-2026-64560） | **未确认** | 仅凭二进制无法判定该内核是否已修复；需要用设备实测 stage1 命中率（或对比旧内核反汇编） |
| 运行期环境（tracefs / SELinux / 竞态时序） | **未验证** | 依赖刷机后的实际系统；`posix_cpu_timer_del+72` 对应指令已变化，竞态时序参数（如 TIMER_GOAL=41500）建议重测微调 |

---

## 6. 产物清单

| 路径 | 说明 |
|---|---|
| `ota_extract/kernel` | 解出的裸 arm64 内核 Image（42,940,928 B） |
| `ota_extract/vmlinux.elf` | 带 139,243 个符号的重建 ELF（可反汇编/查符号） |
| `ota_extract/boot.img` / `ota_extract/init_boot.img` / `ota_extract/vendor_boot.img` | 已校验的分区镜像 |
| `ota_extract/kernel_version.txt` | 版本字符串与 boot 头信息 |
| `ota_extract/extract_summary.json` | 提取过程与哈希校验结果 |
| `ota_extract/route_compatibility.json` | 通路核验的机器可读结果 |
| `patched/scripts/run-macos-4.0.16.sh` 等 | 已改新 slide 常量的脚本**副本**（原分发文件未改动） |
| [tools/](../tools/) | 提取/校验脚本（probe_payload、extract_payload、unpack_boot、verify_path、kernel_cfg_btf、hash_payload 等） |

---

## 7. 复现命令

```bash
python tools/extract_payload.py      # 从 OTA 提取分区（内置逐分区哈希校验）
python tools/unpack_boot.py ota_extract/boot.img   # boot v4 → kernel 裸 Image
python tools/kernel_cfg_btf.py       # 内核配置 + BTF 偏移 + 探针点
python tools/verify_path.py          # 静态地址表核验 + ksys_read 调用点
python tools/hash_payload.py         # payload.bin 与 FILE_HASH 比对（约 9 秒）
```

---

## 8. 注意事项

- 本报告针对 **OTA 文件中的内核**；手机当前仍是 OS4.0.15.0 系统（旧内核），两者不要混淆。
- 只要刷入 4.0.16，旧 `.slide_cache` 与 APK 自动版都会失效；脚本版需用 patched 副本重新 detect。
- CVE-2026-64560 的修复状态无法仅从内核文件确认，最终以设备实测为准。
- 原始分发包（APK、scripts/、README、SHA256SUMS）均未修改；如需 APK 补丁版（含重签），可在此基础上继续。

---

## 9. 补丁版 APK（追加交付）

已按第 4.2 节清单产出可直接安装的 APK 补丁版：

| 项 | 值 |
|---|---|
| 文件 | `patched/EnneaO3V2-4.0.16-v50-auto.apk` |
| 大小 | 25,031,847 B |
| SHA-256 | `e78c296161ab8dfc6f017e29d3cfb83843ee38837ed584243be3df97c9517575` |
| 签名 | 自签 RSA 4096，CN=Ennea O3V2 Patched，v1+v2+v3；apksigner/apksig 校验 `verified=true`（v3 通过） |
| 对齐 | `zipalign -c -p 4` 通过 |
| 改动 | classes2.dex 0x0dde（KSYS_READ_RET）；classes.dex 0x7c1338/0x7c1d84（备用路线 getpid）；DEX checksum/SHA-1 已重算 |

安装注意：签名与作者原版不同 → **必须先卸载原 App**（`adb uninstall o3.ennea.pocv1`）再安装；该 APK 仅适用于 4.0.16 内核，4.0.15 上请继续用原版 APK。

详细说明见 `patched/README.md`。

---

## 10. 真机实测与最终结论（2026-10-23 15:00–15:30）

### 10.1 设备状态

| 项 | 值 |
|---|---|
| 系统 | **OS4.0.16.0.XBMCNXM**（fingerprint `Xiaomi/yingtian/yingtian:17/CP2A.260605.016/OS4.0.16.0.XBMCNXM`） |
| 内核 | `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`（与 OTA 提取内核完全一致） |
| boot_id | `1b71aad5-73b0-4275-81a1-ce40712c2be5` |
| SELinux / root | Enforcing / 无 root |
| 已装 APK | 补丁版 SHA-256 `e78c2961…`（与 `patched/EnneaO3V2-4.0.16-v50-auto.apk` 一致） |

### 10.2 补丁验证成功

- App 自检（`/data/local/tmp/sld_result.txt`）：`slide=0x2c2ea00000`、`selinuxTarget=0xffffffecb1359629`；
- 补丁脚本 `detect`：`lr=0xffffffecaeb6614c` → `slide=0x2c2ea00000`，target 同值。
- → 新常量 **0xffffffc08016614c 正确**，App 与脚本交叉验证一致。

### 10.3 APK 自动路线：用户态入口已被 4.0.16 修复

真机日志（App 内）：

```
TELECOM_ROUTE_UNAVAILABLE Telecom completed the normal call path without UID 1000 controller delivery; the entry is patched.
... SHIZUKU_ROUTE_PASS ... ENTRY_GATE ... KASLR_ENV_GATE_FAIL value=unavailable
FAIL Shizuku shell route stopped: shell worker exited with code 2
```

即 CVE-2026-49881 的 InCallController 注入入口已被补丁关闭；shell 兜底又拿不到 slide，APK 自动路线在 4.0.16 上无法推进。

### 10.4 脚本路线：72 次竞态全部未命中

- 2 次 `stage1` × 3 轮 × 12 attempts = **72 次**，全部 `MISC_BRIDGE_MISS_PRESERVE`；
- 所有前置 gate 均通过（`TARGET_PROFILE_GATE_PASS profile=yingtian-os4-6.18.21-OS4.0.16.0.XBMCNXM`、`STAGE0_GATE_PASS slide=0x2c2ea00000`、`EXEC_REPEAT_GATE_PASS overlap=1`、探针 2/2 等），但 `ORDER1_RECLAIM_GATE changed_nodes=0`——目标页始终未被回收利用。
- 与“内核已修复”完全吻合。

### 10.5 内核修复的二进制证据（决定性）

修复补丁：Linux **`posix-cpu-timers: Prevent UAF caused by non-leader exec() race`**（commit `920f893f735e92ba3a1cd9256899a186b161928d`，2026-07-05；已 backport 至 6.1.180 / 6.6.147 / 6.12.100 / 5.10.262 等稳定版）。

- 补丁新增 `timer_lock_sighand()` helper：**重试** task 查找并锁 sighand，失败时 `smp_rmb()` + `WARN_ON_ONCE`；
- 本内核 `vmlinux` 中存在符号 **`timer_lock_sighand` @0xffffffc0804907c4**，反汇编逐条对应补丁源码：
  - `pid_task()` 查找 → `__lock_task_sighand()` → 失败**跳回重试**；
  - 查找失败时 `dmb ishld`（= `smp_rmb()`）→ 检查 `ctmr->head`/`timerqueue` → `brk #0x800`（= `WARN_ON_ONCE`）。
- `posix_cpu_timer_del` 与 `posix_cpu_timer_set` 均调用该 helper；`posix_cpu_timer_rearm` 内联了同一逻辑；
- 上游 v6.18.21 源码**没有**该 helper（仍用 `lock_task_sighand`）→ 说明 4.0.16 是**后加的安全补丁**。

### 10.6 最终结论

**4.0.16 同时修复了 CVE-2026-49881（用户态）与 CVE-2026-64560（内核 posix-cpu-timer UAF）；现有 EnneaO3V2 两条路线在该版本上均不可用。** 72 次全 miss 不是运气或参数问题，而是内核漏洞被关闭。

后续可行选项：

1. **等待工具作者发布针对新内核的新版本/新漏洞**（当前无利用面）；
2. 若有仍运行 **4.0.15**（或 lhasa 4.0.18）的其它设备，可继续使用原版 APK/脚本；
3. **降级不可行**：`ro.boot.flash.locked=1`、`ro.boot.verifiedbootstate=green`（bootloader 锁定，无法刷回旧固件）。

设备清理：已关闭 tracing、删除测试探针、恢复屏幕常亮设置；SELinux 仍 Enforcing、无 root、未做任何持久修改。
