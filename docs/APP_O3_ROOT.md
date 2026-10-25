# O3 Root — 一体化 APK 开发日志（CVE-2026-98260 + KernelSU）

一句话：装一个 APK，点一次按钮，在**没有电脑、没有 root** 的机器上完成
提权 → SELinux 关闭 → 加载 KernelSU LKM → 自动打开 KernelSU 管理器。

> 说明：本文件是开发过程的原始日志，保留当时的目录名（`work/`、`app5/`、`ota_extract/` 等）。仓库中的正式位置是 `payload/`（载荷与内核符号）、`app/`（APK 源码）、`docs/`（本目录）。接手开发请先读根目录 `AGENTS.md`。

## 产物（v1.8，历史）

> 当前发布件是 **v3.0**：`release/o3root2-v3.0.apk`（14,218,768 B，sha256
> `c80f499a6b06ddf92241aa8cb0aa8874767eccdd960e631b28dcff98e7c141dd`）。下表的
> 13,973,008 B/`0d7c5ea8…` 仅指 v1.8 的 APK，保留作历史记录。

| 文件 | 大小 | sha256 |
|---|---|---|
| `app/build/o3root2.apk`（v1.8） | 13,973,008 B | `0d7c5ea8b1a4d4c35d0c7c444d0c022b36ed5af9b28f7d2f9c7b31e04afff05e` |

包名 `o3.st.root1`，versionName `1.8`（versionCode 9），minSdk 31 / targetSdk 34，
自签（keystore 自建，见 `docs/BUILD.md`）。**release 构建：`android:debuggable` 已移除。**

## 前提（设备侧一次性设置）

1. 安装 **Shizuku**（本机为 ShizukuX 13.7.0.k2014），并让它以 **shell 模式**
   （设置里的「通过无线调试启动」，无需电脑）运行。判断方法：
   `ps -A -o UID,LABEL,ARGS | grep shizuku_plus_server` 应显示 `2000  u:r:shell:s0`。
2. 首次点按钮时 Shizuku 会弹授权框，选「始终允许」。

## v1.8 新增（Enforcing 回弹守护 + 一键恢复）

- 状态探测增加判定：`kernelsu 已加载 && getenforce == Enforcing` ⇒ 认定 policycap 污染即将生效。
- UI：SELinux 行显示 `Enforcing ⚠`（琥珀），红色提示「SELinux 已回到 Enforcing：网络可能中断，
  点按钮恢复 Permissive」，按钮变为「恢复 SELINUX 宽容模式」。
- 一键恢复：优先 `timeout 12 su -c 'setenforce 0' 2>&1; getenforce`（走 KSU su，实测有效）；
  输出含 `Permissive` 则恢复成功并重新探测；否则回退为「重跑载荷」（用 stage0 写回 0）。
- 实测（2026-10-25 05:14，boot `c83360b8`）：`setenforce 1` → 网络断（ping 超时、`nc443=1`）
  → App 显示警告与恢复按钮 → 点击 → 日志 `recover: Permissive`、`✔ 已恢复 SELinux 宽容模式`
  → 网络立刻恢复（ping 2.96 ms、`nc443=0`），按钮回到「打开 KERNELSU」。
- 注意：KSU 授权 shell 后 ShizukuX 会以 **root 模式**重启 server（`uid=0 u:r:ksu:s0`）；
  此时若回退到「重跑载荷」，ksu 域的 bounded transition 会拒绝 exec
  `/data/local/tmp/o3p.bin`（需重启到 Shizuku shell 模式后再跑）。

## v1.7 新增（修复：加载 KernelSU 后断网）

**根因**：KernelSU LKM 的 init 会强制把 SELinux 拉回 Enforcing——
`.init.text` 里先 `getenforce`，若为 0 则 `_printk` 提示，然后
`.init.text+0x144` 的 `mov w0, #1` 配合 `.init.text+0x148` 的 `bl setenforce`
调用模块自带的 `setenforce(1)`（直接写 `selinux_state.enforcing`）。
而我们的 stage0 用 8 字节写把 `selinux_state` 前 8 字节写成
`enforcing=0 / initialized=1 / policycap[0..5]=地址垃圾`；一旦回到 Enforcing，
这些被污染的 policycap 会让 socket 层检查整体失效 → Wi-Fi TX 冻结、断网
（ping 100% 丢包、`wlan0 TX` 不再增长，但 RX 正常）。

**修复**：载荷在 `ksu_patch()` 里随符号重链接一起给 ko 打一个 4 字节代码补丁：
把 `.init.text+0x144` 的 `mov w0, #1`（`52 80 00 20`）改成
`mov w0, #0`（`52 80 00 00`），于是 KSU init 调用的是 `setenforce(0)`（无副作用），
SELinux 保持 Permissive，网络不受影响。日志会打印
`KSU_CODEPATCH mov_w0_zero=1`。

**踩坑记录**：不能把 `.init.text+0x148` 的 `bl setenforce` 直接改成 NOP——
该指令有 `R_AARCH64_CALL26` 重定位，模块加载器会重新写入并触发 BRK，
表现为 `kernelsu_init+0x144` 的 Undefined instruction oops、模块卡在
`/proc/modules` 的 `Loading`（`initstate=coming`），insmod 进程 SIGSEGV。
所以只改 `mov` 的立即数、保留 `bl` 让重定位正常解析。

**实测（冷启，2026-10-25 04:56–04:58，boot_id `c83360b8-191f-4480-8a28-6929ccfeee98`）**：

```
KSU_BLOB len=375328 slide=0x2905c00000
KSU_CODEPATCH mov_w0_zero=1
KSU_PATCH symtabs=1 found=225 missing=0
KSU_EXEC done=1 status=0x0
KSU_INSMOD_OUT:[rc=0]
KSU_MODULE LIVE
ROOTD_OUT: uid=0(root) ... context=u:r:shell:s0 / Permissive
```

内核侧核对：`/proc/modules` = `kernelsu 192512 1 - Live`；
`getenforce` = **Permissive**；`/sys/module/kernelsu/initstate` 无（已 live）；
`ping <gateway>` 3.0 ms、`nc 223.5.5.5:443` rc=0；boot_id 未变。
管理器自动打开，显示「工作中 [越狱模式] 32680-5 / LKM / 宽容模式」。

### 影响与残留风险（v1.7 补丁）

补丁只改了 `kernelsu_init` 里 `setenforce()` 的入参（`mov w0,#1` → `#0`），
其余（hook、feature、模块初始化路径）不变。已验证**不受影响**：

- KSU 本体：`kernelsu ... - Live`、管理器「工作中 [越狱模式]」、超级用户/模块列表、`ksud debug info`；
- 网络/DNS/稳定性：ping、`nc 223.5.5.5:443`、DNS、uptime 512 s+、dmesg oops 计数 0；
- 提权结果：uid 0 + full caps + rootd（`@o3p_rootd`，peercred 放行 0/2000）；
- **adb shell 直接 root**：KSU 加载并授权 shell 后，`adb shell` 变为
  `uid=0(root) ... context=u:r:ksu:s0`，且 `/system/bin/su` 会被 KSU 装上
  （本次实测 05:04 出现，4,990,848 B、`root:root 755`）。

需要知道的残留影响：

1. **Enforcing 回弹 = 断网（已实测，且可即时恢复）**：`selinux_state.policycap[6]`
   仍是垃圾字节（8 字节写原语的固有限制），只有 Enforcing 下才会被解释。
   实测（2026-10-25 05:0x，KSU 已加载）：`setenforce 1` → ping 100% 丢包、
   `nc 223.5.5.5:443` rc=1、wlan0 TX 冻结 1,570,176；`setenforce 0` → **立即恢复**
   （ping 3.1 ms、nc 443 rc=0、DNS 34 ms），不必重启。因此：任何把 enforcing 置 1
   的动作（手动 setenforce、别的 root 工具、KSU 升级后换回未打补丁的 ko）都会让断网复发。
2. **SELinux 全局 Permissive**：安全姿态与早期流程一致（本就是提权的前提），
   但意味着系统不做 SELinux 检查；个别 MIUI 组件在 Permissive 下会有
   `selinux_android_setcontext` 失败导致的崩溃（历史观测到 `com.miui.powerkeeper`
   的 zygote FatalError，早于 KSU 加载）；`getenforce` / 管理器会显示「宽容模式」。
3. **补丁与 LKM 版本强绑定**：4 字节锚在官方 `lkm-aarch64-android17-6.18` 的
   `.init.text+0x144`。换 LKM 版本/换机型内核时必须重新定位；载荷有字节校验，
   不匹配时打印 `KSU_CODEPATCH ... -2` 并继续加载——此时 KSU 会自己把 enforcing
   置 1，断网会复现。
4. **语义变化**：KSU 不再“加载后恢复 Enforcing”（这是官方行为）。若某次 stage0
   失败（enforcing 仍为 1）却仍加载 KSU，补丁会把 SELinux 置 0（关掉）。
5. 这是**修改过的第三方模块**（4 字节 + 静态重链接），未触发签名校验
   （`CONFIG_MODULE_SIG_FORCE` 未设）；KSU 只校验管理器 APK 的 sha256（通过）。

建议：不要手动 `setenforce 1`；若网络突然断了，先 `setenforce 0` 即刻恢复，
再排查是谁把 enforcing 置回去了。要同时拿到「Enforcing + 网络」，需要
**不污染 policycap 的写原语**（1 字节写，或能写任意 8 字节且不需要指针有效性的构造），
这是后续研究方向。

## v1.6 新增（固件门禁 / 内置管理器 / 高亮日志）

### 固件门禁
启动即比对 `Build.FINGERPRINT` 与 `System.getProperty("os.version")`：

- 期望指纹：`Xiaomi/yingtian/yingtian:17/CP2A.260605.016/OS4.0.16.0.XBMCNXM:user/release-keys`
- 期望内核：`6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`

不匹配时：日志红字「✘ 固件门禁未通过」，按钮禁用，不会再执行载荷。
匹配时：日志绿字「✔ 固件门禁通过（yingtian / OS4.0.16.0.XBMCNXM）」。

### 内置 KernelSU 管理器
`assets/ksu-manager.apk`（KernelSU 管理器 v3.3.1 / versionCode 32680，13,637,206 B）。
App 通过 `pm path me.weishu.kernelsu` 探测；缺失时按钮变为「安装 KERNELSU 管理器」，
点击后用 `PackageInstaller` 会话把内置 APK 写给系统安装器（无需 root）。系统会依次弹：
「是否允许 O3 Root 安装应用」→（MIUI）「网络异常…继续安装」→「风险提示…继续」。全部点允许/继续即可。
装完回 App，日志出现 `✔ installer status=0 … pkg=me.weishu.kernelsu`，按钮恢复「Continue to UID 0 / 打开 KernelSU」。

### 高亮日志（给人看的重点）
LogBox 现在按语义上色加符号，普通流水行保持灰蓝：

| 记号 | 含义 | 例子 |
|---|---|---|
| ✔ 绿粗 | 关键成功 | `✔ 固件门禁通过`、`✔ 全部完成，正在打开 KernelSU…`、`✔ installer status=0` |
| ★ 绿粗 | 提权/内核层里程碑 | `★ UIDNOW uid=0 euid=0 gid=0`、`★ KSU_MODULE LIVE` |
| ▶ 青粗 | 阶段开始 | `▶ === running payload as Shizuku shell ===` |
| ● 青 | 连接/能力 | `● binder uid=2000 ctx=u:r:shell:s0` |
| ⚠ 琥珀 | 需要注意的失败/风险 | `⚠ 未获得 UID 0'、`⚠ 诊断: …`、`⚠ KSU_MODULE ABSENT` |
| ✘ 红粗 | 硬失败 | `✘ 固件门禁未通过`、`✘ 未获得 UID 0` |
| · 灰 | 细节流水 | `· installer: session … wrote … bytes`、`probe -> …`、`top -> …` |

导出的日志文件（`/sdcard/Android/data/o3.st.root1/files/o3root-log-*.log`）保持纯文本无符号。

### 兼容原版 Shizuku（以及 ShizukuX 等分支）
本 App 只用官方 API：`Shizuku.pingBinder / checkSelfPermission / requestPermission /
addBinderReceivedListenerSticky / addBinderDeadListener / addRequestPermissionResultListener / newProcess`，
并使用官方 `ShizukuProvider` + `moe.shizuku.manager.permission.API_V23` 声明（manifest 已含）。
因此 **原版 Shizuku 与 ShizukuX 均可**：

- server 在 **adb / 无线调试模式**：`uid 2000 / u:r:shell:s0`（本机实测路径）；
- server 在 **root 模式**：`uid 0`（原版 Shizuku 支持 su 启动），载荷 socket 的 `SO_PEERCRED`
  白名单已同时允许 `0` 与 `2000`，两种 server 都能驱动本 App。
- 若用原版 Shizuku 且无 root，仍需先用无线调试启动（Android 11+ 可在设备上完成）。

尚未在本机做「替换为原版 Shizuku」的实测（卸载 ShizukuX 会丢失当前 shell server），
属代码级兼容性结论；如需要可以再安排一次冷启 + 替换验证。

## 多设备支持（yingtian ✅ / lhasa ✅）

结论：Xiaomi 18 Fold（`lhasa`，`OS4.0.26.0.XPNCNXM`）的 OTA 里，**内核镜像与 yingtian `OS4.0.16.0.XBMCNXM` 的完全一致**：

```
$ sha256sum ota_lhasa/kernel ota_extract/kernel
cde87cd70206f21db4359aa1148f5f68e693ffefb11108dffde99c07c8c5ad88  ota_lhasa/kernel
cde87cd70206f21db4359aa1148f5f68e693ffefb11108dffde99c07c8c5ad88  ota_extract/kernel
$ cmp ota_lhasa/kernel ota_extract/kernel && echo identical      # identical
Linux version 6.18.21-android17-5-g805aa6adea19-abogki565292102-4k   （两边同一字符串，42,940,928 B）
```

（`boot.img` 整体 sha256 不同：`c8b09a86…` vs `1e3a1111…`，差异只在 header/ramdisk/cmdline；kernel 段逐字节相同。）

对比脚本产物：`work/lhasa_kernel_check.txt`；解包目录：`ota_lhasa/`（boot/init_boot/vendor_boot/dtbo/vbmeta，各分区 hash 均与 OTA manifest 一致）、`ota_lhasa_sel/`（system/vendor，用于策略核对）。

因此 **不需要第二套 profile**：`task_struct`/`cred`/`k_itimer` 偏移、`selinux_state`/`init_user_ns` 静态地址、页表常量、225 个 KSU 符号全部通用，同一份载荷（`libo3p.so`）可直接在 lhasa 上跑。

### 门禁规则（v1.6）
1. **内核字符串**必须等于 `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k` —— 这是唯一硬条件，不符直接禁用（红字说明当前内核）。
2. 设备族必须是 `yingtian` 或 `lhasa`（按 `Build.FINGERPRINT` 里的 `/<device>/` 判断），其它设备一律拒绝。
3. build 串与已验证值不同时（例如 lhasa 的小版本、yingtian 的安全补丁版），只要内核一致就放行，并在日志里追加一行提示，便于事后核对。

lhasa OTA 元数据（`META-INF/com/android/metadata`）：`pre-device=lhasa`、`post-build=Xiaomi/lhasa/lhasa:17/CP2A.260605.016/17OS4.0.261012.100316399.XRPECN.S:user/release-keys`、SDK 37、安全补丁 2026-09-01、显示版本 `OS4.0.26.0.XPNCNXM`。

### lhasa 上仍需实测确认的差异（内核以外）
- **SELinux 策略**：已尝试从 lhasa OTA 静态核对，但要注意层次：OTA zip 里是 `payload.bin`（update_engine 格式，不是文件系统镜像）；用 `tools/extract_payload.py` 解出的才是原始分区镜像 `system.img`/`vendor.img`（**EROFS**，magic `e2e1f5e0`，system 943 MB / vendor 795 MB）。策略文件（`*/etc/selinux/*.cil`）在镜像内为压缩存放，主机没有 erofs 解包工具，**静态结论拿不到**；对**已解出的 `.img`** 可用 `erofs-utils` 的 `fsck.erofs --extract` / `dump.erofs`，或在支持 erofs 的 Linux 内核上 loop 挂载后直接读——这些工具不能直接作用于 `payload.bin`。
`fsck.erofs --extract` / `dump.erofs`，或在支持 erofs 的 Linux 内核上 loop
挂载后直接读）。因此：首次在 lhasa 上跑时若 shell 域写 tracefs/`kprobe_events` 被拒，载荷会在 probe 阶段报错（日志里能看到 `kprobe_events` / `KPROBE` 字样的失败），届时导出日志即可定位。
- **Shizuku 可用性**：lhasa 上能否同样用「无线调试 + 配对」冷启 shell 模式 server。
- **安装限制**：MIUI 的 `INSTALL_FAILED_USER_RESTRICTED` 行为、内置管理器安装时的三个系统弹窗。
- 这些都不影响门禁判断，但首次在 lhasa 上跑时建议把「导出日志」保存，便于按日志定位。

### 新增设备画像的流水线（若以后出现不同内核的机型）
1. `tools/extract_lhasa.py` 解 `payload.bin` → `boot.img`（校验 `new_partition_info.hash`）；
2. `tools/unpack_boot.py` 拆 header → `kernel`；先比 sha256，**相同则直接复用现有 profile**；
3. 不同时：`vmlinux-to-elf` → `vmlinux.elf`；BTF 取偏移；反汇编定位 `selinux_state`/`init_user_ns`/timer 符号；
4. `work/ksu/mksymtab.py` 生成该 vmlinux 的 225 项 KSU 符号表；
5. 载荷把编译期常量改为按 profileId 选表；APK 每套画像增加约 0.4–1 MB。

## 工作方式

1. App 通过 Shizuku 的 `newProcess` 在 **shell 域（uid 2000, u:r:shell:s0）** 起一个子进程；
2. 子进程执行 APK 内置载荷（`lib/arm64-v8a/libo3p.so`，安装后落在 nativeLibraryDir；
   运行时先 `cp` 到 `/data/local/tmp/o3p.bin` 再执行）：
   - `t34` 链：POSIX timer UAF → 释放的 order-1 slab 页被 AIO ring 页回收 → fake RB tree 删除
     → 写 `selinux_state` 首 8 字节（`enforcing=0`、`initialized` 保留；`policycap[6]`
     会被写成地址高位字节，属该写原语的固有限制）→ SELinux Permissive；
   - 同一原语再写 `task->cred` 指向受控页里的 fake cred（uid/gid=0、caps 全开；
     `cred->security` 沿用原 cred 的 SELinux blob，即仍为 shell 域）→ uid 0 + 全能力；
   - **不要**尝试伪造 `cred_security_struct` 的 `sid=1`（kernel 域）：实测会触发内核 panic；
   - 起 rootd（v1.5：**抽象 Unix socket `@o3p_rootd`**，`SO_PEERCRED` 只放行 uid 0 / 2000；
     无任何命令通道文件，客户端用 `o3p.bin --shell "cmd"` 连接）；
3. KSU：载荷读自身尾部 24 B trailer（`O3KSKO01` + off/len）取出内置的官方
   `lkm-aarch64-android17-6.18_kernelsu.ko`（375,328 B），按**运行时** slide 把 225 个
   UNDEF 符号改写成 `SHN_ABS + 符号地址`（`ksu_symtab.h` 内嵌符号表），写
   `/data/local/tmp/o3p_ksu.ko`，然后 **fork + exec `insmod`**（关键：exec 会重建
   cred，避免 `commit_creds()` 因 `task->cred != task->real_cred` 触发 BUG）；
4. **状态探测（v1.2）**：App 启动/回前台时通过 Shizuku 跑
   `grep -c kernelsu /proc/modules; getenforce`，若 KSU 已在 Live 则按钮变为
   「打开 KernelSU」、点击直接开管理器（不再误显示「重试」）；
5. **打开管理器（v1.2）**：主路径用 Shizuku 执行
   `am start -W -n me.weishu.kernelsu/.ui.MainActivity`，读其输出判断
   `Status: ok`，再 `dumpsys activity activities | grep -m1 topResumedActivity` 自检；
   intent 只在 am 失败时作后备（Android 11+ 包可见性会让 `getLaunchIntentForPackage`
   返回 null，且后台 `startActivity` 会被静默拦截且不抛异常）。

## 安全加固（v1.5）

1. **rootd 命令通道重做**：旧版用 `/data/local/tmp/o3p_rootd.{cmd,seq,done,out}` 文件
   （0666），设备上任意 App 都能写命令拿到 root。v1.5 改为**抽象命名空间 Unix socket**
   `@o3p_rootd`（`sun_path[0]=0`，不落文件系统、不需目录写权限），服务端用
   `getsockopt(SO_PEERCRED)` 取对端 uid，**只接受 uid 0 或 2000**，其余直接
   `ERR denied uid=…`；执行结果连同 `__EXIT=<code>` 一起回传。客户端：
   `./o3p.bin --shell "id -u; getenforce"`。
2. **去 debuggable**：release 构建，`run-as` 不再可用（体积不变，攻击面更小）。
3. **日志导出**：LogBox 右侧「导出日志」按钮把完整日志写到
   `/sdcard/Android/data/o3.st.root1/files/o3root-log-<毫秒时间戳>.log`，
   `adb pull` 或文件管理器即可取出（替代原来的 `run-as … cat files/run.log`）。
4. 服务端自身日志 `o3p_rootd.log` 改为 `0600`（旧版 0666）。

## 失败诊断（v1.5）

载荷结束时 App 会对**本次运行的最后一段日志**做判定并打印 `诊断: …`：

| 触发条件 | 提示 |
|---|---|
| `boot_id` 与运行时不同 | 运行期间设备重启过——请重新运行 |
| 最后一个 `VERDICT NO_RECLAIM` / `PAGERECLAIM_MISS` | 页回收失败——清理后台应用再重试，连续失败建议重启 |
| 最后一个 `VERDICT NO_UAF_TRIGGER` | 竞态未命中——再点一次通常就能中 |
| 最后一个 `VERDICT NO_STALE_LINK` | 内核对象状态异常——重试 |
| `BAD_OFFSET` | 偏移不匹配——固件/内核可能不受支持 |
| `KSU_SKIP no_slide` / `KSU_BLOB MISSING` | 未取到 slide 或模块数据缺失——重试 |
| `KSU_PATCH … missing≠0` | KSU 符号缺失——内核与内置模块版本不匹配 |
| `KSU_INSMOD_OUT` 非 `rc=0` | KSU 模块加载失败——检查内核版本或已有 root 方案 |

成功路径（`rootOk && ksuOk`）不打印诊断行。

## 实测证据（2026-10-25，冷启、无 root）

设备：Xiaomi Pad 9 Pro Max（yingtian / M367FC），HyperOS 4.0.16.0.XBMCNXM，
内核 6.18.21-android17-5-g805aa6adea19-abogki565292102-4k。

**v1.2 冷启复测（boot_id `89eb3d74-fa09-443a-9108-980fb2968cea`）**：冷启后
`uid=2000(shell)`、`u:r:shell:s0`、`getenforce`=Enforcing、无 su、无 kernelsu；
Shizuku 自动以 shell 模式运行（`shizuku_plus_server` pid 8622，uid 2000）。
启动 App → 点 `CONTINUE TO UID 0` 一次：

```
UIDNOW uid=0 euid=0 gid=0
FULLROOT armed=1 fakekva=0xffffff8306927000
== cleanup
done
payload exit=0
全部完成，正在打开 KernelSU…
am start -> Starting: Intent { cmp=me.weishu.kernelsu/.ui.MainActivity } Status: ok LaunchState: COLD …
top -> topResumedActivity=ActivityRecord{… u0 me.weishu.kernelsu/.ui.MainActivity}
```

复测前后 `boot_id` 未变、`/proc/modules` = `kernelsu 192512 1 - Live`、
界面显示「工作中 [越狱模式] 版本 32680-5、管理器 v3.3.1 (32680-5)、SELinux 强制执行、
模块 3、超级用户 13」（截图 `work/n6.png`）。

**v1.5 冷启（boot_id `e5a9bcfe-6f1c-458c-84c6-12cdeb3e0a44`）**：socket 版 rootd 首次上机即通：

```
KSU_BLOB len=375328 slide=0x1543000000
KSU_PATCH symtabs=1 found=225 missing=0
KSU_EXEC done=1 status=0x0
KSU_INSMOD_OUT:[rc=0]
KSU_MODULE LIVE
ROOTD fork=13197 self=/data/local/tmp/o3p.bin
ROOTD sock ready
ROOTD_OUT:[uid=0(root) gid=0(root) … context=u:r:shell:s0
---
Enforcing

__EXIT=0]
payload exit=0
全部完成，正在打开 KernelSU…
am start -> … Status: ok LaunchState: COLD
top -> … me.weishu.kernelsu/.ui.MainActivity
```

同 boot 第二次冷启（boot_id `71bb6899-a965-46e7-b64e-b459383c5e75`）再点一次也成功
（`ROOTD sock ready`、`KSU_MODULE LIVE`、`payload exit=0`），说明 socket 通道稳定。
另一次同 boot 运行 6 次 attempt 全部 `NO_RECLAIM` 失败，App 正确给出
`未获得 UID 0，可再试一次`（该次为 exploit 竞态失败，非通道问题）。

**v1.1 冷启（boot_id `ac4c5ae-b17e-48de-883b-1790db7d59d9`）** 同样一次点按成功：
`KSU_BLOB len=375328 slide=0x2b96400000`、`KSU_PATCH found=225 missing=0`、
`KSU_INSMOD_OUT rc=0`、`KSU_MODULE LIVE`、`ROOTD fork=13490`、`payload exit=0`。

（KSU 加载后会把 SELinux 拉回 Enforcing，这是 KSU 的正常行为。）

## 主题（跟随系统深色模式）

界面是程序化绘制的，没有用 XML 布局；两套调色板由 `Main.applyPalette()` 从
`Configuration.uiMode & UI_MODE_NIGHT_MASK` 选择：

| | 深色 | 浅色 |
|---|---|---|
| 背景 | `#0B0E13` | `#F5F7FA` |
| 卡片 | `#151A22` / 边 `#232B36` | `#FFFFFF` / 边 `#DCE3EC` |
| 主文字 | `#E8EEF6` | `#11161D` |
| 次文字 | `#8894A3` | `#5B6675` |
| 强调色 | `#34D399` | `#0E9F6E` |
| 日志区 | `#10151C` / 字 `#B9C6D4` | `#F1F4F9` / 字 `#2A3440` |
| 按钮文字 | `#07130E`（深色字） | `#FFFFFF` |

- 状态栏/导航栏颜色同步背景色，浅色模式用 `LIGHT_STATUS_BAR | LIGHT_NAVIGATION_BAR` 让图标变深。
- `AndroidManifest.xml` 的 `configChanges` 里声明了 `uiMode`，所以**系统切换深浅色时不会重建
  Activity**——正在跑的载荷线程不受影响，`onConfigurationChanged()` 里只做 `applyPalette() + restyle()`
  并往日志追加一行 `theme -> dark/light (system uiMode)`。
- 想手工验证：`adb shell cmd uimode night yes` / `no`。

## 图标

自绘的深色 squircle + 翠绿渐变「O3」单字标，同时提供：
`mipmap-*/ic_launcher.png`（传统图标，48–192 px）、`mipmap-anydpi-v26/ic_launcher.xml`
（自适应图标：`drawable/ic_bg.xml` 深色渐变背景 + `ic_fg.png` 前景 + Android 13 单色层 `ic_mono.png`）。

生成脚本 `app5/mk_icon.py`（Pillow，4× 超采样）：

```bash
python3 mk_icon.py
```

`build.sh` 里用 `aapt2 compile --dir res -o build/res.zip` 编资源，并在
`aapt2 link` 时把 `build/res.zip` 一起链接。

## 构建（可复现）

```bash
cd app && bash build.sh
```

内部步骤：`javac --release 17 -classpath "build/cp/*"`（android.jar + Shizuku
api/provider/aidl/shared）→ `d8 --release --min-api 31` → `aapt2 link` →
python zipfile 加 `classes.dex` 与 `lib/arm64-v8a/libo3p.so` → `zipalign -f -p 4` →
`apksigner sign`（keystore `<your-keystore>.p12`，alias `<your-alias>`，
密码 `<keystore-password>`）。

载荷单独构建：`work/o3p.c` 由 `work/mk_o3p.py` 从 `work/t34.c` 生成（内嵌
`work/ksu/ksu_symtab.h`），用 NDK r28c 编译后由 `work/mk_payload.py` 拼上 ko + trailer。

## 安全姿态与 Seccomp 现状（2026-10-25 实测）

### SELinux 处于 Permissive 的后果

1. **MAC 失效**：`avc: denied` 只记录不拦截，域隔离（App ↔ system ↔ 其它 App）不再成立，
   只剩 DAC（uid/gid）与 capability 约束。
2. **本地 App 攻击面显著扩大**：`/sys/kernel/tracing/kprobe_events` 为 0777；
   Enforcing 下 App 域被拒（实测 EACCES），Permissive 下放行。但 App 仍受 seccomp
   过滤（实测 `io_uring_setup` 直接 SIGSYS），本链目前依赖 shell 域能力；
   App 域能否独立复现未验证 —— 应表述为「本地 App 可利用面扩大」，而非「必然可提权」。
3. **稳定性**：个别组件依赖 `selinux_android_setcontext` 的返回；Permissive 下观测到
   `com.miui.powerkeeper` 的 zygote FatalError（早于 KSU 加载）。
4. **可检测**：`getenforce`、KSU 管理器显示「宽容模式」；银行/风控/反作弊类应用可能拒绝运行。
5. **与 policycap 污染耦合**：Permissive 掩盖污染；任何 `setenforce 1` 都会让网络失效
   （v1.8 已加守护 + 一键恢复）。

### Seccomp 现状（`/proc/<pid>/status` 复测，2026-10-25 05:47 冷启 + 跑完 v1.8 后）

| 进程 | Seccomp | filters |
|---|---|---|
| init (1) | 0 | 0 |
| system_server (2361) | 2 | 1 |
| adbd (7675) | 0 | 0 |
| zygote64 (1681) | 0 | 0 |
| systemui (3214；重启后 14212) | 2 | 1 |
| shizuku_plus_server (13284，shell 模式) | 0 | 0 |
| adb shell 自身 | 0 | 0 |
| o3p.bin --rootd (12243，uid 0) | 0 | 0 |
| com.miui.home（普通 App） | 2 | 1 |
| o3.st.root1（本 App） | 2 | 1 |

即：**init / adbd / zygote64 / adb shell / Shizuku 子进程 / rootd 没有 seccomp 过滤；
system_server、systemui 与普通 App 一样是 2/1**。本 boot 复核：systemui 被
`kill -9` 后由 zygote 重生（pid 14212），新进程仍是 2/1 ⇒ 重启不会丢过滤。
注：更早一次观测（boot `c83360b8`，当时 system_server pid 30438 已是重启实例）曾读到
system_server/systemui 0/0，该结果在干净冷启上不可复现，按上表为准。

`/proc/sys/kernel/seccomp/actions_avail` = `kill_process kill_thread trap errno user_notif trace log allow`；
`/proc/sys/kernel/io_uring_disabled` = 0。shell 域实测 `io_uring_setup(1)` → `fd=3 sq=1 cq=2
features=0x3ffff`（成功）；App 域历史实测 `io_uring_setup` 被 seccomp 直接 SIGSYS 杀进程（probe2）。

影响：

1. **攻击面（限定后）**：真正没有 seccomp 的是 init/adbd/zygote 与 shell 域进程，
   不是 system_server/systemui；系统主进程的 syscall 白名单仍在，前一轮“系统进程无
   seccomp”的说法已按复测修正。
2. **对提权链**：本链从 shell/root 域运行，没有 seccomp 拦截（perf、kprobe、AIO 都可用）；
   App 域则被 syscall 白名单挡住（`io_uring_setup` 直接 SIGSYS）。若未来需要更稳的页回收源，
   可在 shell 域改用 io_uring ring 页（GFP_KERNEL、unmovable、可 mmap），
   绕开 AIO 的 `aio-max-nr`（≈510 个 8KB ring）限制。
3. **检测面**：安全/风控应用可读 `Seccomp` 字段；本流程不改任何进程的 seccomp
   （冷启与跑完后的数值一致）。
4. 结论：**SELinux Permissive + shell 域无 seccomp + 已加载 root/KSU** 时，设备安全边界
   只剩 DAC，且 shell 域内可用的内核原语最多；用完重启即可恢复（重启后 SELinux 回
   Enforcing、policycap 干净、网络正常，root/KSU 消失）。

## P0 改进试验（2026-10-25，io_uring / AF_PACKET / kernel SID）

目的：验证「用 io_uring ring 页替代页回收源」与「伪造 kernel SID 换取干净 Permissive」两项改进设想。

### io_uring（结论：不可用）

- 测试环境：boot `56fd7ab1`、Permissive、KSU Live、uid0（rootd）执行。
- `t43c`（nr=128 单 context）：`io_uring_setup` 成功（fd=28006、sq=128、cq=256、array=4160、cqes=64），但对 `io_allocate_scq_urings`、`__alloc_pages_noprof`、`__alloc_frozen_pages_noprof`、`__vmalloc_node_range_noprof`、`vm_area_alloc_pages` 的探针在 SETUP 阶段全部 0 命中。
- `t43d`（100×nr=128）：`kmem:mm_page_alloc` 过滤 `order >= 1`，ALLOC 阶段 0 事件。
- `t44`（4000 timer 泄漏 + 1500×nr=64 ring + MAGIC 填充）：`params sq=64 cq=128 array=2112 cqes=64`、`sq_sz=2368`、`cq_sz=2112`，`VERDICT URING_RECLAIM_MISS hits=0 checked=4000 reused_kernel=0`。
- 结论：多页 io_uring ring 走 order-0 / vmalloc，无法回收 order-1 的 `posix_timers_cache` slab。现有载荷用 7872 字节 skb（7872+320≈8192 → kmalloc-8k，order-1）本来就是正确的 order。

### AF_PACKET TX ring 8KB（结论：回收能力最强，但需 CAP_NET_RAW）

- `t45`（4000 timer 泄漏 + 600 个 `PACKET_TX_RING`、`tp_block_size=8192`、mmap 写 MAGIC）：600 个 socket 全部创建、mmap、可写（`wr_fail=0`）；`kmem:mm_page_alloc` 过滤 order>=1 在 spray 阶段 645 次；`VERDICT PKT_RECLAIM_HIT hits=3738 checked=4000`（命中率 93.5%）。
- `t46`（shell uid2000）：`CapEff: 0000000000000000`，`socket(AF_PACKET)` → EPERM ⇒ shell/App 域没有 CAP_NET_RAW。
- 结论：AF_PACKET 8KB ring 是已验证的最强 order-1 可写页回收原语，但只能从 root 阶段使用；对「pre-root 阶段的页回收」无收益。

### kernel SID 路线（结论：与 KSU hook 冲突，不可行）

- 在 root 下先置 `panic_on_oops=0`、`panic_on_warn=0`，再运行 v20（`O3P_NOSEL=1 O3P_SIDFORGE=1`，伪造 `cred_security_struct{osid=1,sid=1}`）。
- 捕获到 oops（系统未重启，boot_id 未变）：
  `Internal error: Oops: 0000000096000005 [#1] SMP`
  `CPU: 0 UID: 0 PID: 10694 Comm: yt2-parent`
  `pc : hook_file_open.cold+0x4/0x1b0 (P)`
  `security_file_open → vfs_open → path_openat → do_filp_open → do_sys_openat2 → __arm64_sys_openat`
- 同时 avc 显示该进程 `scontext=u:object_r:unlabeled:s0` ⇒ `sid=1` 在本策略里映射为 unlabeled，并非 kernel 域。
- 结论：伪造 cred blob 既拿不到 kernel SID，又会让 KernelSU 的 `hook_file_open` 读崩；此路线在 KSU 已加载时不成立。

### 结论

- P0 两项均未带来可落地的改进；主链保持 v1.8 现状。
- 保留价值：`t45` 的 AF_PACKET 回收原语可归档，供未来「root 后第二次内核写入」类需求复用。

## 已知限制 / 观测

- **仅当前 boot 有效**：重启即失效，需重新点一次（App 可重复使用）。
- 需要 Shizuku 已以 shell 模式运行；纯 adb 模式或未安装 Shizuku 时按钮会提示。
- 依赖精确固件（OS4.0.16 内核布局）；不同内核需重算偏移与符号表。
- 竞态有失败概率（实测多数首次成功，偶尔需点第二次；App 自带 6 次 attempt）。
- MIUI 对 ADB 安装有限制；如 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`，
  可在文件管理器里手动安装，或用 root 通道 `pm install -r -d`。
- rootd 已无文件通道；如需给 root 服务加更多能力，扩展 socket 协议即可（注意保持 peercred 校验）。
- `/system/bin/su` 的出现时机：KSU 加载后、首次有 root 请求（例如 `adb shell` 被 KSU
  授权为 `u:r:ksu:s0`）时才由 KSU 装上；本次实测 05:04 出现（4,990,848 B、`root:root 755`），
  同时 `adb shell` 变为 `uid=0 … context=u:r:ksu:s0`。
- **重启/panic 观测**：02:27–02:28 曾出现过一次「连跳两次开机」（dropbox 里
  `SYSTEM_BOOT` 02:27:05 与 02:28:17），但 `/sys/fs/pstore` 为空、无
  `SYSTEM_LAST_KMSG`、`ro.boot.bootreason`=AP_S_COLDBOOT，未取到 panic 证据；
  02:36 的冷启全链路复测正常（boot_id 未变，payload `== cleanup / done`）。
  若后续再遇重启，可用 rootd（uid0，Permissive 时）读 `/sys/fs/pstore/*`
  或给载荷加 pstore 导出阶段来抓 trace。

## 2026-10-25 晚：运行时加载器（v26，dev）

- 载荷不再依赖内嵌 vmlinux 符号表：默认经 `/proc/kallsyms` 解析 LKM 的未定义
  符号（临时把 `kptr_restrict` 写 0，加载前恢复原值）；`O3P_RELINK_ONLY=1` 时
  回退到 248 符号并集 + KASLR slide。
- `setenforce` 补丁改为动态定位：解析 `.rela.init.text` 中 `setenforce` 的重
  定位，改 `r_offset-4`（tiann/Next `0x144`、SukiSU `0x178`）；解析失败回退
  唯一扫描。
- 三种 LKM 冷启验证：tiann v3.3.1（found=225）、KernelSU-Next v3.4.2（227）、
  SukiSU v4.2.1-r2（245）；均 `insmod` rc=0、`Live`、Permissive、Wi‑Fi 正常。
  relink 回退用 tiann 复验。
- 参考值：payload 67,496 B；配官方 LKM 的 `libo3p.so` 442,848 B，
  sha256 `372518ec…98bb`。

## v1.9（2026-10-25 17:24）：运行时加载器 + 三 fork LKM 支持

- APK：`release/o3root2-v1.9.apk`，13,977,104 B，
  sha256 `99c89628567c04dd59c04a57261cad93be809acaa1914befa61c0d4d9fad885e`
  （versionCode 10 / versionName 1.9；内置 payload `libo3p.so` 442,848 B，
  sha256 `372518ec…98bb`）。
- 载荷默认运行时经 `/proc/kallsyms` 解析 KernelSU 符号（临时 `kptr_restrict=0`，
  加载前恢复）；`O3P_RELINK_ONLY=1` 回退 248 符号并集 + slide；`setenforce`
  补丁点由 `.rela.init.text` 动态定位。
- 冷启动实测（boot `a8a188b1-9c68-4932-afab-d8f19ee483e6`，release APK）：
  `★ UIDNOW uid=0`、`KSU_CODEPATCH mov_w0_zero=1`、`KSU_PATCH found=225
  missing=0`、`KSU_INSMOD_OUT:[rc=0]`、`★ KSU_MODULE LIVE`、管理器前台
  「工作中 [越狱模式]」；boot_id 未变、Permissive、ping 4.6 ms、nc443=0。
- 三 fork LKM 冷启验证：tiann v3.3.1（found=225，offset 0x144）、KernelSU-Next
  v3.4.2（227，0x144）、SukiSU v4.2.1-r2（245，0x178）；relink 回退用 tiann 复验。

## v3.0 (dev log, 2026-10-25): multi-LKM blob + adaptive manager selection + two-reap payload

- Artifacts: payload `payload/o3p.c` 56,631 B -> `build/o3p.bin` 73,768 B;
  `build/libo3p.so` 1,492,484 B
  (`sha256 e64217c8fac2950763869881efdd15ff9fb76f1423a3388185bca7e72499aac3`);
  APK `app/o3root2.apk` 14,218,768 B
  (`sha256 c80f499a6b06ddf92241aa8cb0aa8874767eccdd960e631b28dcff98e7c141dd`).
- Blob v2 layout: payload + KernelSU v3.3.1 (375,328) + KernelSU-Next v3.4.2
  (473,552) + SukiSU v4.2.1-r2 (569,752) + 24-byte tag table + `O3KSKM02`/count;
  `--legacy` keeps the single-LKM `O3KSKO01` layout readable.
- Device evidence (all cold boots, SELinux ended Permissive and Wi-Fi healthy):
  - SukiSU manager only: `KSU_FORK chosen=sukisu tag=3 src=env`,
    `KSU_BLOB v2 tag=3 len=569752 matched=1`, `KALLSYMS_PATCH found=245`,
    `KSU_CODEPATCH mov_w0_zero=1 off=0x3e178`, `KSU_MODULE LIVE`,
    `/data/local/tmp/o3p_ksu.ko` 569,752 B, foreground
    `com.sukisu.ultra/.ui.clqyrckkrmoActivity`.
  - tiann manager only: `chosen=tiann tag=1 src=scan`, len 375,328,
    `found=225`, patch `0x22144`, `LIVE`, foreground
    `me.weishu.kernelsu/.ui.MainActivity`.
  - KernelSU-Next manager only: `chosen=next tag=2 src=env`, len 473,552,
    `found=227`, patch `0x37144`, `LIVE`, foreground
    `com.rifsxd.ksunext/.ui.MainActivity`.
- The app probes the manager in `StateProbe` and passes `O3P_KSU_FORK`; it opens
  the manager via `cmd package resolve-activity --brief` followed by
  `am start -W -n`, which works for all three manager packages.


## v2.1 — 权限感知探测（2026-10-25）

lhasa（Xiaomi 18 Fold）反馈：全新设备装完内置管理器后按钮仍停在「安装 KERNELSU 管理器」，
日志反复 `probe -> ERR java.lang.reflect.InvocationTargetException`。

根因：新设备未授予 Shizuku 权限时 `StateProbe` 的 `newProcess` 抛异常；`probeApply`
把 `ERR ...` 当正常输出解析（`mgrOk=false`），`installResult` 成功后又立即探测一次，
失败的探测把 `mgrOk` 清回 false，形成「装完还让装」的死锁。

v2.1 修复：`StateProbe` 先 `Shizuku.checkSelfPermission()`（未授权 → `probeNoPerm`）；
管理器识别改本地 `PackageManager`；授权后由 `PermProbe` 只探测一次，KSU 已加载则只更新按钮、
未加载才自动跑载荷；载荷复制到唯一文件名 `/data/local/tmp/o3p_<millis>.bin`（避免旧 rootd
导致的 `cp: Text file busy`）。versionCode 12 / versionName 2.1。

真机（yingtian，冷启动 boot 8d747c66）：撤销授权 → 首启只显示「需要授权」+ Continue；
点按授权后自动跑载荷：`KSU_CODEPATCH mov_w0_zero=1 off=0x22144`、`rc=0`、`KSU_MODULE LIVE`、
`ROOTD self=…/o3p_1792926817016.bin`、`UIDNOW uid=0`、Permissive、管理器前台、ping 6.7 ms。
历史发布件 `release/o3root2-v2.1.apk`（14,218,768 B，sha256 `85c3516b…`；v3.0 起由 `release/o3root2-v3.0.apk` 取代）。

## v3 (2026-10-25 22:34, branch exp/v3-tracepoint → main)

Two-reap v3: stage 0 is an unaligned 8-byte zero at `selinux_state-7` (clears
`enforcing`, keeps `initialized`/`policycap`), stage 1 is the `task_cred`
swap. KernelSU loads through the runtime kallsyms loader with **no init
patch**; its own `setenforce(1)` now leaves the network healthy.

Cold boot (yingtian, boot `42860b0e-b370-4594-b14d-da2d521fa62b`, no KSU):

```
KSU_FORK chosen=tiann tag=1 src=env
KSU_BLOB v2 tag=1 len=375328 count=3 matched=1
KALLSYMS_PATCH found=225 missing=0
KSU_CODEPATCH skip
KSU_WROTE /data/local/tmp/o3p_ksu.ko 375328
KSU_EXEC done=1 status=0x0
KSU_INSMOD_OUT:[rc=0]
KSU_MODULE LIVE
ROOTD sock ready
UIDNOW uid=0
payload exit=0
am start -> me.weishu.kernelsu/.ui.MainActivity Status: ok
top -> me.weishu.kernelsu/.ui.MainActivity
```

Final state: Enforcing, `kernelsu` Live, `ping 10.30.0.1` 22 ms,
`nc 223.5.5.5:443` rc=0, manager shows `工作中 [越狱模式]`.

Single-reap, three-write attempt (v3a/v3b/v3c) panicked three times right
after the first `D3ADDR`; it stays behind `O3P_D3V3` for analysis only.
