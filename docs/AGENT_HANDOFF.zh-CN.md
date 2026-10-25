# Agent 交接 —— 状态、历史与待办（中文版）

> 英文原版：[`AGENT_HANDOFF.md`](AGENT_HANDOFF.md)。若两者不一致，以英文版为准并同步本文件。
> 入口文档：[`AGENTS.zh-CN.md`](../AGENTS.zh-CN.md)（中文）/ [`AGENTS.md`](../AGENTS.md)（英文）。

这是自动化贡献者在 `AGENTS.md` 之后应读的第二份文档。它记录：实机上真正验证过
什么、哪些实验已经失败（避免重复）、剩余工作与大致工作量估计。

## 项目状态

| 项 | 值 |
|---|---|
| 发布 | v3.0，`release/o3root2-v3.0.apk`（14,218,768 B，sha256 `c80f499a…`） |
| 已验证设备 | 小米 Pad 9 Pro Max `yingtian`，HyperOS `OS4.0.16.0.XBMCNXM`，内核 `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k` |
| 第二目标 | `lhasa`（Xiaomi 18 Fold）`OS4.0.26.0.XPNCNXM` —— 同内核 sha256 `cde87cd7…`（boot header/ramdisk 不同）；**未实机测试** |
| 持久化 | 无（仅当前 boot） |
| 入口路径 | Shizuku **shell** server（`uid=2000`，`u:r:shell:s0`）→ App → 载荷 |

App 日志中的成功标记（按顺序）：
`★ UIDNOW uid=0` → `KSU_INSMOD_OUT:[rc=0]` → `★ KSU_MODULE LIVE` →
`✔ 全部完成，正在打开 KernelSU…` → `top -> …me.weishu.kernelsu/.ui.MainActivity`。
健康状态：`getenforce` = `Enforcing`（v3；旧路径 `O3P_SELDIRTY=1` 会停在
Permissive）、网络可用、`boot_id` 未变。

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

7. **v3.0（当前，2026-10-25）**：两段式取代单次写入。stage 0 在
   `selinux_state-7` 做 8 字节非对齐零写（只清 `enforcing`，保留
   `initialized`/`policycap`），stage 1 换 `task_cred`；KernelSU 经运行时
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

- 只有 Shizuku 已授权（`checkSelfPermission`）后才跑 probe；用本地
  `PackageManager` 识别三种管理器；`PackageInstaller` 安装成功后再探测；载荷
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
