# 先前工作、致谢与本项目采用情况

> 中文版：本文件是 [`PRIOR-ART.md`](PRIOR-ART.md) 的中文翻译。若两者不一致，
> 以英文版为准，并同步修改本文件。

本文件记录本项目所依据的外部工作 **以及** 我们自行验证的内容。写法刻意具体：
每条影响设计的结论都绑定到常量、本仓库文件或提交，方便后来者复核，而不是
单纯相信叙述。

快照：小米相关先前工作审阅于 **2026-10-25**；CVE 研究审阅于 **2026-10-24/25**。
外部仓库此后可能已经变化——依赖细节前请重读。

> 如果这里的数字与 `payload/o3p.c` 或我们的 `vmlinux` 不一致，以代码和内核
> 镜像为准。请修正本文件，而不是修改常量。

---

## 1. CVE 研究：V4bel / InfinityCore（`timer/`）

来源：V4bel/InfinityCore 仓库的 `timer/` 目录（CVE-2026-98260）。我们读过的
公开材料：`README.md`（约 20 KB）和 `CVE-2026-98260-ubuntu.c`（39,171 B；目标
Ubuntu 26.04 / 7.0.0-30-generic）。Android 版利用 **没有公开**——
`CVE-2026-98260-android.c` 只是 239 字节的占位文件（据称 exploit 的 sha256 为
`713a294f72abc46b6e12633117d40e85629ca2b998698fd75c76e523e0d53253`）。

第一代利用链从它继承的内容（针对本内核重写在 `payload/legacy/t32.c`、
`t34.c`；更早的 `t31.c` 阶段只保留在工作区历史里）：

- **僵尸保留**：`waitid(..., WNOWAIT)` 让受害任务保持 zombie，同时它的
  `posix_cputimers` timerqueue 仍引用已释放的 `k_itimer`。
- **页回收 + 喷页**：释放 order-1 的 `posix_timers_cache` slab，再喷用户可写
  页，让其中一页落到释放的 slab 上；伪造的 RB 节点按页内猜测偏移布置。
- **伪造 RB erase 作为 8 字节写**：`timerqueue_del` → `rb_erase` 得到
  `*(target) = parent_color`；配合假根节点，写入值可以是 0 或受控页指针。
- **AIO ring 页作为回收目标**（其 `io_setup` 喷射）。我们最早用 kmalloc-8k 的
  socket 缓冲喷射，因为 shell 域不能依赖 AIO 的分配顺序。
- **Oracle 泄漏思路**：`cpu_timer.handling = current` 把 task 指针写进用户可读
  页；`elist` 的 `list_del_init` 自指针泄漏对象的内核 VA。我们保留了这个概念，
  但 v1.x 走 kprobe（起点是 shell 域，不是 App 域）。

未采用：其 Ubuntu 专属布局常量（`sizeof(k_itimer)` 0x180、`it.cpu` 0xe0、
`elist` 0x110、`handling` 0x128——我们全部按自己的 BTF 重推）和时间常量
（392 个 worker、64,000 个 AIO 页），后者假设了不同的分配器/CPU 预算。

## 2. 面向 yingtian 的 Android 先前工作——`EAEdward/xiaomi9pmroot`（重点）

来源：<https://github.com/EAEdward/xiaomi9pmroot>，MIT，2026-10-25 发布
（同日审阅的快照）。同设备、同 CVE 的独立 root 工具包。我们读过
`docs/HOW-IT-WORKS.md`、`plant/uaf_plant_v6.c`、`plant/pc98260_*`、
`launcher/ytrl.sh` 以及 release 产物；下面的常量都与我们的 `vmlinux` 交叉核对。

### 它是什么

- 形态：**AxManager 插件**（boot hook）+ shell 启动器 `ytrl.sh` +
  `plant` / `su98260` / `ksud` 二进制；没有 APK UI。
- 链路：Shizuku shell → `plant`（CVE-2026-98260）→ rootd socket
  `pc98260-root.sock` → 上游 `ksud late-load --kmi android17-6.18
  --allow-shell --package-name me.weishu.kernelsu` → KernelSU 用户态 `su`。
- KernelSU：官方 LKM，经上游 `ksud` 加载；**没有 init 补丁**。其
  `selinux_state` 写的位置让 KernelSU 自己的 `setenforce(1)` 之后仍保持*干净*
  状态（没有 `policycap` 垃圾），这也是网络能存活的原因——与我们在实验 A
  独立得到的结论一致。

### 本项目采用的技术

1. **（采用，B）纯 tracepoint 测 KASLR slide + cred 锚点。** 静态
   `kmem_cache_alloc` tracepoint 记录 0x180 字节 `alloc_posix_timer` 和
   0xb8 字节 `prepare_creds` 请求的 `call_site`；每条满足
   `call_site = 静态链接地址 + slide`。实现于 `experiments/tp_slide.c`
   （提交 `57490bf`），读 `/sys/kernel/tracing/per_cpu/cpu0/trace_pipe_raw`
   （文本 trace 会哈希指针——必须用 raw）。本镜像锚点：
   `TIMER_LINK = 0xffffffc0803a726c`、`CRED_LINK = 0xffffffc08026d1b4`。
   在 Enforcing 的 shell 域验证：测得 slide `0x1db3000000` 与载荷独立计算的
   `SLIDE_CALC` 相等；cred 锚点可复现（`match=1`，需要
   `setresuid(2000,2000,2000)` 触发 `prepare_creds`）。
2. **（采用，C）PFN 校验回收。** 给 `mm_page_free` 与 page-cache 插入加探针，
   推导受害 PFN，只有 AIO ring 能证明自己持有该页时才继续。实现于
   `experiments/tp_reclaim.c`（提交 `c924964`、`812a96f`）；一次性运行命中
   `ring=492 pfn=0x334fa3 magic=0xa10a10a1`。**未进入发布**：同一 boot 第二次
   运行必 miss（12,000 次 `io_destroy` 会污染分配器），所以 v3 保留已验证的
   盲喷。
3. **（作为研究采用，D）一次 UAF，三棵树。** `posix_cputimers` 有三条队列
   （`CPUCLOCK_PROF/VIRT/SCHED`）；每条队列装一个 timer，一次 reap 就能得到
   三次 `rb_erase` 写。本镜像里三条队列的 **rb_node** 位于
   `TASK+0x870/0x888/0x8a0`（缓存的 `next` 在 `+0x878/+0x890/+0x8a8`；对方
   文档用后者命名队列，我们用 rb_node 地址）。实现为 `O3P_D3`（提交
   `af0de5b`）：一次 reap 内三次写均验证（`SEL_OK state=0x0100000101010100`、
   `D3PAGE slot0=0x100000 slot1=0 slot2=0 slot3=0`）。把单次 reap 组合
   （v3a–v3c）作为发布路径会**连续三次 panic**（都在 `D3ADDR` 之后）；v3.0
   改用两段式（见 `docs/V3-DESIGN.md` §Outcome）。
4. **（采用，A → v3.0 默认）非对齐 enforcing-only 零写。** 在
   `selinux_state - 7` 写 8 个零字节：`enforcing` 变为 0，而 `initialized`
   与 `policycap[]` 保持完好，因此之后的 `setenforce(1)` 是安全的。实现于
   `payload/o3p.c`（`selzero_mode()`；提交 `b6d67de`，自 `e3d25f6` 起为默认）。
   本镜像零写目标为 `0xffffffc082959629`；读回
   `state=0x0100000101010100`，终态 Enforcing 且网络正常。该写会碰到
   `avc_latest_notif_update.notif_lock` 的尾部（`0xffffffc082959628..2f`）；
   本构建该锁为 0（未加锁），并且**每换一个内核镜像都必须重新确认**（对象
   身份、大小与锁值，见 `docs/ARCHITECTURE.md` §Porting）。
5. **官方 `ksud late-load`（未采用）。** 上游 `late_load.rs` 做运行时
   kallsyms 恢复，是有效替代。我们保留自有 loader（`kallsyms_patch()`，提交
   `69f7375`；回退 `O3P_RELINK_ONLY=1` + 248 符号并集 + slide），让 App 保持
   单一自包含 blob 且错误报告有界。若想丢掉内置符号表，对方路径是更好的参考。

### 我们刻意的差异

- **自包含 APK vs AxManager 插件。** 我们的 App 内置载荷和管理器安装器，
  不依赖第三方插件管理器。
- **有界重试 vs 自动重启。** 他们失败时每天最多自动重启 6 次；我们限制尝试
  次数并打印诊断（`NO_RECLAIM`、`NO_UAF_TRIGGER`…），由人决定。
- **Loader 归属。** 自有运行时 loader + relink 回退，不用 `ksud late-load`。
- **Cred 处理。** 我们保留真实 `cred_security_struct`（合法 SID），从不伪造
  `sid=1`：该路径会得到 `unlabeled`，且 KernelSU 的 `hook_file_open` 会解引用
  假 blob → oops（`AGENTS.md` 负结果）。他们的 sid=0 假 blob 只在 Permissive
  下可用。
- **分发体积。** 他们的 release 含 `plant` + `su98260` + `ksud`（数 MB）；
  我们的三 LKM blob 为 1.49 MB。

引用前请按他们当前仓库复核；本节是快照，不是持续维护的镜像。

## 3. 邻近 Android 移植（信息性，未采用）

- `ylh440104/InfinityCore-Android-CVE-2026-98260`（2026-10-18）：ARM64 移植
  模板，目标偏移全是 `0x0000` 占位——不可运行。
- `quyicheng03-boop/CVE-2026-98260-nezha-android` 与 `…-popsicle-android`
  （2026-10-23）：小米 17 Ultra / 6.12.69 目标；只有其定位内联
  `alloc_posix_timer` 的方法有参考价值（在 `do_timer_create+0xb0` 读 `x19`），
  不适用于我们的 6.18.21 布局。
- EnneaO3V2（`LittlenineEnna`、`o3.ennea.pocv1`）：不同 CVE
  （2026-64560 / 2026-49881）；只借鉴了 APK 架构思路（可执行文件伪装成
  `.so` 随 APK 分发），未借鉴内核层。
- KernelSU 上游分支——[tiann/KernelSU](https://github.com/tiann/KernelSU)
  v3.3.1、KernelSU-Next v3.4.2、SukiSU-Ultra v4.2.1-r2：`payload/lkm/` 里是
  **未修改**的 GPL-3.0 二进制；载荷的 loader 在运行时适配
  （`O3P_KSU_FORK` 可覆盖基于管理器的选择）。

## 4. 采用矩阵

| 技术 | 来源 | 本仓库状态 | 代码 / 提交 |
|---|---|---|---|
| 纯 tracepoint slide + cred 锚点 | EAEdward | 已验证原型（B） | `experiments/tp_slide.c`、`57490bf` |
| PFN 校验 AIO 回收 | EAEdward + V4bel AIO 喷射 | 仅原型（C） | `experiments/tp_reclaim.c`、`c924964`/`812a96f` |
| 一次 UAF、三次 rb_erase 写 | EAEdward | 实验验证；发布不安全 | `O3P_D3`、`af0de5b` |
| 非对齐 enforcing-only 零写 | EAEdward | **发布默认（v3.0）** | `payload/o3p.c` `selzero_mode()`、`b6d67de`、`e3d25f6` |
| 不打 KernelSU init 补丁 | EAEdward（A 中独立得到） | **发布默认（v3.0）** | 仅 `O3P_CODEPATCH` 旧路径 |
| 运行时 kallsyms loader | 本项目（参考上游 `late_load.rs`） | **已发布** | `69f7375`、`O3P_RELINK_ONLY` 回退 |
| zombie + 伪造 RB erase + 喷射 | V4bel | 发布谱系（v1.x → v3） | `payload/legacy/`、`payload/o3p.c` |

## 5. 时间线锚点（本仓库）

| 日期（UTC+8） | 提交 | 里程碑 |
|---|---|---|
| 2026-10-23 | — | OTA 提取；CVE-2026-98260 可行性报告（现位于 `docs/history/`） |
| 2026-10-24 | — | 首次完整 root（t32 链）；KernelSU late-load 验证 |
| 2026-10-25 15:42 | `e0fa7f8` | v1.8 发布（relink-only loader） |
| 2026-10-25 17:28 | `69f7375` | 运行时 kallsyms loader + 动态 KSU 补丁点（v1.9） |
| 2026-10-25 17:59 | `78199e2` | v2.0 多 LKM blob + 管理器选择 |
| 2026-10-25 19:16 | `5dcbf68` | v2.1 权限感知探测 |
| 2026-10-25 20:43/20:59 | `18a46eb`/`b833925` | CI + 徽章/模板/安全策略 |
| 2026-10-25 21:29–21:59 | `57490bf`…`af0de5b` | 实验 B/A/C/D |
| 2026-10-25 22:29–22:42 | `e3d25f6`…`17a8cb6` | 两段式 v3 默认、v3.0 发布、文档 |
| 2026-10-26 02:17 | `291c5a0` | 时间线/准确性审计（v3 口径统一） |
| 2026-10-26 | `291c5a0` 之后 | 过时报告归档到 `docs/history/`；本文件扩写 |

## 6. 如何核对

- 常量：`ota_extract/vmlinux.elf`（symtab/BTF）或工作区副本；
  `grep -n "TIMER_LINK\|CRED_LINK\|SELINUX_STATE_STATIC" payload/o3p.c
  experiments/*.c`。
- 偏移：`docs/ARCHITECTURE.md` §BTF 与 `payload/README.md`；`tools/` 可重新
  生成表。
- 载荷行为：`bash payload/build-payload.sh`，把打印的 `libo3p.so` sha256 与
  `README.md` / `docs/BUILD.md` 比对。
- 历史结论：上面的每个数字都带提交或文件；有疑问时以真正运行的代码路径为准
  （见 `payload/README.md` 的 `O3P_*` 表）。

## 7. 致谢与许可

- V4bel / InfinityCore —— CVE-2026-98260 研究与公开的 Ubuntu PoC；我们按
  6.18.21 重新实现，未复制代码。
- EAEdward/xiaomi9pmroot（MIT）—— 上文标注为 *（采用）* 的技术；未复制源码；
  在此与 `NOTICE.md` 中致谢。
- KernelSU 上游（tiann、KernelSU-Next、SukiSU-Ultra）—— 未修改的 GPL-3.0
  LKM；见 `third_party/NOTICE.md`。
