# 安全说明与威胁模型（中文版）

> 英文原版：[`SECURITY.md`](SECURITY.md)。若两者不一致，以英文版为准并同步本文件。

**适用范围：** v3.0（2026-10-25）；旧 v1.7–v2.1 行为会单独标注。

## 先读这一节

本仓库包含一个**可用的本地提权 exploit**，针对一台在发布时已完全打补丁的特定
Android 设备与固件。发布目的是研究、教育，以及帮助受影响的设备机主理解/修复
自己的设备。

- 只在**你自己拥有**、且愿意清空/重置的设备上运行。
- 不要用于共享、公司或第三方的设备。
- 该 exploit **不持久**：重启会清除它做过的一切。
- 这里不写分区、不改 `vbmeta`、不解锁 bootloader。设备的 verified boot 状态
  不变。

## 威胁模型

| 攻击者起点 | 能力 |
|---|---|
| 任意已安装 App | 不能直接跑这条链（App 域受 seccomp 过滤、没有 `CAP_NET_RAW`；tracefs 被拒） |
| Shizuku **shell** server（`uid=2000`，`u:r:shell:s0`） | 能端到端跑通（本仓库的 APK 就是这么做的） |
| ADB shell（USB 调试） | 同上 |
| 已有 root | 完全的内核/用户态控制；不需要本仓库 |

干净设备上的预期流程：安装 Shizuku，以 shell 模式启动（无线调试 / ADB），安装
本 App，点 Continue。App 在 Shizuku 拉起的 shell 进程里运行载荷，该进程变成
root 并加载 KernelSU。

## 运行时改了什么

载荷只修改内核内存：

1. `selinux_state`（stage 0）：在 `selinux_state-7` 做 8 字节非对齐零写，清
   `enforcing`（同时清相邻 `notif_lock` 的 pending/tail 字节）；`initialized` 与
   `policycap[]` 原样保留（只有旧路径
   `O3P_SELDIRTY=1` 仍写旧的脏值、会污染 `policycap[]`）。
2. 载荷进程的 `task_struct.cred`（stage 1）→ 一个伪造 cred 页：uid 0、
   full capabilities，SELinux blob 沿用原来的（仍是 shell 域）。
3. 加载一个 KernelSU LKM（运行时按当前 KASLR slide 重链接），**默认不打 init
   补丁**；`policycap` 干净，所以 KSU 自己 `setenforce(1)` 不影响网络。
   `O3P_CODEPATCH=1` 才会回退旧补丁（那时才需要下面的旧脏写注意事项）。

除了以下文件，不写磁盘：

- `/data/local/tmp/o3p_<millis>.bin`（App 复制的载荷，模式 0755）与
  `/data/local/tmp/o3p_ksu.ko`（运行时重定基后的 LKM，模式 0644；内置 LKM 本身
  未改，v3 既不补 `.init.text` 也不改字节，只按当前 slide 重写 UNDEF 符号地址），
- `/data/local/tmp/o3p_rootd.log`（模式 0600）与 proof 文件
  `/data/local/tmp/o3p_proof.txt`（经 `sh` 重定向写入，模式 0666 再受 umask），
  以及 `/data/local/tmp/o3p_stage.txt`（模式 0644），
- 按需诊断文件：`/data/local/tmp/o3p_insmod.out`、`/data/local/tmp/canary_t34`
  （模式 0666，stage-0 探针），可选的 `o3p_sd.log`、`o3p_post.sh` /
  `o3p_post.out`，
- App 外部文件目录下的可选日志。

## 已知副作用

### 旧 policycap 污染（仅 `O3P_SELDIRTY=1`）→ 开启 enforcing 时断网

v3 默认不碰 `policycap[]`。只有旧的脏写（`O3P_SELDIRTY=1`）无法保留它，此时重新
开启 SELinux 强制模式（任何 `setenforce 1`）会让 Wi-Fi 发送路径卡死：`wlan0` TX
冻结、RX 继续、所有流量失效。恢复：

- `setenforce 0`（需要有 `CAP_MAC_ADMIN` 的上下文，例如 KernelSU 的 `su`），或
- 重启（清除污染；root/KSU 丢失）。

v1.8–v2.1 会探测并告警、提供一键 `su -c setenforce 0`；v3.0 已移除，因为默认
路径不再污染 `policycap`。

### 全局 Permissive SELinux

设备停留在 Permissive 期间，所有 SELinux MAC 检查都关闭：

- SELinux 域隔离消失；本地 App 被攻破后更容易变成 root（tracefs/kprobe 接口是
  0777，只靠 SELinux 拦着）。
- 某些系统组件行为会变（例如日志里观察到 zygote/`powerkeeper` 的
  `selinux_android_setcontext` 失败）。
- 可检测：`getenforce`、KernelSU 管理器的“宽容模式”标记。

这不是日常主力机该有的姿态。用完请重启。

### 内核 taint / ABI 错配

KernelSU LKM 是上游为 `android17-6.18`
（`6.18.32-4k-g47cc22554442-dirty`）构建的，却加载在 `6.18.21-…` 上。运行时
重链接绕过了符号/CRC 命名空间检查；模块处于 tainted 状态（`/proc/modules` 里的
`(O)`）。请把它当研究环境，而不是生产级 root。

### 残留文件与标签

正常运行时，`/data/local/tmp` 里创建的文件会得到
`u:object_r:shell_data_file:s0`，没有问题。被废弃的“零写”实验
（`initialized=0`）期间创建的文件会停留在 `u:object_r:unlabeled:s0`，在
enforcing 下 shell 无法读取/删除。在非 enforcing 且拿到 root 的 boot 里可以批量
修复：

```sh
chcon -R u:object_r:shell_data_file:s0 /data/local/tmp
```

或者删掉重建；标签是持久化的 `security.selinux` xattr。

### Seccomp 观测

在目标机上实测（干净冷启动）：`init`、`adbd`、`zygote64`、Shizuku server 与
rootd **没有 seccomp 过滤**；`system_server`、`systemui` 与普通 App 是 2/1。
本仓库不改变任何进程的 seccomp 状态 —— 这是 Android 侧的设计属性，这里记录
只是因为它影响可利用性。

## 恢复 / 清理清单

1. 如果网络坏了，先 `setenforce 0`（或重启）。
2. 删除测试产物：`rm -f /data/local/tmp/o3p*`（需 root），卸载 APK；若 App 装过
   KernelSU 管理器，也一并卸载。
3. 重启：SELinux 回到 Enforcing、`selinux_state` 完好、没有 root。
4. 如果你给某些 App 授予过 KSU 超级用户，`ksud`/管理器允许列表在 `/data/adb`
   下且能跨重启保留 —— 用管理器移除，或在你安装过的情况下执行
   `rm -rf /data/adb/ksu /data/adb/modules`。

## 报告

在未读 `docs/history/CVE-2026-98260_FEASIBILITY.md` 之前，请不要把本工作当作漏洞提交给
小米的赏金计划；底层漏洞（`CVE-2026-98260`，以及更早的
`CVE-2026-64560`/`CVE-2026-49881`）都在该文件里记录了上游修复引用。
