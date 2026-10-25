# AGENTS.md — entry point for automated contributors

This repository delivers **O3 Root**: a Shizuku-driven Android app that
exploits `CVE-2026-98260` on Xiaomi `yingtian` (Pad 9 Pro Max) / `lhasa`
(Xiaomi 18 Fold, HyperOS 4.0.26) with kernel `6.18.21`, loads a KernelSU LKM,
and leaves a root daemon for the current boot. Read this file first, then
`README.md`, `docs/AGENT_HANDOFF.md`, `docs/ARCHITECTURE.md`.

## State

- Release **v1.8**, `release/o3root2-v1.8.apk`
  (`13,973,008 B`, `sha256 0d7c5ea8b1a4d4c35d0c7c444d0c022b36ed5af9b28f7d2f9c7b31e04afff05e`).
- Hardware-verified on `yingtian` / `OS4.0.16.0.XBMCNXM` (cold boot, end-to-end).
- `lhasa` / `OS4.0.26.0.XPNCNXM` ships the **bit-identical kernel**
  (`sha256 cde87cd7…`); not device-tested.
- Boot-only: a reboot removes root, KernelSU and the permissive SELinux state.

## Fast commands

```bash
bash scripts/verify.sh            # static checks + optional builds (run before commit)
bash scripts/fetch-toolchain.sh   # android.jar, build-tools r34, Shizuku jars, pyelftools

# payload  (needs an NDK clang)
NDK_CC=/path/to/ndk/.../aarch64-linux-android35-clang bash payload/build-payload.sh
# reference result: 62,048 B payload; libo3p.so 437,400 B sha256 a7e9833883…75ec4

# app (needs SDK + Shizuku jars + your keystore; see docs/BUILD.md)
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... bash app/build.sh
```

## Map

| path | what lives there |
|---|---|
| `payload/o3p.c` | the exploit payload (C): UAF trigger, page reclaim, RB write primitive, two stages, KASLR slide, KernelSU relink/patch/load, rootd |
| `payload/ksu/` | `ksu_symtab.h` (225 KernelSU symbols → addresses), `mksymtab.py` (regen), `relink_ksu.py` (offline relinker reference) |
| `payload/lkm/` | official KernelSU v3.3.1 LKM for `android17-6.18` (GPL-3.0, unmodified) |
| `app/src/o3/st/root1/` | Java: `Main` (UI/state machine), `PayloadRunner`, `StateProbe`, `RecoverRunner`, `ManagerInstaller`, listeners |
| `app/build.sh`, `payload/build-payload.sh`, `scripts/*` | build entry points |
| `docs/` | ARCHITECTURE (design + porting), SECURITY (risks/cleanup), BUILD, APP_O3_ROOT (dev log), OTA/kernel report, CVE feasibility, evidence PNGs |
| `tools/` | OTA extraction / boot image unpack / BTF+disasm checks |
| `release/` | prebuilt APK + SHA256SUMS |

## Invariants — do not "clean up"

1. **Kernel profile constants are load-bearing.** `INIT_USER_NS_STATIC`,
   `SELINUX_STATE_STATIC`, probe offsets, BTF offsets and `ksu_symtab.h` are
   tied to this exact kernel build. Don't reformat or "simplify" them; changing
   them without a matching image = kernel oops.
2. **KernelSU init patch is exactly one immediate**: `.init.text+0x144`
   (`52 80 00 20` → `52 80 00 00`, i.e. `mov w0,#1` → `mov w0,#0`) at file
   offset `0x22144`, verified in `ksu_patch()`. Do **not** patch the
   `bl setenforce` at `+0x148`: that slot carries a `CALL26` relocation, the
   loader rewrites it, and the result is `Undefined instruction` with the
   module stuck in `coming` (see `docs/APP_O3_ROOT.md`).
3. **The write primitive can only write `0` or a controlled-page pointer.**
   It writes `[forged_node]` and dereferences `parent = value & ~3`, so `0x100`
   would be dereferenced. Don't "just write 0x0100".
4. **`real_cred` is not replaced.** Never call `finit_module` in-process — a
   fresh exec is required (`fork` + `sh -c insmod`). In-process load BUGs in
   `commit_creds` (`task->cred != task->real_cred`).
5. **Stage 0 pollutes `selinux_state.policycap[]`.** While SELinux stays
   Permissive that's inert, but any `setenforce 1` makes Wi-Fi TX freeze until
   `setenforce 0` or reboot. The app (v1.8+) detects this and offers recovery;
   don't remove the guard.
6. **rootd protocol**: abstract socket `@o3p_rootd`, `SO_PEERCRED` only accepts
   uid 0/2000, one command per connection, terminated by `__EXIT=<n>`. Do not
   reintroduce world-writable command files in `/data/local/tmp`.
7. **Shizuku must be in shell mode** (`uid=2000`, `u:r:shell:s0`). Root-mode
   Shizuku runs in `u:r:ksu:s0`, and KSU's bounded domain rejects exec of the
   payload.
8. **Release hygiene**: `android:debuggable` stays off; the firmware gate in
   `Main.checkFirmware()` must match the kernel/branch the payload was built
   for (`yingtian`/`lhasa`).

## Verified / not verified

Verified on hardware: full cold-boot chain, `uid=0` + full caps, KernelSU
`Live`, manager auto-open, v1.8 Enforcing-rebound guard and one-click recovery,
network health while Permissive.

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

## Device testing checklist

1. Shizuku running in shell mode: `ps -A -o UID,LABEL,ARGS | grep shizuku_plus_server`
   → `2000 u:r:shell:s0`.
2. Install: `adb install -r release/o3root2-v1.8.apk`; if MIUI says
   `INSTALL_FAILED_USER_RESTRICTED`, allow install-via-USB or, with root,
   `pm install -r -d`.
3. Open the app, grant Shizuku permission, press Continue; wait 20–120 s.
4. Success markers: `★ UIDNOW uid=0`, `KSU_INSMOD_OUT:[rc=0]`,
   `★ KSU_MODULE LIVE`, manager in foreground (`am start` log + `top ->`).
5. Health: `getenforce` = Permissive, `ping <gateway>`, `nc 223.5.5.5 443`,
   `cat /proc/sys/kernel/random/boot_id` unchanged.
6. Rollback: reboot; optional `rm -f /data/local/tmp/o3p*` and uninstall.

| symptom | meaning / action |
|---|---|
| `VERDICT NO_RECLAIM` repeated | race is probabilistic; retry (1–6 attempts typical), reboot if stuck |
| `固件门禁未通过` | kernel/fingerprint mismatch — new profile needed |
| network dead after KernelSU | SELinux back to Enforcing + polluted policycap → recovery button or reboot |
| `KSU_CODEPATCH … -2` | bundled LKM not the expected build/offset — re-check patch location |
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
  patch offset, update the app gate, and test cold boot.
- **App/UI/feature work**: `app/src/o3/st/root1/*.java`; build with
  `app/build.sh`; keep the class set free of anonymous classes.
- **Payload behavior**: `payload/o3p.c`; rebuild with
  `payload/build-payload.sh` and compare `libo3p.so` sha against the reference.
- **Open ideas / history of experiments**: `docs/AGENT_HANDOFF.md`.
