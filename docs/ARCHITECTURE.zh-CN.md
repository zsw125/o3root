# 架构（中文版）

> 英文原版：[`ARCHITECTURE.md`](ARCHITECTURE.md)。若两者不一致，以英文版为准并同步本文件。

本文描述利用链以及 Android/KernelSU 的封装方式。这里的一切都针对下面的目标
profile；常量与移植方法见 [内核 profile](#内核-profile)。

## 目标 profile

| 项 | 值 |
|---|---|
| 设备 | 小米 `yingtian`（Pad 9 Pro Max，M367FC）；`lhasa`（Xiaomi 18 Fold）共享同一内核 |
| 固件 | HyperOS `OS4.0.16.0.XBMCNXM`（yingtian）/ `OS4.0.26.0.XPNCNXM`（lhasa），Android 17 |
| 内核 | `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`，arm64，4 KB 页 |
| bootloader | 锁定，verified boot green |
| 入口 | **shell 模式**的 Shizuku server（`uid=2000`，`u:r:shell:s0`），由 App 驱动 |

内核镜像的 sha256 为
`cde87cd70206f21db4359aa1148f5f68e693ffefb11108dffde99c07c8c5ad88`
（两个固件完全相同）。见 `docs/history/OTA_4.0.16_kernel_report.md`。

## 漏洞：CVE-2026-98260

缺少上游修复 `acb03d388181`（“exec: Cleanup POSIX timers right after
de_thread()”）。在这个内核里，`begin_new_exec()` 中的 POSIX timer 清理仍然发生在
`exec_task_namespaces()` **之后**，而不是紧跟 `de_thread()` 之后。

触发过程：

1. 一个**非 leader** 线程 `B` 创建一个 `CLOCK_THREAD_CPUTIME_ID` 的 POSIX timer
   （`timer_create` + `timer_settime`）。
2. `B` 调用 `execve()`；`de_thread()` 交换 PID（`exchange_tids`）并释放旧
   leader，随后 timer 被 unhash，但它的 RB 节点仍挂在（此时已失效的）
   `timerqueue` 上。
3. exec 路径在 `exit_itimers()`/`posix_cpu_timers_exit()` 完成之前被打断，于是
   `k_itimer` 被释放（`kfree_rcu`），节点却还留着。
4. 父进程把 `B` 收成僵尸（`waitid(P_PID, ..., WEXITED|WNOWAIT)`），并用用户可控
   内容回收被释放的 order-1 slab 页。
5. 回收僵尸（`release_task` → `posix_cpu_timers_exit` →
   `cleanup_timerqueue` → `timerqueue_del` → `rb_erase`）时，对回收页里的节点
   执行第二次 `timerqueue_del`。

## 写原语

`rb_erase()` 的“有左子、无右子”分支：

```
ldr  x9, [x0]        ; pc = node->__rb_parent_color
ands x8, x9, #-4     ; parent = pc & ~3
str  x9, [x10]       ; *(node->rb_left) = pc        <-- 任意 8 字节写
...
ldr  x9, [x8, #0x10] ; 解引用 parent->rb_left
```

伪造节点给出：

- `[N]      = V`  （要写入的值）
- `[N+0x08] = 0`  （没有右子 → 走上面的分支）
- `[N+0x10] = S`  （目标地址）

约束：

- `parent = V & ~3` 会被解引用。要活着，`V` 必须是 `0`（parent 0 → 提前返回）
  或我们可控的可读地址（例如 perf 页的内核 VA）。因此这个原语只能安全地写
  **0** 或**指向受控页的指针** —— 不能写 `0x100` 这类任意常量。
- 第一次 erase 还会更新 `rb_next`/`head->rb_leftmost`；`[N+8] = 0` 与
  `[N+0x10] = S` 让第二次删除落在假节点上，只产生可容忍的 `WARN`（要求
  `panic_on_warn=0`）。

## 两个阶段

载荷每个阶段都完整重跑一遍 UAF/回收周期（`g_ntg = 2`）：

### stage 0 —— SELinux 状态

- v3 默认（两段式）：`S = SELINUX_STATE_STATIC + slide - 7`，值 `0` —— 8 字节
  非对齐零写，只清 `enforcing`，`initialized`/`policycap[]` 原样保留（实测
  `state=0x0100000101010100`）。
- 旧路径 `O3P_SELDIRTY=1` 仍用旧的脏写（`V = fakekva | 0x100`）：同样清
  `enforcing`，但 `policycap[]` 变成地址垃圾——permissive 下无害，一旦回到
  Enforcing 就破坏 socket 检查。

### stage 1 —— 凭据

- `S = task_struct.cred (PTASK + 0x908)`，`V = perf_page_va`
- perf 页里放一个伪造的 `struct cred`：uid/gid/euid/fsuid = 0、full caps
  （`0x1ffffffffff`）、`security` 指向进程原来的 SELinux blob（保持
  `u:r:shell:s0`）、合法的 `user_ns`、`group_info`、`ucounts`。
- 回收完成后，载荷修复该页（`repair_fake_cred`），进程变成 root + full caps。

`real_cred`（0x900）**不会**被替换。任何进程内 `commit_creds()` 都会 BUG
（`task->cred != task->real_cred`），所以模块由 **fork 出的 `sh`** 执行
`insmod` 来加载（新的 exec 会让 cred 一致），而不是进程内 `finit_module`。

## KASLR slide

在 `__arm64_sys_getpid` 上挂 kretprobe，读取父进程的 `task_struct.cred` 指针；
从 cred 读 `user_ns`，然后：

```
slide = leaked_user_ns - 0xffffffc0826d10d8   (INIT_USER_NS_STATIC)
```

slide 会用内核 VA 的对齐规则校验，并复用于所有静态地址，包括内置的 KernelSU
LKM。

## KernelSU 加载

1. 三个预编译 LKM 以 v2 容器追加在载荷之后（每项 24 字节条目表 + `O3KSKM02`/count
trailer；旧的单 LKM `O3KSKO01` trailer 仍可读）：KernelSU v3.3.1（375,328 B）、
KernelSU-Next v3.4.2（473,552 B）、SukiSU v4.2.1-r2（569,752 B）。加载前载荷扫描
`/data/app` 识别已装管理器（`me.weishu.kernelsu` → 1、`com.rifsxd.ksunext` → 2、
`com.sukisu.ultra` → 3；`O3P_KSU_FORK` 可覆盖），只切出对应 LKM；应用做同样探测
并把 fork 传给载荷。
2. 主路径 —— **运行时 kallsyms 解析**：`kallsyms_patch()` 遍历 LKM 的
   `.symtab`，收集所有未定义符号名；保存并临时把 `/proc/sys/kernel/kptr_restrict`
   写成 `0`，经 `/proc/kallsyms` 解析每个名字，并把符号改写成 `SHN_ABS` +
   运行时地址；加载前必恢复原 `kptr_restrict`。这样就不再依赖构建期的
   `vmlinux.elf`；实测分别解析 225（tiann）、227（KernelSU-Next）、245
   （SukiSU）个未定义符号，全部命中、0 缺失。
3. 回退路径 —— **离线重链接表**：`O3P_RELINK_ONLY=1`（或 kallsyms 缺失）时，
   使用内置的 248 个 KernelSU 符号并集（来自上游各 fork），把每个未定义符号
   改写为 `vmlinux_addr + slide`。这同样绕过模块符号命名空间检查、
   `TRIM_UNUSED_KSYMS` 导出限制、受保护符号与 CRC。
4. 只有旧路径（`O3P_CODEPATCH=1`）才改这一条指令：解析 `.rela.init.text`，找符号为 `setenforce`
   的重定位，改写 `r_offset - 4` 的 4 字节（`mov w0,#1` → `mov w0,#0`；
   `.init.text` 段内偏移 tiann/KernelSU-Next `+0x144`、SukiSU `+0x178`；
   日志打印的是文件绝对偏移，这几次构建分别 0x22144 / 0x37144 / 0x3e178）。
   重定位无法解析时回退到唯一扫描。这样模块 init 期间**不会**调用
   `setenforce(1)`。（改 `bl setenforce` 调用点是不可行的：CALL26 重定位会
   在加载时重写这 4 字节，导致未定义指令 / 模块卡在 `coming`。）
5. 把 LKM（旧路径：已打补丁；v3：未修改）写到 `/data/local/tmp/o3p_ksu.ko`，然后
   `fork()` + `exec("/system/bin/sh","-c","insmod ...")`，等待并确认
   `/proc/modules` 里有 `kernelsu`。

已在 `6.18.21` 上按三种管理器镜像验证（分别只装 KernelSU v3.3.1 / 仅
KernelSU-Next v3.4.2 / 仅 SukiSU v4.2.1-r2）：每次冷启动应用都识别出管理器，
容器切出对应 LKM（写入 `/data/local/tmp/o3p_ksu.ko` 的大小分别为 375,328 /
473,552 / 569,752 B），模块 `Live`、SELinux 仍 Permissive（v2.x 时代）、Wi‑Fi
正常，且对应管理器被拉到前台。

### v3 默认（两段式，无 init 补丁）

自 3.0 起，利用改为两次独立 reap（分别写 `selinux_state-7` 与 `task_cred`），
KernelSU 用运行时 kallsyms 加载器、**不打 init 补丁**。`policycap` 保持干净，
所以 KSU 自己的 `setenforce(1)` 无害：设备停在 Enforcing、Wi‑Fi 正常、管理器
正常打开。`KSU_CODEPATCH skip` 是预期日志行；`O3P_CODEPATCH=1` 可回退旧补丁。

## rootd

成功后载荷 fork 一个脱离的守护进程（`o3p --rootd`），并在本次 boot 的剩余时间里
保持存活：

- 抽象 unix socket `@o3p_rootd`；`/data/local/tmp` 里不放文件
- `SO_PEERCRED` 检查：只允许 `uid 0` 或 `uid 2000` 连接
- 协议：每连接一条命令，用 `/system/bin/sh -c` 执行，stdout/stderr 合并回传，
  以 `__EXIT=<code>` 结束
- 客户端：`./o3p.bin --shell "<command>"`

## App 封装

- App（`o3root2.apk`，包名 `o3.st.root1`）是普通 Android 应用，通过
  `dev.rikka.shizuku:api/provider/aidl/shared` 与 Shizuku 通信。
- 载荷以 `lib/arm64-v8a/libo3p.so` 分发（可执行 ELF —— `extractNativeLibs`
  路径保证安装后仍可执行）。点“Continue”时，App 通过 `Shizuku.newProcess`
  拉起 `sh`，把文件复制到 `/data/local/tmp/o3p.bin` 并执行。
- 状态探测：`grep -c kernelsu /proc/modules; getenforce; pm path
  me.weishu.kernelsu`。
- v3 没有 Enforcing 守护：正常终态就是 Enforcing 且 `selinux_state` 干净，App 只
  显示状态（v1.8–v2.1 才有告警与一键 `su -c setenforce 0`）。
- 固件门禁：内核 release 字符串必须匹配
  `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`，且设备 build
  fingerprint 必须包含 `/yingtian/` 或 `/lhasa/`。

## 内核 profile

### 常量（本内核）

| 符号 / 字段 | 值 |
|---|---|
| `init_user_ns` | `0xffffffc0826d10d8` |
| `selinux_state` | `0xffffffc082959630` |
| `begin_new_exec` | `0xffffffc0802cf910` |
| `posix_cpu_timers_exit` | `0xffffffc08014bfbc` |
| `exit_itimers` | `0xffffffc0802a603c` |
| `timerqueue_del` | `0xffffffc080149ec0` |
| `rb_erase` | `0xffffffc080149f34` |
| `task_struct.cred` | `0x908` |
| `task_struct.real_cred` | `0x900` |
| `task_struct.posix_cputimers` | `0x868` |
| `task_struct.signal` / `sighand` | `0x960` / `0x968` |
| `cred` size / uid / euid / fsuid | `0xb8` / `0x08` / `0x18` / `0x20` |
| `cred.cap_*` | `0x30`..`0x50` |
| `cred.security` / `user_ns` / `group_info` | `0x80` / `0x90` / `0xa0` |
| `cred_security_struct`（osid、sid、…） | `0x18`，sid 在 `+0x04` |
| `k_itimer` size / `it` / `elist` / `handling` | `0x180` / `0xe0` / `0x110` / `0x128` |
| `selinux_state`（enforcing、initialized、policycap、policy） | `0` / `1` / `2` / `0x40` |
| 页相关常量 | `PAGE_OFFSET=0xffffff8000000000`、`VMEMAP_BASE=0xfffffffec0000000`、`struct page=0x40` |

### 移植到其它内核

1. 从对应 OTA 取 boot 镜像并提取内核
   （`tools/extract_payload.py`、`tools/unpack_boot.py`）。
2. 用 `vmlinux-to-elf` 把原始 `Image` 转成 ELF 以恢复符号。
3. dump BTF（`tools/kernel_cfg_btf.py` 或 `bpftool btf dump`），确认上面的
   `task_struct`、`cred`、`k_itimer`、`selinux_state` 偏移。
4. 用 `mksymtab.py`（`VMLINUX=...`）为 relink 回退路径重新生成
   `payload/ksu/ksu_symtab.h`（默认的 kallsyms 路径不需要该表）。
5. 更新 `payload/o3p.c` 中的静态地址与常量
   （`INIT_USER_NS_STATIC`、`SELINUX_STATE_STATIC`、各探针偏移）。
6. 只有旧 `O3P_CODEPATCH=1` 路径才打 KernelSU 指令补丁：位置由
   `.rela.init.text`（`setenforce` 重定位往前 4 字节）在运行时定位，不需要按
   构建写死常量；唯一扫描也找不到时会打印 `KSU_CODEPATCH … -2`。v3 默认完全
   跳过该补丁。
7. 更新固件门禁（`Main.checkFirmware`）并重编 APK。

KASLR 本身不需要按设备适配：slide 是运行时推导的。
