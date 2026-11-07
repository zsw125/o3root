# 实验性 O1 测试构建（`v3.3.5-o1-test`）

> 英文原版：[`TESTING_O1.md`](TESTING_O1.md)

本构建为运行 OS4.0.0.x 的小米 **O1（XRING）SoC** 设备提供第二套内核 profile，并包含
v3.3.5 的 O1 运行时改动：在 O1 上，App **不再加载 KernelSU LKM**，root 仅由
`@o3p_rootd` 提供。O1 profile 已**社区实测一次**（见下文）；维护者**未**独立复验，
且仅收到 `jinghu` 的报告。每次测试都可能弄坏启动，只测试你拥有且能自行恢复的设备。

## 验证状态

| 项目 | 状态 |
|---|---|
| O1 端到端 root | **社区实测一次**：GitHub issue #2，@Silas-02 于 2026-11-07 在 Xiaomi Pad 7 Ultra `jinghu` 上报告 —— HyperOS `OS4.0.0.16.XOXCNXM`，Android 17 / API 37，内核 `6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k`，**bootloader 锁定 / vbmeta green**。两次干净启动均在 10 s 内取得 `uid=0` 且 `@o3p_rootd` 常驻（尝试 1–2 次）。 |
| 维护者复现 | **未做** —— 维护者没有独立复现 O1 结果。 |
| `dijun` / `violin` | **未测试**。两者内置同一颗已分析内核（`sha256 0d9917ca…`），但没有任何运行报告。 |
| 宽松门禁构建 | 未测试。门禁也接受 `6.18.21-android17…-4k` 系列中的其他 OS4.0.0.x 构建；这些构建沿用 O1 `selinux_state` 地址且未逐构建比对，属于已知风险。 |

不要把这理解为对所有 O1 设备的支持。一台 `jinghu` 的报告只能说明整条链在该机可用；
不同的 O1 机型、OEM 构建或更新的 OS4.0.0.x 构建可能采用不同的内核布局。

## 谁能测试

| 设备代号 | OTA 示例 | 已分析内核 |
|---|---|---|
| `dijun` | OS4.0.0.19.XODCNXM | `6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k` |
| `jinghu` | OS4.0.0.16.XOXCNXM | 同上（已社区实测一次，见上文） |
| `violin` | OS4.0.0.14.XOTCNXM | 同上 |

三台 OTA 内核逐位相同（`sha256 0d9917ca…`）。门禁还接受内核仍处于
`6.18.21-android17…-4k` 系列的其他 OS4.0.0.x 构建：这些构建会打印
`固件门禁通过（O1 宽松模式：…）`，沿用 O1 `selinux_state` 地址且未逐构建比对。
系列之外的内核仍会被拒绝。

## 这个构建在 O1 上改了什么

- App 在导出 `O3P_SELINUX_PROFILE=o1` 的同时导出 `O3P_NOKSU=1`，载荷因此**跳过
  内置 KernelSU LKM**，root 由载荷自带的 `@o3p_rootd` 守护进程提供。这是刻意设计，
  不是回退（见下文“为什么跳过 LKM”）。
- UI：模块行显示 `内核模块: O1 模式不加载 LKM（@o3p_rootd 提供 root）`，
  **选择 .ko** 变为 **O1 不支持**；选择自定义 `.ko` 会被拒绝并提示
  `O1 模式不支持自定义内核模块`。KernelSU 管理器安装提示被跳过；即使没有
  KernelSU，成功也按 `uid=0` + `@o3p_rootd` 判定，所以按钮变成 **再次运行** 而不是
  **打开 KernelSU**。
- 运行日志新增两行 O1 专属信息：
  - 启动：`O1 模式：跳过内置 KernelSU LKM（sys_call_table 受 EL2 保护），root 由 @o3p_rootd 提供`
  - 成功：`✔ 已获得 UID 0（O1 模式：@o3p_rootd 常驻；KernelSU 未加载）`
- 其余为 v3.3 功能集：`selinux_state` 基址在运行时由 App hint 或
  `uname().release` 选择（`SELINUX_PROFILE kernel=… base=0x… src=env|uname`），
  门禁接受三个代号，隐藏 root 控制台包含在内。
- **payload 二进制未变**（`libo3p.so` 1,496,668 B，sha256 `fef5e065…`）；只有 App
  侧 Java 改动。

## 为什么跳过 LKM

XRING O1 上内核的 `sys_call_table` 位于 `Kernel code` 物理区间
`00200000-023bffff`（已分析构建映射在 PA `0x01560d60`），EL2 XHEE hypervisor
（`kmod_protect`）将其强制只读。因此 KernelSU LKM 的 `stop_machine` 页补丁无法
落地。但它的 `sys_enter` tracepoint hook 仍会把 `pt_regs->syscallno` 改写到
`ksu_dispatcher_nr` 槽，而该槽仍是 `__arm64_sys_ni_syscall` —— 于是 pid1 /
zygote / uid2000 的 `execve`、`newfstatat` 等从第二次运行起开始返回 `-ENOSYS`，
直到重启。首次 `v3.3.4-o1-test` 运行正好命中此问题。v3.3.5 因此在 O1 上跳过
LKM，改用不需要写 `sys_call_table` 的 `@o3p_rootd`。

## 要求

1. 设备已允许 ADB，且 **Shizuku**（或 ShizukuX）以 **shell 模式**运行：
   `shizuku_plus_server` 必须是 `uid=2000`、`u:r:shell:s0`。root 模式的 Shizuku
   （`u:r:ksu:s0`）无法 exec 载荷。
2. USB 调试或可用的 `adb`；一台可用于重装/救砖的电脑。
3. 对你在意的一切做完整备份。整条链**不写分区**且仅当前 boot 有效，但利用会触碰
   内核内存；仍可能 panic 或 boot loop，需要恢复手段。
4. **Android 17 / Shizuku 13.5.4 已知问题**：Shizuku 13.5.4 在该系统上会以
   `java.lang.AbstractMethodError: IProcessObserver.onProcessStarted(int,int,int,String,String)`
   崩溃。测试者需要打补丁的 Shizuku server；本项目不发布也不修复 Shizuku。

## 安装与运行

```bash
adb install -r release/test/o3root2-v3.3.5-o1-test.apk
# 如果 MIUI 拒绝：先在开发者选项里允许“USB 安装”
```

先核对产物：14,247,440 B，sha256
`405158cc9fbbd140da1ef38a83d8590e47a09b5480265554c54c3fa18a5ee6a8`
（versionCode 26 / versionName `3.3.5-o1-test`）。

1. 打开 **O3 Root**。版本行必须显示 `v3.3.5-o1-test`；模块行必须显示
   `内核模块: O1 模式不加载 LKM（@o3p_rootd 提供 root）`，**选择 .ko** 必须显示
   **O1 不支持**。不应出现 KernelSU 管理器提示。
2. 点 **Continue to UID 0**。任何操作开始前会先弹两步警告：风险对话框，然后是
   启用 **开始提权** 的勾选框。两者都接受才会开始；取消不会触碰设备。
3. 检查日志。已分析构建会打印
   `固件门禁通过（O1 实验性：<codename> / 6.18.21-…-g1d099fcb35e0-…）`；其他
   OS4.0.0.x 构建会打印 `固件门禁通过（O1 宽松模式：<codename> / <kernel>）`。
   若显示 `固件门禁未通过`，停止并反馈日志——不要盲目重试。
4. 按提示授予 Shizuku 权限；载荷会在上述确认后启动。等待 20–120 s。
5. 成功标记（精确的预期顺序见下一节）：`★ UIDNOW uid=0`、
   `ROOTD … FULLROOT armed`，随后 `CLIENT`/`SHELL`，以及成功行
   `✔ 已获得 UID 0（O1 模式：@o3p_rootd 常驻；KernelSU 未加载）`。
   **O1 上没有 `★ KSU_MODULE LIVE`、也没有 KernelSU 管理器，这是预期行为**
   —— 该模式下没有 KernelSU 可加载。
6. 验证 root：长按版本行 → 通过三道确认 → 运行 `id`（必须输出 `uid=0`）和
   `getenforce`。O1 上 `getenforce` 终态为 **Permissive** 属设计行为：stage 0
   清掉 `enforcing`，`policycap` 保持不变，且没有 KernelSU 去重新开启 Enforcing。
   O1 不提供 `su`（未加载 KernelSU）；请使用控制台或 `@o3p_rootd`。
7. 点 **导出日志**（或 **复制**）并保留原始日志文件。

## 预期日志（O1）

O1 profile 应按顺序打印：

1. `固件门禁通过（O1 实验性：<codename> / <kernel>）`（未分析的 OS4.0.0.x 构建为
   `O1 宽松模式`）
2. `SELINUX_PROFILE kernel=… base=0x… src=env`
3. `SLIDE_CALC …`、`SELWRITE … SEL_OK` 和 `ENFORCE:[0]`
4. `CREDPTR … PTR_OK`
5. `★ UIDNOW uid=0`
6. `ROOTD … fork` / `ROOTD … sock ready` / `ROOTD … FULLROOT armed`
7. `CLIENT` / `SHELL`

预期**不会出现**：`KSU_MODULE LIVE`、`KSU_INSMOD_OUT`、前台出现 KernelSU 管理器、
以及任何 `su`。SELinux 终态为 Permissive（见上）。

## 需要反馈什么

开 GitHub issue（或在 release 下评论），附上：

- **APK 文件名及其 sha256**（`o3root2-v3.3.5-o1-test.apk` 对应 `405158cc…`）
  —— 用于锁定确切产物；
- 设备代号、完整 `Build.FINGERPRINT`、`uname -r` / 内核行，以及 OS4 构建号；
- App 版本（`v3.3.5-o1-test`）、Shizuku 版本与模式（`shell` 还是 root），以及是否
  出现上述 Shizuku 13.5.4 Android 17 崩溃；
- **导出的原始日志文件**（直接附上——摘要不够），包含
  `固件门禁` / `SELINUX_PROFILE` / `UIDNOW` / `ROOTD` 行；
- 结果：成功、反复 `VERDICT NO_RECLAIM`、panic/重启，或预期序列中缺少某一步；
  尝试次数与取得 root 的耗时；
- **同一 boot 内第二次运行**是否仍正常（没有失效的 `execve`/`newfstatat`），以及
  `getenforce` 是否以 Permissive 结束且网络可用。

请不要把 O1 上缺少 KernelSU 或其管理器当作缺陷上报：那正是 v3.3.5 的预期行为。
只有 O1 启动/成功行缺失或未取得 root 时才需要报告。

## 回滚

重启。整条链仅当前 boot 有效；不写任何分区，重启也会恢复 SELinux Enforcing。
如果设备 boot loop，使用你惯用的 fastboot/EDL 恢复流程。

## 已知风险（运行前必读）

- 维护者**未**独立验证 O1 硬件。`jinghu` 的一份社区报告是全部硬件证据；
  `dijun` / `violin` 从未运行整条链。
- O1 内核的 `posix_cpu_timer_del` 比 yingtian 旧；UAF 时序可能不同，可能需要更多
  重试，也可能失败。
- 在宽松（未验证）的 OS4.0.0.x 内核上，O1 `selinux_state` 地址是猜的：其他静态
  地址可能已移动，地址错了可能让设备 panic。能用已分析构建时优先用。
- O1 终态为 **Permissive** SELinux（没有 KernelSU 重新开启 Enforcing）：重启前
  MAC 全系统关闭。这不是日常使用的姿态；用完后重启。
- 实验性构建会编译隐藏 root 控制台。通过它的三道确认意味着你接受 root 命令可能
  损坏设备。
- O1 上没有 KernelSU 就意味着没有管理器、没有 `su`、没有 KSU allow-list。root 仅
  `@o3p_rootd`，重启即消失。
