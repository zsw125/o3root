# Agent 交接 —— 状态、历史与待办（中文版）

> 英文原版：[`AGENT_HANDOFF.md`](AGENT_HANDOFF.md)。若两者不一致，以英文版为准并同步本文件。
> 入口文档：[`AGENTS.zh-CN.md`](../AGENTS.zh-CN.md)（中文）/ [`AGENTS.md`](../AGENTS.md)（英文）。

这是自动化贡献者在 `AGENTS.md` 之后应读的第二份文档。它记录：实机上真正验证过
什么、哪些实验已经失败（避免重复）、剩余工作与大致工作量估计。

## 项目状态

| 项 | 值 |
|---|---|
| 发布 | v3.3，`release/o3root2-v3.3.apk`（14,243,344 B，sha256 `c98a9ced…`；O1 profile + 两步确认 + LogBox/LKM 修复，无控制台）。上一稳定版 v3.2 保留在 tag `v3.2` |
| 测试构建 | v3.2.1-console，`release/beta/o3root2-v3.2.1-console.apk`（14,243,344 B，sha256 `9cba365a…`；隐藏 root 控制台） |
| O1 测试构建 | v3.3.4-o1-test，`release/test/o3root2-v3.3.4-o1-test.apk`（14,247,440 B，sha256 `1a298929…`；O1 profile + 隐藏控制台；**仅静态分析**） |
| 已验证设备 | 小米 Pad 9 Pro Max `yingtian`，HyperOS `OS4.0.16.0.XBMCNXM`，内核 `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k` |
| 第二目标 | `lhasa`（Xiaomi 18 Fold）`OS4.0.26.0.XPNCNXM` —— 同内核 sha256 `cde87cd7…`（boot header/ramdisk 不同）；**未实机测试** |
| 持久化 | 无（仅当前 boot） |
| 入口路径 | Shizuku **shell** server（`uid=2000`，`u:r:shell:s0`）→ App → 载荷 |

App 日志中的成功标记（按顺序）：
`★ UIDNOW uid=0` → `KSU_INSMOD_OUT:[rc=0]` → `★ KSU_MODULE LIVE` →
`✔ 全部完成，正在打开 KernelSU…` → `top -> …me.weishu.kernelsu/.ui.MainActivity`。
健康状态：`getenforce` = `Enforcing`（v3；旧路径 `O3P_SELDIRTY=1` 会停在
Permissive）、网络可用、`boot_id` 未变。
日期/提交/致谢见 `docs/PRIOR-ART.md` §5–7；历史报告索引见 `docs/history/README.md`。

## 它是怎么走到这里的（压缩时间线）

1. **shell 域载荷**（`payload/legacy/t32.c`、`t34.c`）：CVE-2026-98260 的
   UAF → 用 kmalloc-8k 喷页回收 order-1 slab → 伪造 RB 节点 → 单次 8 字节写。
   stage 0 写 `selinux_state.enforcing=0`；stage 1 把 `task_struct.cred` 换成
   fake cred（uid 0、full caps）→ `uid=0`、rootd。
2. **KernelSU**：官方 `android17-6.18` LKM；加载器运行时经 `/proc/kallsyms`
   解析全部未定义符号（默认不需要内嵌符号表），失败时回退到 248 符号并集 +
   KASLR slide（slide 来自泄漏的 `init_user_ns`），然后从 fork 出的 shell
   执行 `insmod`。
3. **APK**：Shizuku `newProcess` 在 shell 域运行载荷；状态探测
   （`/proc/modules`、`getenforce`、`pm path`）；固件门禁；内置 KernelSU 管理器
   APK + `PackageInstaller` 流程；主题 UI；日志导出。
4. **v1.7 修复（断网）**：KernelSU 的 init 会调用 `getenforce()==0 →
   setenforce(1)`；与被污染的 `policycap[]` 叠加后冻结 Wi-Fi TX。加载器按
   `.init.text` 里的 `setenforce` 重定位定位到 `r_offset-4` 并改掉该立即数
   （`mov w0,#1`→`#0`；tiann/Next 0x144、SukiSU 0x178），让 KSU 不再把
   enforcing 拉回 1。冷启动已验证：KSU Live、管理器打开、网络正常。（v3.0 已不再
   打补丁，见第 7 条。）
5. **v1.8 守护（历史；v3.0 已移除）**：v1.8–v2.1 在 KSU 已加载且 SELinux 回弹
   到 Enforcing 时告警并提供一键 `su -c setenforce 0`；v3 不再需要——stage0 只清
   `enforcing`，Enforcing 是安全终态（见 §5 与 v3.0 说明）。
6. **v1.9 运行时加载器**：载荷在加载时经 `/proc/kallsyms` 解析 KernelSU 符号
   （回退：248 符号并集重链接 + slide），用 `.rela.init.text` 定位 `setenforce`
   补丁点；tiann / KernelSU-Next / SukiSU 三种 LKM 均可；v1.9 APK 已冷启动
   端到端验证。

7. **v3.0（2026-10-25 发布；v3.1 扩展了 App/LKM 路径）**：两段式取代单次写入。stage 0 在
   `selinux_state-7` 做 8 字节非对齐零写（清 `enforcing`，同时清相邻锁的
   pending/tail 字节；保留 `initialized`/`policycap`），stage 1 换 `task_cred`；
   KernelSU 经运行时
   kallsyms 加载、**不打 init 补丁**，其 `setenforce(1)` 不再影响网络，设备停在
   Enforcing。单次回收三写方案（v3a–v3c）连续三次 panic，仅保留在 `O3P_D3V3`
   后供分析。详见 `docs/V3-DESIGN.md` 与下文“v3.0”一节。

完整叙述与日志见 `docs/APP_O3_ROOT.md`（中文，原始开发日志；其中的目录名是
历史名称：`work/`→`payload/`、`app5/`→`app/`）。

## 已做实验 —— 不要重复

### 1. io_uring ring 页作为 order-1 回收源 —— **负面**

目标：用 `io_uring` 可 mmap 的 ring 页（unmovable 的 `GFP_KERNEL` 页）替代
kmalloc-8k 喷页（后者需要 8 KB 可控内容）。

- shell 域可以 `io_uring_setup`（无 seccomp，`io_uring_disabled=0`）。
- 对 `io_allocate_scq_urings`、`__alloc_pages_noprof`、
  `__alloc_frozen_pages_noprof`、`__vmalloc_node_range_noprof`、
  `vm_area_alloc_pages` 下 kprobe，setup 期间**零**命中。
- buddy tracepoint `kmem:mm_page_alloc` 加 `order>=1` 过滤：100 个 128 项 ring
  **零**事件。
- 完整回收实验（4000 个泄漏 timer + 1500 个 nr=64 ring，写 MAGIC）：0/4000
  命中；ring 页是 order-0/vmalloc。
- 结论：io_uring ring 页无法回收被释放的 `posix_timers` order-1 slab。现有
  喷页路径（`send(...,7872)` → kmalloc-8k）的 order 本来就是对的。

### 2. `AF_PACKET` TX ring —— **正面但 pre-root 不可用**

600 个 socket × `tp_block_size=8192`，mmap 并填充：回收命中 **3738/4000
（93.5%）**，是目前发现的最强 order-1 可写页原语。但是：

- shell 域 `CapEff=0`；`socket(AF_PACKET)` → `EPERM`。
- 所以它只在拿到 root 之后可用 —— 对 pre-root 回收步骤没有帮助；仍可用于
  第二阶段的写原语或作为更快的 post-root 原语。

### 3. 伪造 `cred_security_struct.sid=1`（kernel SID 路线）—— **负面**

目标：完全不写 `selinux_state`；用假 SELinux blob 让载荷进程变成 kernel 域
（sid=1），再通过 sysfs 执行 `setenforce 0`。

- 该策略里 SID 1 映射为 `u:object_r:unlabeled:s0`（不是 kernel）。
- KernelSU 的 `hook_file_open` 解引用假 blob 并 oops：
  `pc : hook_file_open.cold+0x4`，`Comm: yt2-parent`，avc 显示
  `scontext=u:object_r:unlabeled:s0`。
- KSU 已加载时该路线不可用。KSU 未加载时也许不崩，但最多也只是 `unlabeled`，
  不是 kernel。

### 4. 对 `selinux_state` 零写（避免 policycap 污染）—— **负面**

写 8 个零字节得到 `enforcing=0, initialized=0, policycap=0`。网络正常、
KernelSU 也能加载，但 `initialized=0` 破坏 `zygote` 对新 App 的
`selinux_android_setcontext`（`com.xiaomi.bluetooth` fatal），导致 KSU 管理器
起不来、设备在重启前不可用。

为什么不能写 `0x0100`（initialized=1、policycap=0）？写原语写 `[node]` 并解引用
`parent = value & ~3`；只有 `0` 能让 parent 保持 0。另一种安全选择是“受控页
指针”，而它的高位字节反正也会落进 `policycap`。

### 5. 旧 `policycap` 副作用（v3 之前的路径）—— **历史**

旧的 stage0 在 `selinux_state` 一次写 8 字节，`policycap[]` 会变成地址垃圾；
之后任何 `setenforce 1` 都会冻结 `wlan0` TX（RX 仍工作），而 shell 域又无法
`setenforce 0`（没有 `mac_admin`）——恢复手段：KernelSU `su -c setenforce 0`
或重启。**v3 默认避免了这一切**：只在 `selinux_state-7` 清一个字节（见 v3.0
说明）；旧行为仅保留在 `O3P_SELDIRTY=1` 后面。

### 6. 标签修复 —— **正面，操作说明**

被废弃的零写 boot 期间在 `/data/local/tmp` 创建的文件是
`u:object_r:unlabeled:s0`，shell 无法访问。在 root + 非 enforcing 下：
`chcon -R u:object_r:shell_data_file:s0 /data/local/tmp` 有效（标签是持久化
xattr）；`restorecon -R` 会跳过该目录本身。正常 v1.7+ 流程（含 v3.0）创建的文件就是
正确的 `shell_data_file`。

## 环境笔记（设备工作）

- adb：`adb -s <serial> ...`；Windows-bash 下对远程路径要先
  `export MSYS2_ARG_CONV_EXCL='*'`。
- Shizuku 必须是 **shell 模式**；一旦有 `su`，ShizukuX 会以 root 模式重启
  （`uid=0`，`u:r:ksu:s0`），而该 bounded 域无法 exec 载荷。冷启动测试要按这个
  前提做。
- `/system/bin/su` 只在 KernelSU 首次 root 请求之后出现（管理器“超级用户”授权）；
  在此之前缺失是正常的。
- MIUI：`adb install` 可能返回 `INSTALL_FAILED_USER_RESTRICTED`；允许“USB
  安装”，或用 root 执行 `pm install -r -d`。
- tracefs：`kprobe_events` 是 0777，但 `events/*/enable` 仅 root 可写；探针必须
  由 root 进程使能。
- 默认 memlock 64 KB 会限制 `io_uring` 上下文数量（需要 `ulimit -l`）；与已发布
  载荷无关。
- seccomp（干净冷启动）：init/adbd/zygote/shizuku/rootd 为 0/0；system_server、
  systemui、普通 App 为 2/1。利用过程不会改变 App 自己的 seccomp。
- 相关内核配置：`CONFIG_MODULES=y`、`MODULE_SIG_FORCE` 未设、`MODVERSIONS`、
  `CFI`、`TRIM_UNUSED_KSYMS`、`KALLSYMS_ALL`、`DEBUG_INFO_BTF`、
  `kptr_restrict=2`、`panic_on_warn=0`、`panic_on_oops=0`。

## 待办 / 候选

按“价值/工作量”排序。v3.0 不依赖其中任何一项；都是研究级改进。

1. **消除第二次 `timerqueue_del`**（容易 → 中等）。目前伪造的树会触发第二次
   删除，以 `WARN` 形式执行（`panic_on_warn=0` 时被容忍）。设计一种树形让第一次
   erase 就终结（例如写 0 且满足 `[N]=0`、`[V]=0` 不变量），使回收路径不再触碰
   被破坏的队列。这会减少崩溃面，也可能让 `panic_on_warn=1` 场景可用。
2. **rootd 加固**（容易）：随机 socket 后缀 + 首消息 token（在 `SO_PEERCRED`
   之外）；从“放行 0/2000”收紧到“调用方 App 的 uid”；日志保持 0600。
3. **验证原版 Shizuku**（容易-中等）：代码用的是官方 `dev.rikka.shizuku`
   13.1.5 API（api/provider/aidl/shared + `newProcess`）；只实测过 ShizukuX
   13.7。需要在装有原版 Shizuku、shell 模式的设备上测 provider 与权限流程。
4. **冷启动成功率 harness**（中等）：脚本化 N 次重启 + 点击；记录成功所需
   attempts、`VERDICT` 失败分布、到 root 的时间、网络健康。用于调 `g_att`、
   喷页规模与 `g_mode`。
5. **Kernel profile JSON**（中等）：把常量/偏移从 `o3p.c` 挪到按
   `(kernel release, fingerprint)` 生成的 header，并按 profile 生成
   `ksu_symtab.h`。这是支持更多设备（例如其它 XRING O3 产品）的主要前提，
   可避免“每个内核重编一次”。
6. **KSU 用户态可靠性**（中等）：正常流程里管理器自动打开与 `su` 都工作，但在
   `initialized=0`（未发布）时两者都坏；另外首次 `su` 安装是惰性的。可以考虑让
   App 显式执行 `ksud install`（或触发一次 root 请求）作为初始化的一部分。
7. **App 功能**（容易）：App 内 root shell（rootd socket 已支持 `--shell`）、
   KSU allowlist 快捷入口、导出日志的复制/分享、i18n、平板两栏布局、失败后的
   “需要重启”指引。
8. **崩溃取证**（受阻）：pstore/ramoops 为空且只能从 kernel 域上下文读取；我们
   的 kernel SID 路线被阻断（见上）。在没有新的 `/sys/fs/pstore` 读取手段之前，
   panic 现场无法捕获。
9. **LKM ABI 风险**（困难）：上游 LKM 是为 `6.18.32-4k` 构建的，被重链接后加载
   到 `6.18.21`。用 KernelSU 源码针对本内核配置（或匹配的 GKI）构建，可消除
   ABI 错配与“修改过的模块”顾虑；需要内核源码/配置。

## 复现工作流（开发循环）

```bash
# payload
NDK_CC=... bash payload/build-payload.sh          # -> libo3p.so（sha 必须一致）
# app
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... bash app/build.sh
# device
adb install -r app/o3root2.apk
adb shell 'am start -n o3.st.root1/.Main'
# (UI tap) 然后看日志：
adb shell 'tail -f /data/data/o3.st.root1/files/run.log'   # 仅 debuggable 构建可用
# 或者用 App 内“导出日志” -> /sdcard/Android/data/o3.st.root1/files/
```

载荷可以独立测试（root 或 shell）：把 `libo3p.so` push 成 `o3p.bin`，
`chmod 755`，用 `10000 14000 300 1 6 1 8000 800` 运行。

## 禁止

- 不要提交 `tools/` 工具链、`app/build`、`payload/build`、keystore、`env.sh`、
  OTA zip、`.img` 文件或设备标识。
- 不要改 KernelSU 的 `bl setenforce` 调用点（`CALL26` 重定位）。
- 不要“修正”写入值为 `0x100`（解引用崩溃）。
- 不要在无法重启或 `su` 恢复的设备上使用旧路径 `O3P_SELDIRTY=1`；v3 默认终态
  是 Enforcing，是安全的。
- 不要在非本人设备上运行；见 `docs/SECURITY.md`。

## v2.0 — 自适应 LKM（2026-10-25）

- blob 改为 v2 容器：内置三个 LKM（KernelSU v3.3.1 375,328 B、KernelSU-Next v3.4.2
  473,552 B、SukiSU v4.2.1-r2 569,752 B），每项 24 字节 tag 表 +
  `O3KSKM02`/count trailer；旧的 `O3KSKO01` 仍可读。
- 载荷识别已装管理器（`me.weishu.kernelsu` → tag 1、`com.rifsxd.ksunext` → 2、
  `com.sukisu.ultra` → 3；`O3P_KSU_FORK` 可覆盖），切出对应 LKM 再加载；应用
  做同样探测、把 fork 传给载荷，并用解析出的 activity 打开对应管理器。
- 已在三种镜像上冷启动验证（仅 KernelSU / 仅 KernelSU-Next / 仅 SukiSU）：
  `/data/local/tmp/o3p_ksu.ko` 大小与前台管理器每次都与选择一致；SELinux 保持
  Permissive（v2.x 时代）、Wi-Fi 正常。

## v2.1 —— 权限感知探测（2026-10-25）

- 只有 Shizuku 已授权（`checkSelfPermission`）后才跑 probe；在 Shizuku 里用
  `pm path` 探测三种管理器包；`PackageInstaller` 安装成功后再探测；载荷
  文件名用唯一的 `o3p_<millis>.bin` 避免 `ETXTBSY`。
- 修掉 18 Fold 死锁：`ERR java.lang.reflect.InvocationTargetException` 曾被当作
  正常 probe 输出，导致“安装管理器”循环。

## v3.0 —— 两段式、不打 KernelSU init 补丁（2026-10-25）

- stage0 改为只清一个字节：在 `selinux_state-7` 做 8 字节非对齐零写（清
  `enforcing`，保留 `initialized`/`policycap`）；旧的脏写只在
  `O3P_SELDIRTY=1` 时保留。
- stage1 仍是 `task_cred` 换指针；两段使用两次独立 reap（一次 reap 三写连续
  三次 panic，仅保留在 `O3P_D3V3` 后）。
- KernelSU 用运行时 kallsyms 加载器、**不打 init 补丁**（`KSU_CODEPATCH
  skip`）；`policycap` 干净，所以 KSU 的 `setenforce(1)` 不影响 Wi‑Fi。App 因此
  移除 v1.8 的守护/一键恢复 UI。
- yingtian 冷启验证：`KALLSYMS_PATCH found=225`、`KSU_INSMOD_OUT:[rc=0]`、
  `KSU_MODULE LIVE`、Enforcing、`ping` 22 ms、`nc 443` rc=0、管理器自动打开、
  `boot_id` 未变。

## v3.1 —— 自定义 LKM 与内置 LKM 检查（2026-11-01）

- 应用：主按钮下方新增“选择 .ko”。选择器 → `CustomKo.validate()`（64 位
  AArch64 `ET_REL`、`.modinfo` 有 `name=kernelsu`、内核系列对比 `os.version`）；
  不通过时弹窗：退回自适应（推荐）/ 仍然尝试 / 取消，警告则记日志后接受。选中的
  模块先复制到应用外部目录，由 Shizuku shell 推到
  `/data/local/tmp/o3p_custom_<millis>.ko`（失败时走 stdin 管道），再以
  `O3P_CUSTOM_KO` 传给载荷。
- 载荷：`ko_validate()` 重复同一套校验；`ksu_load()` 先试自定义模块，文件不可读、
  被拒绝或加载失败时自动退回内置自适应 blob（`CUSTOM_KO fallback`）。relink /
  `insmod` 路径共用，因此自定义模块同样享受运行时 kallsyms 解析。
- 内置 LKM 检查（`python3 tools/check_lkm_updates.py`，2026-10-29）：tiann
  v3.3.1 与 KernelSU-Next v3.4.2 没有更新；SukiSU-Ultra v4.3.0 比内置的
  v4.2.1-r2 新。**没有**替换内置文件：没有真机验证的话 SukiSU 路径会失去已验证
  状态。想试 v4.3.0 可以用新的自定义 .ko 通道（与内置同属
  `android17-6.18` 构建，vermagic 相同）。
- 发布件：`release/o3root2-v3.1.apk`（14,235,152 B，sha256 `6d9041a9…`，同日被
  v3.1.1 取代）；载荷 `libo3p.so` 1,496,044 B，sha256 `e479e539…`；versionCode 14 /
  versionName 3.1。
- 已真机验证（2026-11-01，yingtian）：自适应冷启动加载内置 SukiSU blob
  （`KSU_BLOB v2 tag=3 matched=1`、`KSU_MODULE LIVE`）并打开管理器；自选 tiann
  v3.3.1 `.ko`（同 6.18 系列、不同构建）给出警告、持久化且 force-stop 后仍恢复；
  把 vermagic 改成 6.6.000 的副本触发校验弹窗，点“仍然尝试”后日志出现
  `CUSTOM_KO force reason=kernel_series_mismatch`，relink 后仍到
  `KSU_MODULE LIVE`。自动回退分支因强制模块仍能加载而未在真机触发，仍只做过桌面
  测试（`CustomKo` 对三个内置 LKM、6.18.21 vermagic 副本、错架构/类型/截断/非 ELF）。

## v3.1.1 —— 持久化自定义 .ko 选择（2026-11-01）

- `Main` 把选中的模块存为 `<files>/custom-ko.ko`，另存 `<files>/custom-ko.meta`
  （`forced` 标志 + 显示名），`onCreate` 时恢复；若文件已不再通过校验且未强制，
  则删除并写日志；强制选择按强制状态恢复。
- 长按模块行（`LongClearKo`，因 `d8` 不支持匿名/内部类而为顶层类）清除选择并删除
  两个文件。
- versionCode 15 / versionName 3.1.1；载荷未变（`libo3p.so` sha256 `e479e539…`）。
  APK `release/o3root2-v3.1.1.apk`，14,239,248 B，sha256 `a013cbff…`。内置 LKM
  决策不变（SukiSU v4.3.0 未替换）。
- 真机验证：首次启动清理失效文件、警告选择 force-stop 后恢复、长按清除、强制选择
  重启后仍保留、干净重启后的自适应运行（`KSU_BLOB v2 tag=3 matched=1`、
  `KSU_MODULE LIVE`、管理器打开、Enforcing）。
- 设备侧备注：该次重启后 Shizuku server 需要手动启动（自带 starter 报
  `librish.so` 不在 `base.apk!/lib/arm64-v8a`）；可用命令是在 `app_process ...
  rikka.shizuku.server.ShizukuService` 前设置 `CLASSPATH=<apk>/lib/arm64:<apk>`
  与 `LD_LIBRARY_PATH=<apk>/lib/arm64`。这是设备侧 workaround，与应用无关。


## v3.2 —— 可选中/可复制日志框与测试版 root 控制台（2026-11-01）

- 正式版（versionCode 17 / versionName 3.2）：`Main` 调用
  `logView.setTextIsSelectable(true)`，日志头新增 复制 链接（`ClickCopyLog`），
  复制全部日志；剪贴板不可用时提示用户手动拖选复制。`scrollBottomNow()` 只在
  接近底部时自动滚动。
- 测试版 `BETA=1`（versionCode 18 / versionName 3.2.1-console，
  `release/beta/o3root2-v3.2.1-console.apk`）：root 控制台入口藏在版本行长按
  （`ConsoleEntry` 反射调用 `ConsoleGate.start`；正式版排除
  `ConsoleGate`/`ConsoleActivity`，只链接 `ConsoleEntry`）。三道不可跳过确认
  （不可取消的风险框、复选框、输入恰好 `I ACCEPT ROOT RISK`）后传一次性 token；
  `ConsoleActivity` 无 token 直接结束。`ConsoleTask` 经
  `PayloadRunner.newProcess`（uid 2000）执行 `o3p --shell <cmd>`，
  `@o3p_rootd` 返回输出加 `__EXIT=n`，界面显示 `[exit n]`；需要同一 boot 内已
  跑过载荷，不新增提权路径。
- 真机（yingtian，2026-11-01）：正式版长按无控制台；beta 连过三道确认，经 rootd
  执行 `id` 得 uid 0（`[exit 0]`）；控制台输出复制/粘贴验证通过；正式版
  LogBox 选择、复制与条件自动滚动也验证过。
- 构建：`build.sh` 增加 `BETA` 开关（见 `docs/BUILD.md`）；`scripts/verify.sh`
  增加 `VERIFY_BETA=1`。发布物：正式版 14,239,248 B sha256 `64bbc654…`；beta
  14,243,344 B sha256 `9cba365a…`。APK 重建非逐位可复现（zip 时间戳 + 重签），
  应比较 dex/资源/`libo3p.so`。
- 测试环境备注：默认讯飞输入法会吞 `adb shell input text` 的字母，先
  `ime set com.dsharnessmobile.shell/.AdbKeyboardService`，测完恢复
  `com.iflytek.inputmethod.miui/.FlyIME`；键盘弹出时对话框上移，需重新 dump 坐标。

## v3.3 —— 实验性 O1（XRING）profile 与测试构建（2026-11-03）

- 调研：`dijun`、`jinghu`、`violin` 三份 OTA 共用一个内核（42,936,832 B，
  `sha256 0d9917ca…`，2026-07-28 构建，
  `6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k`）。与 yingtian
  （`cde87cd7…`）对比：BTF 布局一致；config 只差 `CONFIG_DPM_WATCHDOG`；
  `begin_new_exec` 的 128 个调用顺序相同（两边都没有 6.18.54+ 的
  `acb03d388181` POSIX timer 清理修复，漏洞路径存在）；`selinux_state - 7`
  仍落在 `avc_latest_notif_update.notif_lock`；`do_timer_create+0x68` 落在同一条
  `cbz`；三个 LKM 需要的 KernelSU UNDEF 符号在 O1 镜像中全部存在
  （`__versions` 为空、`MODULE_SIG_FORCE` 未设置）。只有 `selinux_state` 不同
  （`0xffffffc082958630` vs `…959630`），`init_user_ns` 相同。
- 载荷：新增 `SELINUX_STATE_O1_STATIC` 与 `selinux_state_static()`，运行时优先按
  `O3P_SELINUX_PROFILE`（App 提示）、否则按 `uname().release` 选基址并打印
  `SELINUX_PROFILE kernel=… base=0x… src=env|uname`。载荷 77,952 B；`libo3p.so`
  1,496,668 B sha256 `fef5e065…`。
- App：`Main.checkFirmware()` 对三个代号接受已分析 O1 内核（实验路径）或
  OS4.0.0.x 的任意 `6.18.21-android17…-4k` 内核（宽松路径），分别打印
  `固件门禁通过（O1 实验性/宽松模式：…）`；系列外内核仍被拒绝。识别为 O1 时
  `PayloadRunner` 导出 `O3P_SELINUX_PROFILE=o1`。正式版 3.3 的 APK：14,243,344 B
  sha256 `0153846d…`（未发布）。
- 测试构建：`BETA=1 BETA_VERSION=3.3.4-o1-test BETA_CODE=24` →
  `release/test/o3root2-v3.3.4-o1-test.apk`，14,247,440 B，sha256 `1a298929…`，
  versionCode 24，含隐藏控制台。
- App（v3.3.3）：识别到 O1 机型时，启动载荷前会先弹出两步警告确认（风险
  说明，然后勾选后启用“开始提权”）；取消不会改动设备。实现在 `Main` +
  `O1Warning.java`（顶层监听器）。
- v3.3.3 修复宽松系列匹配带来的门禁回归：已验证的 yingtian/lhasa 内核本身也是
  `6.18.21-android17…-4k`，导致没有 O1 代号时也进入 O1 分支，在已验证的 O3
  设备上误报 `固件门禁未通过：O1 内核但设备代号未知`。现在分支条件是
  `o1Gate = o1Dev != null && (o1Kernel || o1Series)`，yingtian/lhasa 先走原路径；
  已知 O1 内核但代号未知仍失败（并多打印一行说明）。由用户截图（v3.3.2）报障；
  本次无 O3 设备连接，未能重新实测。
- 取代 v3.3.1（相同的宽松门禁，另加两步 O1 确认）和 v3.3-o1-test（只接受
  精确 O1 内核）；旧 tag / GitHub release 均已删除。
- **未做真机验证**：没有 O1 设备；主要风险是 O1 的旧实现
  `posix_cpu_timer_del`（`__rcu_read_lock` + `pid_task` +
  `__lock_task_sighand`，而非 `timer_lock_sighand`），可能影响 UAF 时序。测试
  指南见 `docs/TESTING_O1.md`。

## v3.3.4 —— LogBox 滚动方向 + 现代自定义 LKM（2026-11-05）

- LogBox：`appendNow()` 每行都调用 `fullScroll(FOCUS_DOWN)`。在可选中
  `TextView` 上，某些 ROM 会把该调用变成“滚到焦点视图的顶部”，所以看起来向上跳。
  现在在改文本前记录 `nearBottom()`，改完用
  `scrollTo(0, logView.getBottom() - logScroll.getHeight())` 显式滚到底（连发两次，
  确保首次布局完成），不再使用 `fullScroll`。
- 自定义 LKM 加载器的两个独立缺陷：
  1. `kallsyms_patch()` 与 `ksu_patch()` 都拒绝超过 64 个节，SukiSU 4.3.0 的
     LKM（65 节）因此掉进 relink 路径（`KSU_LOADER relink (kallsyms r=-2 …)`）。
     两处上限现改为 512。
  2. 未设 `O3P_CODEPATCH` 时 relink 路径是静默空操作：`ksu_patch()` 在
     `KSU_CODEPATCH skip` 处提前返回，一个 UNDEF 符号都没重写，模块带着未解析符号
     写出，`insmod` 报 `No such file or directory`（内核 `-ENOENT`）。
     现在 `ksu_patch()` 显式接收 `relink` 参数：该守卫只用于“仅补丁”调用，符号重写
     只在 relink 模式执行，返回值为 `relink ? (found>0?0:-6) : 0`。kallsyms 成功的
     调用传 0，回退传 1；日志新增 `KSU_PATCH … relink=%d`。
- 载荷 77,952 B；`libo3p.so` 1,496,668 B sha256 `fef5e065…`。正式版 3.3 APK
  14,243,344 B sha256 `0153846d…`（未发布）。测试构建
  `BETA=1 BETA_VERSION=3.3.4-o1-test BETA_CODE=24` →
  `release/test/o3root2-v3.3.4-o1-test.apk`，14,247,440 B，sha256 `1a298929…`。
- 由设备机主报告：(a) 自动滚动跳到顶部；(b) 自选 SukiSU 4.3.0 LKM 在
  `insmod … No such file or directory` 后记录 `CUSTOM_KO fallback reason=load_failed`。
- 已回 yingtian 真机复验（2026-11-05，冷启动，直接由 adb shell 跑载荷并设
  `O3P_CUSTOM_KO=/data/local/tmp/ksu430.ko`）：
  `KSU_LOADER kallsyms found=251 missing=0`、`KSU_CODEPATCH skip`、
  `KSU_WROTE /data/local/tmp/o3p_ksu.ko 614424`、`KSU_INSMOD_OUT:[rc=0`、
  `KSU_MODULE LIVE`，`/proc/modules` 显示 `kernelsu 503808 … Live`，Enforcing。
  该次共 9 次尝试成功（`VERDICT NO_RECLAIM` 属正常、可重试）。
  LogBox：连续 50 次追加都保持在最新行；手动滚到上方后再追加不会被拉回底部。
