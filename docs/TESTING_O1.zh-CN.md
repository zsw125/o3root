# 实验性 O1 构建（`v3.3-o1-test`）测试指南

> 英文版：[`TESTING_O1.md`](TESTING_O1.md)

这个构建为采用小米 **O1（XRING）SoC** 的 OS4.0.0.x 设备增加了第二个内核
profile。它**只经过静态分析**，从未在 O1 真机上运行过。请把每次测试都当成可能让
设备无法启动来对待，只在你自己拥有、且能救砖的设备上测试。

## 适用设备

| 设备代号 | OTA 示例 | 内核（必须完全一致） |
|---|---|---|
| `dijun` | OS4.0.0.19.XODCNXM | `6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k` |
| `jinghu` | OS4.0.0.16.XOXCNXM | 同上 |
| `violin` | OS4.0.0.14.XOTCNXM | 同上 |

三台 OTA 的内核逐位相同（`sha256 0d9917ca…`）。如果你的内核不是这个字符串，App
门禁会阻止执行——不要绕过。

## 这个构建改了什么

- 载荷在运行时根据 `uname().release` 选择 `selinux_state` 链接地址
  （日志中的 `SELINUX_PROFILE kernel=… base=…`）。`yingtian` / `lhasa` 继续用原
  常量；O1 用 `0xffffffc082958630`。
- `Main.checkFirmware()` 接受三个 O1 代号 + 上述精确内核，并在界面显示
  **实验性**警告。
- 其余是 v3.2 的功能，外加隐藏 root 控制台（长按版本行，三道确认），便于验证
  `uid=0` 与导出日志。

## 前置条件

1. 设备已开启 ADB；**Shizuku**（或 ShizukuX）以 **shell 模式**运行：
   `shizuku_plus_server` 必须是 `uid=2000`、`u:r:shell:s0`。root 模式的 Shizuku
   （`u:r:ksu:s0`）无法执行载荷。
2. 可用的 `adb` 与一台能重装/救砖的电脑。
3. 备份重要数据。链路不写分区、仅当前 boot 有效，但会改内核内存：可能触发 panic
   或启动循环，需要恢复手段。

## 安装与运行

```bash
adb install -r release/test/o3root2-v3.3-o1-test.apk
# MIUI 拒绝时，先在开发者选项允许“USB 安装”
```

1. 打开 **O3 Root**，版本行应显示 `v3.3-o1-test`。
2. 看日志：应出现
   `固件门禁通过（O1 实验性：<代号> / 6.18.21-…-g1d099fcb35e0-…）`。若显示
   `固件门禁未通过`，停止操作并反馈日志，不要盲目重试。
3. 授予 Shizuku 权限，点 **Continue to UID 0**。
4. 等 20–120 秒。成功标记：`★ UIDNOW uid=0`、`★ KSU_MODULE LIVE`，KernelSU
   管理器自动打开。开头还应有 `SELINUX_PROFILE kernel=…` 和
   `SEL_WRITE … SEL_OK`。
5. 验证 root：长按版本行 → 通过三道确认 → 执行 `id`（应为 `uid=0`）与
   `getenforce`。
6. 点 **导出日志**（或 **复制**）并保存日志文件。

## 反馈内容

请开 GitHub issue（或在该 release 下评论），附上：

- 设备代号、完整 `Build.FINGERPRINT`、`kernel` 行、OS4 build 号；
- App 版本（`v3.3-o1-test`）、Shizuku 版本与模式（shell / root）；
- 导出的日志（至少包含 `SELINUX_PROFILE`、`VERDICT`、`UIDNOW`、`KSU_*` 行）；
- 结果：成功、反复 `VERDICT NO_RECLAIM`、panic/重启、管理器打不开等；以及
  `getenforce` 是否停在 Enforcing、网络是否正常。

## 回滚

重启即可。链路只在当前 boot 内有效，不写分区。若启动循环，用你平时的
fastboot/EDL 恢复流程。

## 已知风险（运行前必读）

- O1 内核的 `posix_cpu_timer_del` 比 yingtian 旧，UAF 时序可能不同，可能重试更多
  次或失败。
- 内置 KernelSU LKM 是为 `6.18.32` 构建、在 `6.18.21` 上重链接加载；这在
  yingtian 上成立，且 O1 内核包含它们需要的全部未定义符号，但 O1 上的模块加载
  尚未验证。
- 实验构建包含隐藏 root 控制台。通过三道确认即表示你接受 root 命令可能损坏设备。
