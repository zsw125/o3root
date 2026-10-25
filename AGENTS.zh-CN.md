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

- 发布 **v3.0**：`release/o3root2-v3.0.apk`
  （`14,218,768 B`，`sha256 c80f499a6b06ddf92241aa8cb0aa8874767eccdd960e631b28dcff98e7c141dd`）；
  载荷内置 tiann / KernelSU-Next / SukiSU 三种 LKM，按已安装的管理器自动选择（`O3P_KSU_FORK` 可覆盖），
并默认运行时解析符号（kallsyms）；v2.1 让探测先检查 Shizuku 授权、用本地包管理器识别管理器、
授权后先探测再决定是否跑载荷，并使用唯一载荷文件名；v1.8/v1.9 保留在 git 历史里。
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
# 参考结果：payload 73,768 B；含三种 LKM 的 libo3p.so 1,492,484 B，sha256 e64217c8…87dfa

# app（需要 SDK + Shizuku jars + 你自己的 keystore；见 docs/BUILD.md）
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... bash app/build.sh
```

## 目录地图

| 路径 | 内容 |
|---|---|
| `payload/o3p.c` | 利用载荷（C）：UAF 触发、页回收、RB 写原语、两阶段、KASLR slide、KernelSU 重链接/补丁/加载、rootd |
| `payload/ksu/` | `ksu_symtab.h`（上游各 fork 的 248 个 KernelSU 符号并集，供 relink 回退）、`mksymtab.py`（重新生成单 LKM 表）、`relink_ksu.py`（离线重链接参考实现） |
| `payload/lkm/` | 官方 `android17-6.18` LKM（GPL-3.0，未修改）：KernelSU v3.3.1、KernelSU-Next v3.4.2、SukiSU-Ultra v4.2.1-r2 |
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
2. **v3 默认不给 KernelSU 打 init 补丁 —— 保持这个默认。**
   断网根因已消除：stage0 只把 `enforcing` 字节清零（在 `selinux_state-7` 做 8 字节
   非对齐零写，`initialized`/`policycap` 原样保留），所以 KSU 重新拉回
   Enforcing 后 Wi-Fi 仍正常；日志里 `KSU_CODEPATCH skip` 即正确路径。
   仅用于对比时可用 `O3P_CODEPATCH=1` 回退旧补丁（`.rela.init.text` 动态定位，
   tiann/Next `0x144`、SukiSU `0x178`；文件偏移 `0x22144`/`0x37144`/`0x3e178`）。
   **绝对不要**改 `+0x148` 的 `bl setenforce`：该槽位带 `CALL26` 重定位，加载器会重写它，
   结果是 `Undefined instruction`、模块卡在 `coming`（见 `docs/APP_O3_ROOT.md`）。
3. **写原语只能写 `0` 或“受控页指针”。** 它写入 `[forged_node]` 并解引用
   `parent = value & ~3`，所以 `0x100` 会被当作地址解引用。不要“干脆写
   0x0100”。
4. **`real_cred` 不会被替换。** 绝不要进程内调用 `finit_module` —— 必须
   新起一次 exec（`fork` + `sh -c insmod`）。进程内加载会在 `commit_creds`
   触发 BUG（`task->cred != task->real_cred`）。
5. **v3 的 stage0 不再污染 `policycap`。** 在 `selinux_state-7` 做 8 字节非对齐零写
   只清 `enforcing`，所以 Enforcing 是安全终态、App 不再带恢复守护。只有旧路径
   `O3P_SELDIRTY=1` 会污染 `policycap[]`；用它时任何 `setenforce 1` 都会让 Wi-Fi TX
   冻结，直到 `setenforce 0` 或重启。
6. **rootd 协议**：抽象 socket `@o3p_rootd`，`SO_PEERCRED` 只接受 uid 0/2000，
   每连接一条命令，以 `__EXIT=<n>` 结束。不要把全局可写命令文件重新引入
   `/data/local/tmp`。
7. **Shizuku 必须是 shell 模式**（`uid=2000`，`u:r:shell:s0`）。root 模式的
   Shizuku 运行在 `u:r:ksu:s0`，KSU 的 bounded domain 会拒绝 exec 载荷。
8. **发布卫生**：`android:debuggable` 保持关闭；`Main.checkFirmware()` 里的
   固件门禁必须与载荷所针对的内核/分支一致（`yingtian`/`lhasa`）。

## 已验证 / 未验证

实机已验证：完整冷启动链、`uid=0` + full caps、KernelSU `Live`、管理器自动
打开、Enforcing 回弹守护与一键恢复、Permissive 期间网络正常；v2.1 发布 APK（权限感知探测：先查授权、本地识别管理器、授权后先探测再跑载荷）
已在三种管理器镜像上冷启动验证（仅 tiann / 仅 KernelSU-Next / 仅 SukiSU）：
应用识别管理器、载荷从内置 LKM 中选对应版本、对应管理器被拉到前台。

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
2. 安装：`adb install -r release/o3root2-v3.0.apk`；MIUI 若报
   `INSTALL_FAILED_USER_RESTRICTED`，允许“USB 安装”或用 root 执行
   `pm install -r -d`。
3. 打开 App，授予 Shizuku 权限，点 Continue；等待 20–120 秒。
4. 成功标记：`★ UIDNOW uid=0`、`KSU_INSMOD_OUT:[rc=0]`、
   `★ KSU_MODULE LIVE`、管理器在前台（`am start` 日志 + `top ->`）。
5. 健康检查：`getenforce` = Enforcing（v3；旧的脏写路径会停在 Permissive）、`ping <gateway>`、
   `nc 223.5.5.5 443`、`cat /proc/sys/kernel/random/boot_id` 未变。
6. 回滚：重启；可选 `rm -f /data/local/tmp/o3p*` 并卸载 App。

| 症状 | 含义 / 处理 |
|---|---|
| 反复 `VERDICT NO_RECLAIM` | 竞态是概率性的；重试（通常 1–6 次），卡住就重启 |
| `固件门禁未通过` | 内核/指纹不匹配 —— 需要新的 profile |
| KernelSU 加载后断网 | 仅旧路径 `O3P_SELDIRTY=1`：policycap 被污染 + Enforcing → `setenforce 0` 或重启 |
| `KSU_CODEPATCH … -2` | 没找到 `setenforce` 重定位（唯一扫描也失败）—— 核对 LKM |
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

### v3（当前）

v3 保留两段式利用，但两次写都改了：stage0 是**只清 enforcing** 的零写
（在 `selinux_state-7` 做 8 字节非对齐写），stage1 仍是 `task_cred` 换指针。之后用运行时
kallsyms 加载器装 KSU，**不打 init 补丁**；KSU 自己的 `setenforce(1)` 无害（policycap
干净），所以设备停在 Enforcing 且 Wi-Fi 正常。曾尝试“一次 reap 三次写”，连续 panic，
现仅保留在 `O3P_D3V3` 后供分析。yingtian 冷启已验证（`KSU_CODEPATCH skip`、
`KALLSYMS_PATCH found=225`、`KSU_MODULE LIVE`、Enforcing、`ping`/`nc` 正常、管理器已打开）。
