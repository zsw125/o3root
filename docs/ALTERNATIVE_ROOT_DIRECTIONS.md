# yingtian（小米平板 9 Pro Max / XRING O3）替代 root 方向调研

> **Historical (superseded 2026-10-25)** — written before CVE-2026-98260 was found, when an
> unlock + Magisk route looked like the only path. It is kept for the research record only;
> the current mainline is the in-app chain in `README.md` / `docs/ARCHITECTURE.md`
> (release v3.0). Do not follow its unlock recommendations for this device.


> **状态更新（2026-10-23 18:28）**：用户决定暂不走解锁路径，保留经验待后续进展；本文件仅作备查，不执行解锁。归档要点见 `notes/05_ALTERNATIVE_DIRECTIONS.md`（原工作区文件，未随仓库分发）。

- 日期：2026-10-23
- 设备：Xiaomi Pad 9 Pro Max（代号 yingtian，型号 M367FC），**玄戒 O3（XRING O3）自研 SoC**，HyperOS 4.0 / Android 17.0，当前系统 OS4.0.16.0.XBMCNXM
- 前提：现有 EnneaO3V2 的 APK 路线与脚本路线均已在 4.0.16 上失效（见 [OTA_4.0.16_kernel_report.md](OTA_4.0.16_kernel_report.md)）、bootloader 锁定、设备已拔出

## 一、方向总览（按可行性排序）

| # | 方向 | 结论 | 关键前提 / 代价 |
|---|---|---|---|
| **A** | **非官方解锁 Bootloader（LittlenineEnna / XRING O3 方案）→ Magisk/KernelSU** | **目前唯一现实可行的 root 路线** | 需要作者的解 BL 材料/工具，且需支持 Pad 9 Pro Max；解锁会清空数据，非官方方案有变砖风险 |
| B | 内核 1-day LPE 移植（DirtyFrag CVE-2026-43284 / GhostLock CVE-2026-43499） | **已排除：4.0.16 内核两个漏洞都已修复**（二进制级核实，见第二节） | — |
| C | 等待 Ennea / 社区推出针对 4.0.16 的新工具或新漏洞 | 不确定；作者公开表态偏悲观（“O3 越狱不会有了”），但 2026-10 又完成了 18 Fold 的解锁 | 时间不可控 |
| D | 现成免解锁 root 工具（KSuRoot / DirtyFrag 系） | **不适用**：它们基于 GhostLock/DirtyFrag，且只支持 6.1/6.6/6.12（及部分 5.x）内核族；本机为 6.18 / Android 17 | — |
| E | 降级回 4.0.15（恢复旧漏洞） | **不可行**：bootloader 锁定（`ro.boot.flash.locked=1`、verified boot green）；且 vbmeta 带 ARB 计数 | — |
| F | 维持非 root 用法（Shizuku/ADB 自动化） | 兜底方案 | 无需 root，但功能受限 |

## 二、内核补丁核实：三条 1-day 路线全部关闭

| CVE | 漏洞 | 修复版本 | 本机 4.0.16 内核证据 | 判定 |
|---|---|---|---|---|
| **CVE-2026-49881** | Telecom InCallController 逻辑缺陷 → system_server/network_stack 注入 | 2026-09 | 真机日志 `TELECOM_ROUTE_UNAVAILABLE … the entry is patched` | ❌ 已修复 |
| **CVE-2026-64560** | posix-cpu-timers 非 leader exec() 竞态 UAF | 2026-07（commit `920f893f735e…`；backport 6.1.180/6.6.147/6.12.100 等） | 内核包含补丁新增的 `timer_lock_sighand()` 助手，反汇编逐条匹配（pid_task 重试 / `dmb ishld`=`smp_rmb` / `brk #0x800`=`WARN_ON_ONCE`）；device 上 72 次 stage1 全 miss | ❌ 已修复 |
| **CVE-2026-43284** | Dirty Frag：xfrm ESP 对 shared skb frag 原地解密 → page-cache 写原语 | 6.18.28（commit `71a1d9d985d2…`） | `esp_input`/`esp6_input` 反汇编中同时检查 `frag_list` 与 `SKBFL_SHARED_FRAG`（`tbnz w9,#1` → 走 `skb_cow_data` 慢路径） | ❌ 已修复 |
| **CVE-2026-43499** | GhostLock：rtmutex `remove_waiter()` 用 `current` 导致 futex PI UAF | 6.18.27（commit `3bfdc63936dd…`） | `remove_waiter` 使用 `waiter->task`（`[waiter+0x50]`）而非 `current`，并对 `waiter_task->pi_blocked_on`（`str xzr,[task,#0xa20]`）清零 | ❌ 已修复 |

> 说明：4.0.16 安全补丁级别为 2026-09-01，且该内核明显回合入了 2026-07 之后的内核 CVE 修复；三条公开 1-day 均已在其中。

## 三、方向 A 详解：解锁 Bootloader → Magisk

### 3.1 依据

- 2026-10 多家媒体报道：**Xiaomi 18 Fold（lhasa，XRING O3 同款自研芯片）已被 LittlenineEnna 非官方解锁 bootloader**；Xiaomi 18 Pro 也由 realMlgmXyysd 解锁。说明 XRING O3 平台的解锁并非不可能。
- Pad 9 Pro Max（yingtian）与 18 Fold **同属 XRING O3 首发设备**，但 **目前尚未检索到该平板解锁成功的公开报告**——需要向作者/社区确认是否已支持。
- 该方法的材料通常通过作者社群（酷安/QQ/Telegram/网盘）分发，工作区中不包含。

### 3.2 解锁后的 root 步骤（已备好所需镜像）

`prep/` 目录已准备好：

| 文件 | 说明 |
|---|---|
| `prep/Magisk-v30.7.apk` | Magisk v30.7（11,613,864 B，sha256 `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5`） |
| `prep/init_boot-OS4.0.16.0.XBMCNXM.img` | 本机 4.0.16 原厂 init_boot（header v4，ramdisk 2,616,413 B）—— Magisk 需要修补它 |
| `prep/boot-OS4.0.16.0.XBMCNXM.img` | 本机 4.0.16 原厂 boot |
| `prep/vbmeta-OS4.0.16.0.XBMCNXM.img` | 本机 vbmeta（rollback_index=1,788,220,800） |

流程（解锁成功后）：

1. 手机端安装 `Magisk-v30.7.apk`；
2. 把 `init_boot-OS4.0.16.0.XBMCNXM.img` push 到手机（如 `/sdcard/Download/`）；
3. Magisk →「安装」→「选择并修补一个文件」→ 选该 init_boot → 生成 `magisk_patched-*.img`；
4. `adb pull` 回电脑；进入 fastboot：`fastboot flash init_boot magisk_patched-*.img`；
   - 若开机校验失败，再执行（以实际 fastboot 支持为准）：
     `fastboot --disable-verity --disable-verification flash vbmeta prep/vbmeta-OS4.0.16.0.XBMCNXM.img`
5. 重启；Magisk 显示已安装即 root 完成；随后可按需安装 Zygisk / LSPosed 模块。
6. （可选）解锁后可刷 4.0.15 fastboot 官方包，恢复旧的 Ennea 漏洞利用环境；但更简单的是直接用 Magisk。

### 3.3 风险

- 非官方解 BL：可能变砖、失去保修、触发 AVB/ARB；**务必先备份数据**（解锁通常清空数据）。
- 解锁后 Play Integrity/部分银行 App 可能不可用，需要额外隐藏。
- 材料来源需自行甄别（陌生人的 LK/fastboot 工具存在植入风险）。

## 四、结论

- 免解锁的内核利用路线（DirtyFrag / GhostLock / posix-cpu-timer / Telecom）在 4.0.16 上**全部被修复**，短期内没有可行 exploit。
- **唯一现实的 root 方向是拿到作者（LittlenineEnna）的 XRING O3 非官方解锁材料，先解锁 bootloader，再用 Magisk 刷 init_boot。**
- 已准备好解锁后所需的一切镜像与 Magisk；需要你确认：① 能否从作者社群拿到 XRING O3 / Pad 9 Pro Max 的解锁工具；② 是否接受清空数据与变砖风险。

## 五、外部参考

- Xiaomi 18 Fold / 18 Pro 非官方解锁报道：<https://ximitime.com/xiaomi-18-pro-and-fold-get-unofficial-bootloader-unlock-101065/>、<https://en.gizchina.it/2026/10/xiaomi-18-pro-fold-bootloader-sbloccato/>
- O3 越狱前景讨论（含“别把宝押在越狱上”）：<https://post.smzdm.com/p/a3m0e33d/>
- KSuRoot（GhostLock 工具，支持内核 6.1/6.6/6.12，不含 6.18）：<https://github.com/hmascs/KSuRoot>
- 免解锁 root 漏洞列表（ABL cmdline injection / Adreno 等，主要适用于高通平台，不适用 XRING O3）：<https://github.com/lokey0905/rootWithoutUnlockBootloaderList>
- 本报告引用的内核修复 commit：
  - CVE-2026-64560 `920f893f735e92ba3a1cd9256899a186b161928d`
  - CVE-2026-43284 `71a1d9d985d26716f74d21f18ee8cac821b06e97`（6.18.28）
  - CVE-2026-43499 `3bfdc63936dd4773109b7b8c280c0f3b5ae7d349`（6.18.27）