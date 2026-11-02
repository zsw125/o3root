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

- 发布 **v3.2**（最新稳定版）：`release/o3root2-v3.2.apk`
  （`14,239,248 B`，`sha256 64bbc65420181babd69b826de7b86be99409963dd29253b72a2930d7a7ff6cfd`）。
- **实验性测试构建 v3.3.2-o1-test**：`release/test/o3root2-v3.3.2-o1-test.apk`
  （`14,247,440 B`，`sha256 7aa29c483d75562f2b35418911fdc6d0ca2921def1710ff4fcd3fb02115da4df`）：
  v3.2 + O1（XRING）内核 profile + 隐藏 root 控制台（versionCode 22 / `3.3.2-o1-test`）；
  `dijun` / `jinghu` / `violin` 仅经静态分析，未真机验证。上一测试构建
  `release/beta/o3root2-v3.2.1-console.apk`（`14,243,344 B`，`sha256 9cba365a…`）
  留在树里作为历史。
  载荷内置 tiann / KernelSU-Next / SukiSU 三种 LKM，按已安装的管理器自动选择（`O3P_KSU_FORK` 可覆盖），
并默认运行时解析符号（kallsyms）；v2.1 让探测先检查 Shizuku 授权、用本地包管理器识别管理器、
授权后先探测再决定是否跑载荷，并使用唯一载荷文件名；v3.0 改为两段式（`selinux_state-7` 只清
enforcing + cred 写入），并默认不打 KernelSU init 补丁（`policycap` 干净，KSU 自己的
`setenforce(1)` 无害）；v3.1 增加自定义 .ko：应用先做 ELF/.modinfo 基础校验（AArch64 ET_REL、name=kernelsu、同一 6.18 系列），未通过时提示并可退回自适应内置模块；载荷会再次校验，文件不可读或加载失败时自动回退。v3.1.1 把选择持久化到应用 files 目录
（`custom-ko.ko` + `custom-ko.meta`），启动时恢复，失效文件自动丢弃，长按模块行清除。v3.2 让 LogBox 可选中、可复制（LogBox 头部“复制”链接复制全部日志；输出区可选中，只有读到底部附近才自动滚动）。测试构建（v3.2.1-console / v3.3.2-o1-test）额外把 root 控制台藏在长按版本行之后：三道不可跳过的确认（风险对话框、复选框、输入 `I ACCEPT ROOT RISK`）加一次性 token 才能进入；命令在载荷启动后经 rootd socket 执行（接受 uid 0/2000）。正式版不编译控制台类。v3.3 增加第二个内核 profile：载荷优先根据 App 导出的 `O3P_SELINUX_PROFILE`，否则
按 `uname().release` 选择 `selinux_state` 链接地址（日志
`SELINUX_PROFILE kernel=… base=… src=env|uname`）。`Main.checkFirmware()` 接受
`dijun` / `jinghu` / `violin` 配已分析的 O1 内核
`6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k`；对同属 OS4.0.0.x、
`6.18.21-android17…-4k` 系列的其他内核走“宽松模式”（沿用同一 O1 地址并给出
更强警告），系列外仍然拦截。三份 O1 OTA 内核
逐位相同（`sha256 0d9917ca…`）；相对 yingtian 只有 `selinux_state` 不同
（`0xffffffc082958630`），`init_user_ns` 相同。识别到 O1 机型时，App 会在启动载荷前弹出两步警告确认（风险说明 + 勾选后
才能点“开始提权”），确认后才会开始流程。该 profile 仅经静态分析，
没有任何 O1 真机跑过。三个内置 LKM 未变（2026-10-29 时除 SukiSU 外均为上游最新，SukiSU 现已有 v4.3.0，见 `python3 tools/check_lkm_updates.py`）；v1.8/v1.9 保留在 git 历史里。
- 已在 `yingtian` / `OS4.0.16.0.XBMCNXM` 实机验证（冷启动、端到端）。v3.1/v3.1.1
  于 2026-11-01 复验：自适应冷启动（`KSU_BLOB v2 tag=3 matched=1`）、自选 tiann
  v3.3.1 `.ko`（警告接受、force-stop 后仍恢复）、强制 6.6.000 模块
  （`CUSTOM_KO force` → relink → `KSU_MODULE LIVE`）、长按清除。自动回退分支因强制
  模块仍能加载而未在真机触发，仍只做过桌面测试。
- v3.2 / v3.2.1-console 于 2026-11-01 实机验证：正式版显示可选中 LogBox 与“复制”操作，长按版本行无控制台入口；测试版通过三道确认、用一次性 token 打开控制台，并经 rootd 以 uid=0 执行 `id`（`[exit 0]`）；把控制台输出复制到剪贴板后再粘贴回来验证通过。
- v3.3 的 O1 profile **只做过静态分析**：O1 与 yingtian 内核做了符号/BTF/
  config/指令级对比（见 `docs/AGENT_HANDOFF.md`），但没有任何 O1 设备跑过
  载荷或 KernelSU LKM。
- `lhasa` / `OS4.0.26.0.XPNCNXM` 携带**逐位相同的内核**
  （`sha256 cde87cd7…`）；未实机测试。
- 仅当前 boot 有效：重启后 root 与 KernelSU 消失（旧 `O3P_SELDIRTY=1` 路径下的 Permissive 也一并消失；v3 默认终态是 Enforcing）。

## 快速命令

```bash
bash scripts/verify.sh            # 静态检查 + 可选构建（提交前运行）
bash scripts/fetch-toolchain.sh   # android.jar、build-tools r34、Shizuku jars、pyelftools

# payload（需要 NDK clang）
NDK_CC=/path/to/ndk/.../aarch64-linux-android35-clang bash payload/build-payload.sh
# 参考结果：payload 77,928 B；含三种 LKM 的 libo3p.so 1,496,644 B，sha256 0d3d4dd5…0701

# app（需要 SDK + Shizuku jars + 你自己的 keystore；见 docs/BUILD.md）
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... bash app/build.sh

# 测试版构建（隐藏 root 控制台；版本/versionCode 可用环境变量覆盖）
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... \
PAYLOAD=... BETA=1 BETA_VERSION=3.3.2-o1-test BETA_CODE=22 bash app/build.sh
```

## 目录地图

| 路径 | 内容 |
|---|---|
| `payload/o3p.c` | 利用载荷（C）：UAF 触发、页回收、RB 写原语、两阶段、KASLR slide、KernelSU 重链接/kallsyms 加载（默认不打补丁，旧 `O3P_CODEPATCH=1` 才有补丁）、rootd |
| `payload/ksu/` | `ksu_symtab.h`（上游各 fork 的 248 个 KernelSU 符号并集，供 relink 回退）、`mksymtab.py`（重新生成单 LKM 表）、`relink_ksu.py`（离线重链接参考实现） |
| `payload/lkm/` | 官方 `android17-6.18` LKM（GPL-3.0，未修改）：KernelSU v3.3.1、KernelSU-Next v3.4.2、SukiSU-Ultra v4.2.1-r2 |
| `app/src/o3/st/root1/` | Java：`Main`（UI/状态机）、`PayloadRunner`（也负责把自定义 .ko 推到设备）、`CustomKo`+`KoResult`（ELF/.modinfo 校验）、`LongClearKo`（长按清除）、`StateProbe`、`RecoverRunner`（旧守护，v3 已停用）、`ManagerInstaller`、各监听器；`BuildInfo`（版本+控制台开关）、`ClickCopyLog`；仅测试版的 `ConsoleEntry`（反射入口）、`ConsoleGate`（三道确认）、`ConsoleActivity`+`ConsoleTask`（终端） |
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
   断网根因已消除：stage0 在 `selinux_state-7` 做 8 字节非对齐零写清掉 `enforcing`
   （同时清掉相邻锁的 pending/tail 字节，`initialized`/`policycap` 原样保留），
   所以 KSU 重新拉回
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
   清掉 `enforcing`（相邻锁的 pending/tail 字节也一并清零），所以 Enforcing 是安全终态、App 不再带恢复守护。只有旧路径
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

实机已验证（v3.0）：完整冷启动链（`uid=0` + full caps）、KernelSU `Live` 且
**未打 init 补丁**、管理器自动打开、Enforcing 终态下网络正常（`ping`/`nc`）、
`boot_id` 未变。v1.8–v2.1 另外验证过旧的守护/一键恢复与 Permissive 期间网络正常
（这两项在 v3.0 已移除）。v2.1 发布 APK（权限感知探测：先查授权、本地识别管理器、授权后先探测再跑载荷）
已在三种管理器镜像上冷启动验证（仅 tiann / 仅 KernelSU-Next / 仅 SukiSU）：
应用识别管理器、载荷从内置 LKM 中选对应版本、对应管理器被拉到前台。

未验证：**O1 实机**（dijun/jinghu/violin —— 见 `docs/TESTING_O1.md`）；
`lhasa` 实机；**原版 Shizuku**（我们用的是官方 `dev.rikka.shizuku`
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
- `BETA=1` 会写 `build/gen/BuildInfo.java`（`CONSOLE=true`、`VERSION=$BETA_VERSION`，默认 `3.2.1-console`）并生成 `build/AndroidManifest.xml`（versionCode `$BETA_CODE`，默认 18，加入 `ConsoleActivity`）。正式版编译跟踪的 `BuildInfo.java`（`CONSOLE=false`、`3.3`）并排除 `ConsoleActivity.java`/`ConsoleGate.java`；`Main` 只经 `ConsoleEntry` 反射到达门禁，正式版代码不得直接链接 `ConsoleGate`。
- 保持 LogBox 可选中、可复制：不要删 `setTextIsSelectable(true)`；自动滚动必须只在接近底部时触发。

## 设备测试检查表

1. 确认 Shizuku 以 shell 模式运行：
   `ps -A -o UID,LABEL,ARGS | grep shizuku_plus_server` → `2000 u:r:shell:s0`。
2. 安装：`adb install -r release/o3root2-v3.2.apk`；MIUI 若报
   `INSTALL_FAILED_USER_RESTRICTED`，允许“USB 安装”或用 root 执行
   `pm install -r -d`。
3. 打开 App，授予 Shizuku 权限，点 Continue；等待 20–120 秒。
4. 成功标记：`★ UIDNOW uid=0`、`KSU_INSMOD_OUT:[rc=0]`、
   `★ KSU_MODULE LIVE`、管理器在前台（`am start` 日志 + `top ->`）。
5. 健康检查：`getenforce` = Enforcing（v3；旧的脏写路径会停在 Permissive）、`ping <gateway>`、
   `nc 223.5.5.5 443`、`cat /proc/sys/kernel/random/boot_id` 未变。
5.5 若本轮失败需重跑：先确认 `shizuku_plus_server` 仍是 `2000 u:r:shell:s0`。
   KSU 授权后 ShizukuX 可能以 root 模式（`u:r:ksu:s0`）重启，该域是 bounded
   transition，会拒绝 exec 载荷 —— 需重启 Shizuku server（或重启设备）回到
   shell 模式后再点 Continue。
6. 回滚：重启；可选 `rm -f /data/local/tmp/o3p*` 并卸载 App。
7. 测试版控制台（仅测试构建）：安装测试 APK（`release/beta/o3root2-v3.2.1-console.apk` 或 `release/test/o3root2-v3.3.2-o1-test.apk`，versionCode 均高于正式版 17，可直接升级）；先跑一次载荷让 rootd 在跑，长按版本行并通过三道确认；`id` 必须显示 `uid=0`。正式版做同样的长按必须没有控制台入口。
8. O1（`dijun` / `jinghu` / `violin`）：按 `docs/TESTING_O1.md` 操作。已分析内核打印
   `固件门禁通过（O1 实验性：<代号> / <kv>）`，同系列其他构建打印
   `固件门禁通过（O1 宽松模式：<代号> / <kv>）`；两者都应出现
   `SELINUX_PROFILE kernel=… base=0xffffffc082958630 src=env|uname`。即使失败也要导出
   日志反馈。本机没有 O1 设备，绝不能声称已验证。

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
  `payload/ksu/ksu_symtab.h`、更新常量、重新定位 KSU 补丁偏移（仅当保留旧 `O3P_CODEPATCH=1` 路径时）、更新 App
  门禁，并做冷启动测试。
- **App/UI/功能**：`app/src/o3/st/root1/*.java`；用 `app/build.sh` 构建；
  保持类集合里没有匿名内部类。
- **载荷行为**：`payload/o3p.c`；用 `payload/build-payload.sh` 重建，并把
  `libo3p.so` 的 sha 与参考值对比。
- **开放想法 / 实验历史**：`docs/AGENT_HANDOFF.md`（中文版
  `docs/AGENT_HANDOFF.zh-CN.md`）。

### v3（当前）

v3 保留两段式利用，但两次写都改了：stage0 是清 `enforcing` 的零写
（在 `selinux_state-7` 做 8 字节非对齐写，同时清掉相邻锁的 pending/tail 字节），
stage1 仍是 `task_cred` 换指针。之后用运行时
kallsyms 加载器装 KSU，**不打 init 补丁**；KSU 自己的 `setenforce(1)` 无害（policycap
干净），所以设备停在 Enforcing 且 Wi-Fi 正常。曾尝试“一次 reap 三次写”，连续 panic，
现仅保留在 `O3P_D3V3` 后供分析。yingtian 冷启已验证（`KSU_CODEPATCH skip`、
`KALLSYMS_PATCH found=225`、`KSU_MODULE LIVE`、Enforcing、`ping`/`nc` 正常、管理器已打开）。
