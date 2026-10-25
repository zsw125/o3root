# Security notes and threat model

> Chinese translation: [`SECURITY.zh-CN.md`](SECURITY.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

**Scope:** v3.0 (2026-10-25); legacy v1.7–v2.1 behaviour is called out explicitly.

## Read this first

This repository contains a **working local privilege-escalation exploit** for a
specific, fully-patched-at-release Android device and firmware. It is published
for research, education and for owners of the affected device who want to
understand/repair it.

- Only run it on devices **you own** and are willing to wipe/reset.
- Do not use it on shared, corporate or third-party devices.
- The exploit is **not persistent**: a reboot removes everything it did.
- Nothing here writes to partitions, changes `vbmeta`, or unlocks the
  bootloader. The device's verified boot state is not modified.

## Threat model

| attacker starting point | capability |
|---|---|
| any installed app | cannot run the chain directly (App domain is seccomp-filtered and has no `CAP_NET_RAW`; tracefs is denied) |
| Shizuku **shell** server (`uid=2000`, `u:r:shell:s0`) | can run the chain end-to-end (this repo's APK does exactly that) |
| ADB shell (USB debugging) | same as above |
| root already present | full kernel/userland control; this repo is unnecessary |

The intended flow on a clean device: install Shizuku, start it in shell mode
(wireless-debugging / ADB), install this app, press Continue. The app runs the
payload inside a Shizuku-spawned shell process, which becomes root and loads
KernelSU.

## What the exploit changes at runtime

The payload modifies kernel memory only:

1. `selinux_state` (stage 0): an 8-byte unaligned zero at `selinux_state-7`
   clears only `enforcing`; `initialized` and `policycap[]` stay intact (the
   legacy `O3P_SELDIRTY=1` path still writes the old dirty value and dirties
   `policycap[]`).
2. `task_struct.cred` of the payload process (stage 1) → a fake cred page with
   uid 0, full capabilities and the original SELinux blob (still shell domain).
3. Loads a KernelSU LKM (relinked at runtime for the current KASLR slide) with
   **no init patch** by default; because `policycap` is clean, KernelSU's own
   `setenforce(1)` does not break networking. `O3P_CODEPATCH=1` restores the
   legacy patch (which then needs the old dirty-write caveats below).

Nothing is written to disk except:
- `/data/local/tmp/o3p.bin`, `/data/local/tmp/o3p_ksu.ko` (the payload and the
  patched module, mode 0755),
- `/data/local/tmp/o3p_proof.txt`, `/data/local/tmp/o3p_rootd.log` (mode 0600),
- optional app logs under the app's external files dir.

## Known side effects

### Legacy policycap corruption (`O3P_SELDIRTY=1` only) → network loss

The v3 default does not touch `policycap[]`. Only the legacy dirty write
(`O3P_SELDIRTY=1`) cannot preserve it, and then re-enabling SELinux enforcement
(any `setenforce 1`) makes the Wi-Fi transmit path stall: `wlan0` TX freezes,
RX continues, all traffic dies. Recovery:

- `setenforce 0` (from a context with `CAP_MAC_ADMIN`, e.g. KernelSU `su`), or
- reboot (clears the corruption; root/KSU are lost).

v1.8–v2.1 shipped a probe/warning and a one-click `su -c setenforce 0`; v3.0
removed them because the default no longer corrupts `policycap`.

### Global permissive SELinux (legacy paths only)

The v3 default ends in Enforcing with MAC on. Only `O3P_SELDIRTY=1` (or a manual
`setenforce 0`) leaves the device Permissive; then all SELinux MAC checks are
disabled:

- SELinux domain isolation is gone; a local app compromise is much easier to
  turn into root (tracefs/kprobe interfaces are 0777 and only SELinux stops
  them).
- Some system components behave differently (e.g. zygote/`powerkeeper`
  `selinux_android_setcontext` failures observed in logs).
- Detection: `getenforce`, KernelSU manager's "permissive" badge.

This is not a normal daily-driver posture. Reboot when done.

### Kernel taint / ABI mismatch

The KernelSU LKM was built by upstream for `android17-6.18`
(`6.18.32-4k-g47cc22554442-dirty`) but is loaded on `6.18.21-…`. The runtime
relink bypasses symbol/CRC namespace checks; the module is tainted (`(O)` in
`/proc/modules`). Treat this as a research setup, not a production root.

### Leftover files and labels

On normal runs, files created in `/data/local/tmp` get
`u:object_r:shell_data_file:s0` and are fine. Files created during the abandoned
"zero-write" experiment (`initialized=0`) stay `u:object_r:unlabeled:s0` and
cannot be read/deleted by shell while enforcing. With root in a non-enforcing
boot they can be repaired in bulk:

```sh
chcon -R u:object_r:shell_data_file:s0 /data/local/tmp
```

or deleted/recreated; the label is a persistent `security.selinux` xattr.

### Seccomp observation

Measured on the target (clean cold boot): `init`, `adbd`, `zygote64`, the
Shizuku server and rootd have **no seccomp filter**; `system_server`,
`systemui` and ordinary apps have 2/1. This repo does not change any process's
seccomp state — it is an Android-side design property, reported here only
because it affects exploitability.

## Recovery / cleanup checklist

1. `setenforce 0` if the network is broken (or reboot).
2. Remove test artifacts: `rm -f /data/local/tmp/o3p*` (root), and uninstall the
   APK and, if installed by the app, the KernelSU manager.
3. Reboot: SELinux returns to Enforcing, `selinux_state` is intact, no root.
4. If you granted KSU superuser to apps, `ksud`/manager allow-list lives in
   `/data/adb` and survives reboots — remove it with the manager or
   `rm -rf /data/adb/ksu /data/adb/modules` if you installed them.

## Reporting

Please do not file this as a vulnerability against Xiaomi's bounty program
without reading `docs/history/CVE-2026-98260_FEASIBILITY.md`; the underlying bugs
(`CVE-2026-98260`, and the earlier `CVE-2026-64560`/`CVE-2026-49881`) are
documented there with upstream fix references.
