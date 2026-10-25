# AGENTS.zh-CN.md — 自动化贡献者入口（中文版）

> 英文原版：[`AGENTS.md`](AGENTS.md)。若两者不一致，以英文版为准，并同步本文件。
> 交接细节（已验证状态、失败实验、待办、复现流程）见
> [`docs/AGENT_HANDOFF.zh-CN.md`](docs/AGENT_HANDOFF.zh-CN.md)。

本仓库交付 **O3 Root**：一个由 Shizuku 驱动的 Android 应用，在小米 `yingtian`
（Pad 9 Pro Max）/ `lhasa`（Xiaomi 18 Fold，HyperOS 4.0.26）、内核 `6.18.21`
上利用 `CVE-2026-98260`，加载 KernelSU LKM，并在当前 boot 内保留一个 root
守护进程。请先读本文件，再读 `README.md`、`docs/AGENT_HANDOFF.md`、
`docs/ARCHITECTURE.md`。

## 状态

- 发布 **v1.8**：`release/o3root2-v1.8.apk`
  （`13,973,008 B`，`sha256 0d7c5ea8b1a4d4c35d0c7c444d0c022b36ed5af9b28f7d2f9c7b31e04afff05e`）。
- 已在 `yingtian` / `OS4.0.16.0.XBMCNXM` 实机验证（冷启动，端到端）。
- `lhasa` / `OS4.0.26.0.XPNCNXM` 携带**逐位相同的内核**
  （`sha256 cde87cd7…`）；未实机测试。
- 仅当前 boot 有效：重启后 root、KernelSU 与 Permissive 状态全部消失。

## 快速命令

```bash
bash scripts/verify.sh            # 静态检查 + 可选构建（提交前运行）
bash scripts/fetch-toolchain.sh   # android.jar、build-tools r34、Shizuku jars、pyelftools

# payload（需要 NDK clang）
NDK_CC=/path/to/ndk/.../aarch64-linux-android35-clang bash payload/build-payload.sh
# 参考结果：payload 62,048 B；libo3p.so 437,400 B，sha256 a7e9833883…75ec4

# app（需要 SDK + Shizuku jars + 你自己的 keystore；见 docs/BUILD.md）
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... bash app/build.sh
```

## 目录地图

| 路径 | 内容 |
|---|---|
| `payload/o3p.c` | 利用载荷（C）：UAF 触发、页回收、RB 写原语、两阶段、KASLR slide、KernelSU 重链接/补丁/加载、rootd |
| `payload/ksu/` | `ksu_symtab.h`（225 个 KernelSU 符号 → 地址）、`mksymtab.py`（重新生成）、`relink_ksu.py`（离线重链接参考实现） |
| `payload/lkm/` | 官方 KernelSU v3.3.1 LKM（`android17-6.18`，GPL-3.0，未修改） |
| `app/src/o3/st/root1/` | Java：`Main`（UI/状态机）、`PayloadRunner`、`StateProbe`、`RecoverRunner`、`ManagerInstaller`、各监听器 |
| `app/build.sh`、`payload/build-payload.sh`、`scripts/*` | 构建入口 |
| `docs/` | ARCHITECTURE（设计+移植）、SECURITY（风险/清理）、BUILD、APP_O3_ROOT（开发日志）、OTA/内核报告、CVE 可行性、证据截图 |
| `tools/` | OTA 提取 / boot 镜像解包 / BTF+反汇编检查 |
| `release/` | 预编译 APK + SHA256SUMS |

## 不变量 —— 不要“整理/清理”

1. **内核 profile 常量是承重结构。** `INIT_USER_NS_STATIC`、
   `SELINUX_STATE_STATIC`、探针偏移、BTF 偏移与 `ksu_symtab.h` 都绑定这个
   确切的内核构建。不要重排或“简化”它们；在没有对应镜像的情况下改动 =
   内核 oops。
2. **KernelSU init 补丁只改一个立即数**：`.init.text+0x144`
   （`52 80 00 20` → `52 80 00 00`，即 `mov w0,#1` → `mov w0,#0`），文件
   偏移 `0x22144`，由 `ksu_patch()` 校验。**绝对不要**改 `+0x148` 的
   `bl setenforce`：该槽位带 `CALL26` 重定位，加载器会重写它，结果是
   `Undefined instruction`、模块卡在 `coming`（见 `docs/APP_O3_ROOT.md`）。
3. **写原语只能写 `0` 或“受控页指针”。** 它写入 `[forged_node]` 并解引用
   `parent = value & ~3`，所以 `0x100` 会被当作地址解引用。不要“干脆写
   0x0100”。
4. **`real_cred` 不会被替换。** 绝不要进程内调用 `finit_module` —— 必须
   新起一次 exec（`fork` + `sh -c insmod`）。进程内加载会在 `commit_creds`
   触发 BUG（`task->cred != task->real_cred`）。
5. **stage 0 会污染 `selinux_state.policycap[]`。** SELinux 保持 Permissive
   时无害，但任何 `setenforce 1` 都会让 Wi-Fi TX 冻结，直到 `setenforce 0`
   或重启。App（v1.8+）会检测并提供恢复；不要移除这个守护。
6. **rootd 协议**：抽象 socket `@o3p_rootd`，`SO_PEERCRED` 只接受 uid 0/2000，
   每连接一条命令，以 `__EXIT=<n>` 结束。不要把全局可写命令文件重新引入
   `/data/local/tmp`。
7. **Shizuku 必须是 shell 模式**（`uid=2000`，`u:r:shell:s0`）。root 模式的
   Shizuku 运行在 `u:r:ksu:s0`，KSU 的 bounded domain 会拒绝 exec 载荷。
8. **发布卫生**：`android:debuggable` 保持关闭；`Main.checkFirmware()` 里的
   固件门禁必须与载荷所针对的内核/分支一致（`yingtian`/`lhasa`）。

## 已验证 / 未验证

实机已验证：完整冷启动链、`uid=0` + full caps、KernelSU `Live`、管理器自动
打开、v1.8 的 Enforcing 回弹守护与一键恢复、Permissive 期间网络正常。

未验证：`lhasa` 实机；**原版 Shizuku**（我们用的是官方 `dev.rikka.shizuku`
API，但只实测过 ShizukuX 13.7）；`/system/bin/su` 只在 KernelSU 首次收到
root 请求后才出现。

负面结果 —— 没有新思路不要重复（细节见 `docs/AGENT_HANDOFF.md`）：

- io_uring ring 页**不能**回收已释放的 order-1 `posix_timers` slab
  （setup 走 order-0/vmalloc；实测 0 命中）。
- `AF_PACKET` `PACKET_TX_RING`（8 KB block）**可以**回收 order-1 页，命中率
  93.5% —— 但需要 `CAP_NET_RAW`，pre-root 的 shell/App 域没有。
- 伪造 `cred_security_struct.sid = 1` 得到的是 `unlabeled`，且 KernelSU 的
  `hook_file_open` 会解引用假 blob → oops（KSU 加载时该 SID 路线不可用）。

## 构建陷阱

- `d8` 遇到**匿名/内部类**会内部 NPE，也不接受目录输入；保持顶层类，并把
  `build/classes/**/*.class` 传给 d8。
- Git Bash + `javac`：classpath 用通配 `build/cp/*`；`;` 分隔符无效。
- Windows：给 `java`/`aapt2`/`zipalign` 传 `E:/` 正斜杠路径（`/e/...` 只在
  bash 里有效）。脚本会自动补 `.exe`。
- Python 生成 C/Java 源码：heredoc 会把 `\n` 变成真实换行 —— 这类字符串用
  `chr(92)+'n'` 拼，或改用编辑器工具写文件。
- `aapt2 link` 之前必须先 `aapt2 compile --dir res`；`-A assets` 用来打进
  内置的 KernelSU 管理器。
- 不要给 `payload/*.c` 提交 CRLF 变更噪音；索引统一为 LF。

## 设备测试检查表

1. 确认 Shizuku 以 shell 模式运行：
   `ps -A -o UID,LABEL,ARGS | grep shizuku_plus_server` → `2000 u:r:shell:s0`。
2. 安装：`adb install -r release/o3root2-v1.8.apk`；MIUI 若报
   `INSTALL_FAILED_USER_RESTRICTED`，允许“USB 安装”或用 root 执行
   `pm install -r -d`。
3. 打开 App，授予 Shizuku 权限，点 Continue；等待 20–120 秒。
4. 成功标记：`★ UIDNOW uid=0`、`KSU_INSMOD_OUT:[rc=0]`、
   `★ KSU_MODULE LIVE`、管理器在前台（`am start` 日志 + `top ->`）。
5. 健康检查：`getenforce` = Permissive、`ping <gateway>`、
   `nc 223.5.5.5 443`、`cat /proc/sys/kernel/random/boot_id` 未变。
6. 回滚：重启；可选 `rm -f /data/local/tmp/o3p*` 并卸载 App。

| 症状 | 含义 / 处理 |
|---|---|
| 反复 `VERDICT NO_RECLAIM` | 竞态是概率性的；重试（通常 1–6 次），卡住就重启 |
| `固件门禁未通过` | 内核/指纹不匹配 —— 需要新的 profile |
| KernelSU 加载后断网 | SELinux 回到 Enforcing + policycap 被污染 → 点恢复按钮或重启 |
| `KSU_CODEPATCH … -2` | 内置 LKM 不是预期的构建/偏移 —— 重新核对补丁位置 |
| 管理器打不开 | 检查 App 安装路径/包名，以及 KSU 管理器包是否存在 |

## 提交前卫生检查

```bash
bash scripts/verify.sh
```

它会检查：shell/Python 语法、必需文件、`ksu_symtab.h` 条目数、release APK
校验和、被跟踪的密钥文件，并扫描**每个文件（含二进制）**里的设备序列号、
主机路径、keystore 名/口令、抓包特征（MAC/BSSID/SSID/IP）。若失败，请修掉
命中项 —— 不要削弱检查。

规则：绝不提交 `tools/`（SDK/NDK/Shizuku jars）、`app/build`、
`payload/build`、`*.p12/*.jks`、`env.sh`、OTA zip、`.img`，或真实设备标识。
第三方许可证（`NOTICE.md`、`third_party/NOTICE.md`）必须保持准确 ——
KernelSU（LKM + 管理器 APK）是 GPL-3.0。

## 从哪里开始

- **移植到别的内核/设备**：`docs/ARCHITECTURE.md` → “Porting”；然后重新生成
  `payload/ksu/ksu_symtab.h`、更新常量、重新定位 KSU 补丁偏移、更新 App
  门禁，并做冷启动测试。
- **App/UI/功能**：`app/src/o3/st/root1/*.java`；用 `app/build.sh` 构建；
  保持类集合里没有匿名内部类。
- **载荷行为**：`payload/o3p.c`；用 `payload/build-payload.sh` 重建，并把
  `libo3p.so` 的 sha 与参考值对比。
- **开放想法 / 实验历史**：`docs/AGENT_HANDOFF.md`（中文版
  `docs/AGENT_HANDOFF.zh-CN.md`）。
