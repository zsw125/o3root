# AGENTS.md — entry point for automated contributors

> 中文版：[`AGENTS.zh-CN.md`](AGENTS.zh-CN.md)。若中英文不一致，以英文版为准。

This repository delivers **O3 Root**: a Shizuku-driven Android app that
exploits `CVE-2026-98260` on Xiaomi `yingtian` (Pad 9 Pro Max) / `lhasa`
(Xiaomi 18 Fold, HyperOS 4.0.26) with kernel `6.18.21`, loads a KernelSU LKM,
and leaves a root daemon for the current boot. Read this file first, then
`README.md`, `docs/AGENT_HANDOFF.md`, `docs/ARCHITECTURE.md`.

## State

- Release **v3.2**, `release/o3root2-v3.2.apk`
  (`14,239,248 B`, `sha256 64bbc65420181babd69b826de7b86be99409963dd29253b72a2930d7a7ff6cfd`),
  plus the **test build** `release/beta/o3root2-v3.2-beta1.apk`
  (`14,243,344 B`, `sha256 4f8845fc3b03836e78dc237924da5f77eebd10e2b23bdaf6b36a6b1f26994a05`).
  v2.0 added the multi-LKM blob (KernelSU / KernelSU-Next / SukiSU) with
  manager-based selection; v2.1 makes the probe permission-aware (local manager
  detection, probe-then-payload after granting, unique payload path); v3.0
  replaces the single-reap write with two reaps — an unaligned enforcing-only
  zero at `selinux_state-7` plus the cred swap — and drops the KernelSU init
  patch (KSU's own `setenforce(1)` is safe because `policycap` stays clean).
  v3.1 adds a user-supplied LKM path ("选择 .ko"): the app checks the ELF and
  `.modinfo` (AArch64 `ET_REL`, `name=kernelsu`, same 6.18 series) before the
  run, warns and offers a fallback to the adaptive built-in LKM, and the payload
  re-validates and automatically falls back when the file is unreadable or the
  load fails. v3.1.1 persists that choice in the app files dir (`custom-ko.ko` +
  `custom-ko.meta`), restores it on launch, drops a stale file that no longer
  validates, and clears it on a long press of the module row. v3.2 makes the
  LogBox selectable and copyable (a "复制" link copies the whole log; the output
  view is selectable, and it only auto-scrolls while the reader is already at
  the bottom). The test build v3.2-beta1 additionally hides a root console
  behind a long press of the version row: three non-skippable confirmations
  (risk dialog, checkbox, then typing `I ACCEPT ROOT RISK`) plus a one-shot
  token guard the activity, and each command goes through the rootd socket
  (uid 0/2000 accepted) after the payload has started. The stable build does
  not compile the console classes at all. The three built-in LKMs are unchanged (all were the latest
  upstream on 2026-10-29 except SukiSU, which has v4.3.0 — see
  `python3 tools/check_lkm_updates.py`).
  v1.8 (relink-only) and v1.9 remain in the git history.
- Hardware-verified on `yingtian` / `OS4.0.16.0.XBMCNXM` (cold boot, end-to-end).
- `lhasa` / `OS4.0.26.0.XPNCNXM` ships the **bit-identical kernel**
  (`sha256 cde87cd7…`); not device-tested.
- Boot-only: a reboot removes root and KernelSU (legacy `O3P_SELDIRTY=1`
  paths also lose the permissive SELinux state; the v3 default ends in Enforcing).

## Fast commands

```bash
bash scripts/verify.sh            # static checks + optional builds (run before commit)
bash scripts/fetch-toolchain.sh   # android.jar, build-tools r34, Shizuku jars, pyelftools

# payload  (needs an NDK clang)
NDK_CC=/path/to/ndk/.../aarch64-linux-android35-clang bash payload/build-payload.sh
# reference result: 77,328 B payload; libo3p.so 1,496,044 B (three LKMs), sha256 e479e539…0a2b

# app (needs SDK + Shizuku jars + your keystore; see docs/BUILD.md)
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... bash app/build.sh

# beta test build (hidden root console: versionCode 16, versionName 3.2-beta1)
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... \
PAYLOAD=... BETA=1 bash app/build.sh
```

## Map

| path | what lives there |
|---|---|
| `payload/o3p.c` | the exploit payload (C): UAF trigger, page reclaim, RB write primitive, two stages, KASLR slide, KernelSU runtime loader (kallsyms first, relink fallback), legacy init patch (`O3P_CODEPATCH=1`), rootd |
| `payload/ksu/` | `ksu_symtab.h` (union of 248 KernelSU symbols from upstream forks), `mksymtab.py` (regen single-LKM table), `relink_ksu.py` (offline relinker reference) |
| `payload/lkm/` | official `android17-6.18` LKMs (GPL-3.0, unmodified): KernelSU v3.3.1, KernelSU-Next v3.4.2, SukiSU-Ultra v4.2.1-r2 |
| `app/src/o3/st/root1/` | Java: `Main` (UI/state machine), `PayloadRunner` (also stages a custom `.ko`), `CustomKo`+`KoResult` (ELF/.modinfo validation), `LongClearKo` (long-press clear), `StateProbe`, `RecoverRunner` (legacy guard; disabled in v3), `ManagerInstaller`, listeners; `BuildInfo` (version + console switch), `ClickCopyLog`; beta-only `ConsoleEntry` (reflective entry), `ConsoleGate` (three-step gate), `ConsoleActivity`+`ConsoleTask` (terminal) |
| `app/build.sh`, `payload/build-payload.sh`, `scripts/*` | build entry points |
| `docs/` | ARCHITECTURE (design + porting), SECURITY (risks/cleanup), BUILD, APP_O3_ROOT (dev log), OTA/kernel report, CVE feasibility, evidence PNGs |
| `tools/` | OTA extraction / boot image unpack / BTF+disasm checks / `check_lkm_updates.py` |
| `release/` | prebuilt APK + SHA256SUMS |

## Invariants — do not "clean up"

1. **Kernel profile constants are load-bearing.** `INIT_USER_NS_STATIC`,
   `SELINUX_STATE_STATIC`, probe offsets, BTF offsets and `ksu_symtab.h` are
   tied to this exact kernel build. Don't reformat or "simplify" them; changing
   them without a matching image = kernel oops.
2. **v3 applies no KernelSU init patch by default — keep it that way.**
   The network break is fixed at the source: stage 0 zeroes only the
   `enforcing` byte (8-byte unaligned zero at `selinux_state-7`, leaving
   `initialized`/`policycap` intact), so when KernelSU restores Enforcing the
   Wi-Fi path stays healthy. `KSU_CODEPATCH skip` is the expected line.
   `O3P_CODEPATCH=1` restores the legacy patch (dynamic `.rela.init.text`
   rewrite, offsets `0x144` tiann / KernelSU-Next, `0x178` SukiSU) for
   comparison only. Do **not** patch the `bl setenforce` itself:
   that slot carries a `CALL26` relocation, the loader rewrites it, and the
   result is `Undefined instruction` with the module stuck in `coming` (see
   `docs/APP_O3_ROOT.md`).
3. **The write primitive can only write `0` or a controlled-page pointer.**
   It writes `[forged_node]` and dereferences `parent = value & ~3`, so `0x100`
   would be dereferenced. Don't "just write 0x0100".
4. **`real_cred` is not replaced.** Never call `finit_module` in-process — a
   fresh exec is required (`fork` + `sh -c insmod`). In-process load BUGs in
   `commit_creds` (`task->cred != task->real_cred`).
5. **v3 stage 0 no longer pollutes `policycap`.** The 8-byte unaligned zero at
   `selinux_state-7` clears `enforcing` (and the adjacent `notif_lock`
   pending/tail bytes), so Enforcing is safe and the app
   ships no recovery guard. Only the legacy `O3P_SELDIRTY=1` path dirties
   `policycap[]`; if used, any `setenforce 1` freezes Wi-Fi TX until
   `setenforce 0` or reboot.
6. **rootd protocol**: abstract socket `@o3p_rootd`, `SO_PEERCRED` only accepts
   uid 0/2000, one command per connection, terminated by `__EXIT=<n>`. Do not
   reintroduce world-writable command files in `/data/local/tmp`.
7. **Shizuku must be in shell mode** (`uid=2000`, `u:r:shell:s0`). Root-mode
   Shizuku runs in `u:r:ksu:s0`, and KSU's bounded domain rejects exec of the
   payload.
8. **Release hygiene**: `android:debuggable` stays off; the firmware gate in
   `Main.checkFirmware()` must match the kernel/branch the payload was built
   for (`yingtian`/`lhasa`).
9. **A custom `.ko` is untrusted input.** `CustomKo.java` (app) and
   `ko_validate()` (payload) are the only gates: keep them in sync, and never
   remove the automatic fallback to the embedded blob when the custom file is
   unreadable, rejected or fails to load. The checks are compatibility checks,
   not a security boundary — a module that passes still runs in the kernel.

10. **KernelSU loading is runtime-resolved by default.** `kallsyms_patch()`
   collects the module's UNDEF names, temporarily writes `0` to
   `/proc/sys/kernel/kptr_restrict`, resolves addresses through
   `/proc/kallsyms`, and always restores the old value before `insmod`.
   `O3P_RELINK_ONLY=1` forces the embedded 248-symbol table + slide path
   (offline fallback if kallsyms ever stops exposing addresses). Upstream LKMs
   (tiann, KernelSU-Next, SukiSU) all load; their manager packages and ksud
   differ, so swapping forks also means swapping the manager APK/ksud.

## Verified / not verified

Verified on hardware (v3.0): full cold-boot chain (`uid=0` + full caps),
KernelSU `Live` with **no init patch**, manager auto-open, Enforcing end state
with healthy network (`ping`/`nc`) and unchanged `boot_id`. v3.1 and v3.1.1 were re-verified on the same device on
2026-11-01: adaptive cold boot (`KSU_BLOB v2 tag=3 matched=1`,
`KSU_MODULE LIVE`), custom tiann v3.3.1 picker (warning accepted, persisted
across force-stop/restart), forced 6.6.000-vermagic module (`CUSTOM_KO force
reason=kernel_series_mismatch` -> relink -> `KSU_MODULE LIVE`), and long-press
clear. v3.2 and v3.2-beta1 were verified on the same device on 2026-11-01:
the stable build shows the selectable LogBox with the 复制 action and no
console entry on a long press, while the beta build passed all three gate
steps, opened the console with its one-shot token, and ran `id` as uid 0
through the rootd socket (`[exit 0]`); copying the console output into the
clipboard was verified by pasting it back. The automatic fallback branch was
not exercised on hardware (the forced
module still loaded) and remains desktop-tested. v1.8–v2.1 had
additionally verified the old guard/recovery UI and network health while
Permissive (both removed in v3.0). The v2.0 release APK was cold-boot tested; v2.1 was cold-boot tested with a revoked Shizuku permission (grant → probe → automatic payload)
end-to-end on three images (only the tiann manager, only KernelSU-Next, only
SukiSU): each time the app detected the manager, the payload selected the
matching embedded LKM and the matching manager came to the foreground;
`O3P_RELINK_ONLY=1` still falls back to the 248-symbol union.

Not verified: `lhasa` hardware; **original Shizuku** (we use the official
`dev.rikka.shizuku` API, but only ShizukuX 13.7 was exercised); `/system/bin/su`
appears only after KernelSU's first root request.

Negative results — don't repeat without a new idea (details in
`docs/AGENT_HANDOFF.md`):

- io_uring ring pages **cannot** reclaim the freed order-1 `posix_timers` slab
  (setup allocates order-0/vmalloc; measured 0 hits).
- `AF_PACKET` `PACKET_TX_RING` (8 KB blocks) **does** reclaim order-1 pages at
  93.5% — but needs `CAP_NET_RAW`, which the pre-root shell/App domain lacks.
- Forging `cred_security_struct.sid = 1` gives `unlabeled`, and KernelSU's
  `hook_file_open` dereferences the fake blob → oops (SID route unusable with
  KSU loaded).

## Build traps

- `d8` chokes on **anonymous/inner classes** (internal NPE) and doesn't accept
  directories; keep top-level classes and pass `build/classes/**/*.class`.
- Git Bash + `javac`: classpath wildcard `build/cp/*`; `;` does not work.
- Windows: pass `E:/forward/slash` paths to `java`/`aapt2`/`zipalign`
  (`/e/...` is bash-only). The scripts auto-add `.exe`.
- Python-generated C/Java: heredoc turns `\n` into a real newline — build such
  strings with `chr(92)+'n'`, or write the file with the editor tool.
- `aapt2 link` needs a prior `aapt2 compile --dir res`; `-A assets` ships the
  bundled KernelSU manager.
- Don't commit CRLF churn for `payload/*.c`; the index normalizes to LF.
- `BETA=1` writes `build/gen/BuildInfo.java` (`CONSOLE=true`,
  `VERSION="3.2-beta1"`) and a generated `build/AndroidManifest.xml`
  (versionCode 16, `ConsoleActivity` entry). The stable build compiles the
  tracked `BuildInfo.java` (`CONSOLE=false`, `3.2`) and excludes
  `ConsoleActivity.java`/`ConsoleGate.java`; `Main` reaches the gate only
  via `ConsoleEntry` reflection, so never link `ConsoleGate` from stable
  code.
- Keep the log box selectable and copyable: don't drop
  `setTextIsSelectable(true)`; auto-scroll must stay conditional on being
  near the bottom.

## Device testing checklist

1. Shizuku running in shell mode: `ps -A -o UID,LABEL,ARGS | grep shizuku_plus_server`
   → `2000 u:r:shell:s0`.
2. Install: `adb install -r release/o3root2-v3.2.apk`; if MIUI says
   `INSTALL_FAILED_USER_RESTRICTED`, allow install-via-USB or, with root,
   `pm install -r -d`.
3. Open the app, grant Shizuku permission, press Continue; wait 20–120 s.
4. Success markers: `★ UIDNOW uid=0`, `KSU_INSMOD_OUT:[rc=0]`,
   `★ KSU_MODULE LIVE`, manager in foreground (`am start` log + `top ->`).
5. Health: `getenforce` = Enforcing (v3; legacy dirty write ends Permissive), `ping <gateway>`, `nc 223.5.5.5 443`,
   `cat /proc/sys/kernel/random/boot_id` unchanged.
5.5 If the run failed and you want to retry: check `shizuku_plus_server` is
   still `2000 u:r:shell:s0`. After any KernelSU grant ShizukuX may restart in
   root mode (`u:r:ksu:s0`), whose bounded-transition domain refuses to exec the
   payload — restart the Shizuku server (or reboot) back to shell mode before
   pressing Continue again.
6. Rollback: reboot; optional `rm -f /data/local/tmp/o3p*` and uninstall.
7. Beta console (test build only):
   `adb install -r -d release/beta/o3root2-v3.2-beta1.apk` (its versionCode
   16 is lower than stable 17); start the payload once so rootd is running,
   long-press the version row, and pass the three confirmations; `id` must
   print `uid=0`. Stable must show no console entry on the same long press.

| symptom | meaning / action |
|---|---|
| `VERDICT NO_RECLAIM` repeated | race is probabilistic; retry (1–6 attempts typical), reboot if stuck |
| `固件门禁未通过` | kernel/fingerprint mismatch — new profile needed |
| network dead after KernelSU | only with legacy `O3P_SELDIRTY=1`: polluted policycap + Enforcing → `setenforce 0` or reboot |
| `KSU_CODEPATCH … -2` | only with legacy `O3P_CODEPATCH=1`: the `setenforce` relocation was not found (unique-scan fallback failed) — check the LKM |
| manager not opening | check the app install path/package, and that KSU manager package exists |

## Hygiene before commit

```bash
bash scripts/verify.sh
```

It checks: shell/Python syntax, required files, `ksu_symtab.h` count, release
APK checksum, tracked secrets, and scans every file (including binaries) for
device serials, host paths, keystore names/passwords, packet-capture-style
identifiers (MAC/BSSID/SSID/IP). If it fails, fix the match — do not weaken the
check.

Rules: never commit `tools/` (SDK/NDK/Shizuku jars), `app/build`,
`payload/build`, `*.p12/*.jks`, `env.sh`, OTA zips, `.img`, or real device
identifiers. Third-party licenses (`NOTICE.md`, `third_party/NOTICE.md`) must
stay accurate — KernelSU (LKM + manager APK) is GPL-3.0.

## Where to start for a task

- **Port to another kernel/device**: `docs/ARCHITECTURE.md` → "Porting"; then
  regenerate `payload/ksu/ksu_symtab.h`, update constants, re-locate the KSU
  patch offset (only if you keep the legacy `O3P_CODEPATCH=1` path), update the
  app gate, and test cold boot.
- **App/UI/feature work**: `app/src/o3/st/root1/*.java`; build with
  `app/build.sh`; keep the class set free of anonymous classes.
- **Payload behavior**: `payload/o3p.c`; rebuild with
  `payload/build-payload.sh` and compare `libo3p.so` sha against the reference.
- **Open ideas / history of experiments**: `docs/AGENT_HANDOFF.md`.

### v3 (current)

v3 keeps the two-stage exploit but changes both writes: stage 0 is an
**enforcing-only** zero (8-byte unaligned write at `selinux_state-7`), stage 1
is the usual `task_cred` swap. KernelSU is then loaded with the runtime
kallsyms loader and **no init patch**; its own `setenforce(1)` is harmless
because `policycap` stayed clean, so the device ends in Enforcing with working
Wi-Fi. A single-reap three-write variant was tried and continuously panicked;
it is kept only behind `O3P_D3V3` for analysis. Cold-boot tested on yingtian
(`KSU_CODEPATCH skip`, `KALLSYMS_PATCH found=225`, `KSU_MODULE LIVE`,
Enforcing, `ping`/`nc` OK, manager opened).
