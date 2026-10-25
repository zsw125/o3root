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
（两个固件完全相同）。见 `docs/OTA_4.0.16_kernel_report.md`。

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

- `S = SELINUX_STATE_STATIC + slide`，`V = fakekva | 0x100`
- 写入 `enforcing = 0`，保留 `initialized = 1`，但高 6 字节（`policycap[]`
  数组）变成地址垃圾字节。
- 结果：SELinux **Permissive**；垃圾 `policycap` 在 permissive 下无害，但一旦
  重新开启 enforcing 就会破坏 socket 检查。

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

1. 官方预编译的 `android17-6.18` LKM 以 24 字节 trailer（`O3KSKO01`、
   payload_len、lkm_len）追加在载荷之后。
2. `ksu_patch()` 遍历 LKM 的 `.symtab`，把每个未定义符号（225 个）改写成
   `SHN_ABS` + `vmlinux_addr + slide`。这绕过了模块符号命名空间检查、
   `TRIM_UNUSED_KSYMS` 导出限制、受保护符号与 CRC。
3. `ksu_patch()` 还会改一条指令：`.init.text+0x144` 的 `mov w0,#1` →
   `mov w0,#0`（文件偏移 `0x22144`，字节 `52 80 00 20` → `52 80 00 00`），使
   模块 init 期间**不会**调用 `setenforce(1)`。（改 `bl setenforce` 调用点是不
   可行的：CALL26 重定位会在加载时重写这 4 字节，导致未定义指令 / 模块卡在
   `coming`。）
4. 把打好补丁的 LKM 写到 `/data/local/tmp/o3p_ksu.ko`，然后
   `fork()` + `exec("/system/bin/sh","-c","insmod ...")`，等待并确认
   `/proc/modules` 里有 `kernelsu`。

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
- 当 KernelSU 已加载但 SELinux 被设回 Enforcing 时，App 会告警并提供一键恢复
  （`su -c setenforce 0`）。
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
4. 用 `mksymtab.py`（`VMLINUX=...`）重新生成 `payload/ksu/ksu_symtab.h`。
5. 更新 `payload/o3p.c` 中的静态地址与常量
   （`INIT_USER_NS_STATIC`、`SELINUX_STATE_STATIC`、各探针偏移）。
6. 重新确认 KernelSU LKM 里要改的那条指令（在 `.init.text` 中 `setenforce`
   调用之前搜索 `mov w0,#1`）—— 偏移 `0x144` 是构建相关的。
7. 更新固件门禁（`Main.checkFirmware`）并重编 APK。

KASLR 本身不需要按设备适配：slide 是运行时推导的。
