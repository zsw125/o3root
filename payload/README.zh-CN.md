# payload（中文版）

> 英文原版：[`README.md`](README.md)。若两者不一致，以英文版为准并同步本文件。

原生 aarch64 利用载荷（C，NDK），以 `lib/arm64-v8a/libo3p.so` 的形式打进 APK ——
一个伪装成共享库的可执行 ELF。App 会把它复制到 `/data/local/tmp`，并在
Shizuku 拉起的 shell 域进程里运行。

## 构建

```bash
export NDK_CC=/path/to/ndk/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android35-clang
bash build-payload.sh
# -> build/o3p.bin      （载荷可执行文件）
# -> build/libo3p.so    （载荷 + 三种 KernelSU 系 LKM + 条目表/trailer）
```

`mk_payload.py` 现在写 v2 容器：载荷 + 三种 LKM（KernelSU v3.3.1、
KernelSU-Next v3.4.2、SukiSU v4.2.1-r2）+ 每项 24 字节的 tag 表 +
`O3KSKM02`/count trailer。载荷从 `/proc/self/exe` 读 trailer，识别已装管理器
（或 `O3P_KSU_FORK` 覆盖），切出对应 LKM 再加载；同一个文件仍可直接执行
（`./libo3p.so`）。旧的单 LKM `O3KSKO01` 布局仍可读。

## 载荷做什么

1. **trace 准备**：在 `do_timer_create`、`__set_task_comm`、
   `posix_timer_unhash_and_free`、`exit_itimers`、`release_task`、
   `timerqueue_del`、`posix_cpu_timers_exit` 上挂 kprobe/ftrace
   （tracefs 为 0777；shell 域允许写）。
2. **KASLR slide**：通过 kprobe（`__arm64_sys_getpid`）读父进程的 `cred`，泄漏
   `init_user_ns`，然后 `slide = leaked - INIT_USER_NS_STATIC`。
3. **CVE-2026-98260**：在非 leader 线程里武装一个 `CLOCK_THREAD_CPUTIME_ID`
   POSIX timer，让 `execve` 经 `de_thread`/`exit_itimers` 竞态，保留僵尸
   （waitid WNOWAIT），再用带伪造 `k_itimer` RB 节点的 kmalloc-8k
   （`send(...,7872)`）喷页回收已释放的 order-1 `posix_timers_cache` slab 页。
4. **写原语**：回收（`release_task` → `posix_cpu_timers_exit` →
   `cleanup_timerqueue` → `timerqueue_del` → `rb_erase`）会把伪造节点的
   `__rb_parent_color` 写进任意目标地址。载荷用它改写
   `selinux_state.enforcing`（stage 0）和 `task_struct.cred`（stage 1）。
5. **伪造 cred**（`stage 1`）：一个 perf ring 页（内核 VA 经 kretprobe 泄漏）里放
   伪造的 `struct cred`，uid/euid/fsuid=0、full caps、`security` 沿用原来的
   SELinux blob 指针。
6. **KernelSU LKM（自适应）**：加载前扫描 `/data/app` 识别已装管理器——
   `me.weishu.kernelsu`（KernelSU，tag 1）、`com.rifsxd.ksunext`（KernelSU-Next，tag 2）、
   `com.sukisu.ultra`（SukiSU-Ultra，tag 3）——从内置 blob 里切出对应 LKM
   （`O3P_KSU_FORK` 可覆盖）。随后经 `/proc/kallsyms` 解析该 LKM 的未定义符号
   （临时降低 `kptr_restrict`，结束前恢复）；`O3P_RELINK_ONLY=1` 时回退到
   248 符号并集 + address+slide。它还会打补丁使其不把 SELinux 强制拉回
   Enforcing，写 `/data/local/tmp/o3p_ksu.ko`，然后从 fork 出的
   `/system/bin/sh` 执行 `insmod`。
7. **rootd**：fork 一个脱离的 root 守护进程；父进程以 `--rootd` exec 载荷，后者
   监听抽象 unix socket `@o3p_rootd`，只接受 uid 0 或 2000 的对端
   （SO_PEERCRED）。客户端：`--shell "<cmd>"`。

## 文件

| 文件 | 作用 |
|---|---|
| `o3p.c` | 完整载荷（约 25 KB C；由 t32/t34 血统生成） |
| `ksu/ksu_symtab.h` | 上游各 fork 的 248 个 KernelSU 符号并集 → vmlinux 地址（供 relink 回退） |
| `ksu/mksymtab.py` | 从 `vmlinux.elf` 重新生成 `ksu_symtab.h`（env `VMLINUX`） |
| `ksu/relink_ksu.py` | 离线重链接器，运行时 `ksu_patch` 的参考实现 |
| `mk_payload.py` | 打包载荷 + 三种 LKM + tag 表/trailer → `libo3p.so` |
| `lkm/lkm-aarch64-…kernelsu.ko` | `android17-6.18` 的官方 KernelSU v3.3.1 LKM（GPL-3.0） |
| `lkm/aarch64-next-…kernelsu.ko` | `android17-6.18` 的官方 KernelSU-Next v3.4.2 LKM（GPL-3.0） |
| `lkm/sukisu-aarch64-…ko` | `android17-6.18` 的官方 SukiSU-Ultra v4.2.1-r2 LKM（GPL-3.0） |
| `legacy/t32.c`、`legacy/t34.c`、`legacy/mk_o3p.py` | 早期利用版本；`mk_o3p.py` 展示 `o3p.c` 如何从 `t34.c` 派生 |

## 注意

- 载荷是**设备/profile 特定**的：偏移与静态地址都是按
  `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`（yingtian/lhasa）编译
  的。常量与换内核时如何重新推导，见 `docs/ARCHITECTURE.md`。
- 该写操作会破坏 `selinux_state.policycap[]`（第一次 8 字节写的第 2..7 字节）。
  SELinux 保持 Permissive 时无害，但若 enforcing 被置 1，网络栈就会坏。APK 为此
  有守护/恢复 UI；见 `docs/SECURITY.md`。
