# O3 Root（中文版）

[![verify](https://github.com/zsw125/o3root/actions/workflows/verify.yml/badge.svg)](https://github.com/zsw125/o3root/actions/workflows/verify.yml)
[![release](https://img.shields.io/github/v/release/zsw125/o3root?display_name=tag)](https://github.com/zsw125/o3root/releases)
[![license](https://img.shields.io/github/license/zsw125/o3root)](LICENSE)
[![platform](https://img.shields.io/badge/Android%2017-kernel%206.18.21-3DDC84)](docs/ARCHITECTURE.md)
[![devices](https://img.shields.io/badge/devices-yingtian%20%7C%20lhasa-3DDC84)](docs/ARCHITECTURE.md)

> 英文原版：[`README.md`](README.md)。若两者不一致，以英文版为准并同步本文件。

针对 HyperOS 4（Android 17，内核 `6.18.21`）上的小米 **yingtian**（Pad 9 Pro
Max）与 **lhasa**（Xiaomi 18 Fold）系列，做本地提权 + KernelSU。整条链打包成一个
普通 Android 应用：它以 shell 模式驱动
[Shizuku](https://github.com/RikkaApps/Shizuku)，利用 **CVE-2026-98260**，
按已安装的管理器（KernelSU / KernelSU-Next / SukiSU）加载对应 LKM —— 不需要电脑、
不需要解锁 bootloader、不改任何分区。

> 研究 / 教育 / 设备维修项目。仅当前 boot 有效（重启即失效）。只在自己拥有的
> 设备上运行。使用前请读 [`docs/SECURITY.md`](docs/SECURITY.md)（中文版
> [`docs/SECURITY.zh-CN.md`](docs/SECURITY.zh-CN.md)）。

**自动化贡献者 / Agent：** 先读 [`AGENTS.md`](AGENTS.md)（入口：不变量、
构建/测试配方、坑、卫生要求），再读
[`docs/AGENT_HANDOFF.md`](docs/AGENT_HANDOFF.md)（已验证状态、失败实验、待办）。
提交前运行 `bash scripts/verify.sh`；配置好工具链环境时它还会重建 payload/APK。

中文版： [`AGENTS.zh-CN.md`](AGENTS.zh-CN.md) /
[`docs/AGENT_HANDOFF.zh-CN.md`](docs/AGENT_HANDOFF.zh-CN.md)

## 状态

| | |
|---|---|
| 发布 | **v3.0**（`release/o3root2-v3.0.apk`，sha256 `c80f499a…`） |
| 链路 | Shizuku shell → CVE-2026-98260 → `uid=0` + full caps → 对应 KernelSU LKM（KernelSU / KernelSU-Next / SukiSU）→ rootd |
| 持久化 | 无（仅当前 boot） |
| 已验证设备 | 小米 Pad 9 Pro Max `yingtian`，HyperOS `OS4.0.16.0.XBMCNXM` |
| 第二设备 | `lhasa`（Xiaomi 18 Fold，`OS4.0.26.0.XPNCNXM`）：其 boot 内核与 yingtian **逐位相同**（`sha256 cde87cd7…`），因此同一载荷适用；未实机测试 |

## 工作原理（简版）

1. App 通过 Shizuku 的 `newProcess` 拉起 `/system/bin/sh -c …`，因此载荷运行在
   `u:r:shell:s0`（`uid=2000`）。
2. 载荷触发 `CLOCK_THREAD_CPUTIME_ID` + `execve`/`de_thread` 竞态
   （CVE-2026-98260），用一个包含伪造 RB 树节点的 `kmalloc-8k` 喷页缓冲回收已
   释放的 `posix_timers_cache` order-1 slab 页，并借第二次
   `timerqueue_del → rb_erase` 向任意内核地址写入一个 8 字节值。
3. stage 0 写 `selinux_state.enforcing = 0`；stage 1 把 `task_struct.cred` 指向
   伪造的 cred 页（uid 0、full caps）。
4. 载荷加载内置 KernelSU LKM：未定义符号默认运行时经 `/proc/kallsyms` 解析
   （回退：248 符号并集重链接 + 当前 KASLR slide），在 `setenforce` 重定位处
   打补丁，然后 `insmod` 并留下一个 `rootd` 守护进程（抽象 socket
   `@o3p_rootd`，peer-uid 0/2000）。上游 KernelSU、KernelSU-Next、SukiSU
   三种 LKM 都可加载。

偏移、写原语、KernelSU 补丁与移植指南见
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)（中文版
[`docs/ARCHITECTURE.zh-CN.md`](docs/ARCHITECTURE.zh-CN.md)）。

## 快速开始

1. 设备上：开启开发者选项 → 无线调试（或 ADB）。安装 **Shizuku** / ShizukuX，
   并以 **shell 模式** 启动：`shizuku_plus_server` 必须是 `uid=2000`、
   `u:r:shell:s0`。
2. 安装 `release/o3root2-v3.0.apk`（MIUI 可能需要允许“USB 安装”）。
3. 打开 **O3 Root**，授予 Shizuku 权限，点 **Continue to UID 0**。日志会显示
   `★ UIDNOW uid=0`、`★ KSU_MODULE LIVE`，随后 KernelSU 管理器自动打开。

没装管理器？按钮会变成 **安装 KernelSU 管理器**，点击后安装内置 APK（按系统
提示确认即可）。

## 仓库结构

```
AGENTS.md   自动化贡献者入口（不变量、陷阱、检查）
app/        Android App 源码（Java）、manifest、res、build.sh
payload/    o3p.c（利用载荷 + 运行时 KernelSU 加载器）、248 符号回退表 /
            重链接器、mk_payload.py、build-payload.sh、legacy/（早期版本）
tools/      OTA/内核提取 + 校验脚本
docs/       架构、安全、构建、OTA 分析、可行性报告、证据截图
scripts/    工具链下载、环境模板、一键构建
release/    预编译 APK + 校验和
third_party/ 内置第三方组件的许可说明
```

## 构建

```bash
bash scripts/fetch-toolchain.sh
cp scripts/env.example.sh env.sh && . ./env.sh   # 修改路径
bash payload/build-payload.sh                    # -> payload/build/libo3p.so
bash app/build.sh                                # -> app/build/o3root2.apk
```

完整前提与排错见 [`docs/BUILD.md`](docs/BUILD.md)（中文版
[`docs/BUILD.zh-CN.md`](docs/BUILD.zh-CN.md)）。

## 限制

- **仅当前 boot**：重启会移除 root、KernelSU 与 Permissive 状态。持久化需要改
  `boot`/`init_boot`（被锁定的 bootloader 挡住），不在范围内。
- **概率性**：UAF 竞态需要 1–6 次尝试（偶尔更多）。App 会自动重试；重启通常
  有帮助。
- **Permissive SELinux 姿态**：生效期间整个系统的 MAC 关闭。见安全说明。
- **policycap 副作用**：任何把 SELinux 设回 Enforcing 的操作都会让 Wi-Fi TX
  卡住，直到 `setenforce 0` 或重启。v1.8 App 会检测并提供一键恢复。
- **KernelSU LKM ABI**：上游模块是为 `6.18.32-4k` 构建的；这里为 `6.18.21`
  内核重链接，因此会污染内核（taint）。不要把它当生产级 root 方案。
- **绑定 profile**：偏移/地址是按这个确切内核编译的。换内核构建需要新的
  profile（见 `docs/ARCHITECTURE.md` 的移植章节）。

## 证据

`docs/evidence/` 里有设备截图：App 成功日志（`v17app.png`）、KernelSU 管理器
（`v17ksu.png`）、Enforcing 回弹告警（`v18risk.png`）与一键恢复
（`v18rec.png`）。详细日志与完整记录在
[`docs/APP_O3_ROOT.md`](docs/APP_O3_ROOT.md)。

## 文档

| 文件 | 内容 |
|---|---|
| `docs/AGENT_HANDOFF.md` | 自动化贡献者交接：已验证状态、失败实验、待办、复现流程 |
| `docs/AGENT_HANDOFF.zh-CN.md` | 交接说明（中文版） |
| `docs/ARCHITECTURE.md` | 利用链、写原语、KernelSU 补丁、常量、移植 |
| `docs/ARCHITECTURE.zh-CN.md` | 架构说明（中文版） |
| `docs/SECURITY.md` | 威胁模型、副作用、恢复/清理 |
| `docs/SECURITY.zh-CN.md` | 安全说明（中文版） |
| `docs/BUILD.md` | 构建 + 安装 + 验证 |
| `docs/BUILD.zh-CN.md` | 构建说明（中文版） |
| `docs/APP_O3_ROOT.md` | 完整开发日志 / 验证报告（中文） |
| `docs/OTA_4.0.16_kernel_report.md` | OTA 提取、内核分析、KASLR 常量（中文） |
| `docs/CVE-2026-98260_FEASIBILITY.md` | 漏洞分析与上游修复引用 |
| `docs/CVE-2026-98260_FEASIBILITY.zh-CN.md` | 可行性报告（中文版） |
| `docs/ROOT_ACHIEVED_OS4.0.16.md` | 首次端到端运行记录（中文） |
| `docs/ALTERNATIVE_ROOT_DIRECTIONS.md` | 本方案之前考虑过的方向（中文） |
| `AGENTS.zh-CN.md` | 自动化贡献者入口（中文版） |
| `README.zh-CN.md` | 本 README 的中文版 |
| `payload/README.md` / `payload/README.zh-CN.md` | 载荷说明（英文 / 中文） |
| `tools/README.md` / `tools/README.zh-CN.md` | 工具脚本说明（英文 / 中文） |
| `NOTICE.zh-CN.md` | 第三方组件说明（中文版） |
| `third_party/NOTICE.zh-CN.md` | 第三方组件明细（中文版） |

## 免责声明

本项目仅用于**安全研究与教育**目的发布。

- **只可用于你本人拥有**、且可以承受丢失或重置的设备；不要在共享、公司或第三方设备上使用。
- 运行本工具可能导致设备**崩溃、重启或软砖**，造成**数据丢失**，并使**保修失效**。它会修改内核内存并加载未签名内核模块；在下一次重启前，设备处于非标准、已污染状态。
- 在你所在司法辖区或设备/运营商条款下，使用本工具可能**违法或违约**；合规责任由使用者自行承担。
- 本软件按**“现状”提供，不附带任何明示或默示担保**，包括但不限于适销性、特定用途适用性与不侵权担保。作者与贡献者**不对**因使用本软件产生的任何索赔、损害或其它责任负责。
- 本项目与小米、KernelSU、Shizuku、Google 无隶属、背书或支持关系；相关商标归其各自所有者。
- 若不接受上述条款，请勿使用本软件。

## 许可证

MIT —— 见 [`LICENSE`](LICENSE)。内置第三方组件保留各自许可证；见
[`NOTICE.md`](NOTICE.md) 与 [`third_party/NOTICE.md`](third_party/NOTICE.md)
（中文说明见 [`NOTICE.zh-CN.md`](NOTICE.zh-CN.md)）。

---

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
