# O3 Root

> Chinese translation: [`README.zh-CN.md`](README.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

Local privilege escalation + KernelSU for the Xiaomi **yingtian** (Pad 9 Pro
Max) and **lhasa** (Xiaomi 18 Fold) family on HyperOS 4 (Android 17, kernel
`6.18.21`). The whole chain is delivered as a normal Android app that drives
[Shizuku](https://github.com/RikkaApps/Shizuku) in shell mode, exploits
**CVE-2026-98260**, and loads a KernelSU LKM — no PC, no unlocked bootloader,
no partition changes.

> Research / education / device-repair project. Boot-only (a reboot removes
> it). Only run on devices you own. Read [`docs/SECURITY.md`](docs/SECURITY.md)
> before using it.

**Agents / automated contributors:** start with [`AGENTS.md`](AGENTS.md) (entry
point: invariants, build/test recipes, traps, hygiene), then
[`docs/AGENT_HANDOFF.md`](docs/AGENT_HANDOFF.md) (verified state, failed
experiments, open work). Run `bash scripts/verify.sh` before committing; it also
rebuilds the payload/APK when the toolchain environment is set.

中文版： [`AGENTS.zh-CN.md`](AGENTS.zh-CN.md) /
[`docs/AGENT_HANDOFF.zh-CN.md`](docs/AGENT_HANDOFF.zh-CN.md)

## Status

| | |
|---|---|
| release | **v1.8** (`release/o3root2-v1.8.apk`, sha256 `0d7c5ea8…`) |
| chain | Shizuku shell → CVE-2026-98260 → `uid=0` + full caps → KernelSU LKM → rootd |
| persistence | none (current boot only) |
| tested device | Xiaomi Pad 9 Pro Max `yingtian`, HyperOS `OS4.0.16.0.XBMCNXM` |
| second device | `lhasa` (Xiaomi 18 Fold, `OS4.0.26.0.XPNCNXM`): the boot kernel is **bit-identical** to yingtian's (`sha256 cde87cd7…`), so the same payload applies; not device-tested |

## How it works (short version)

1. The app spawns `/system/bin/sh -c …` through Shizuku's `newProcess`, so the
   payload runs in `u:r:shell:s0` (`uid=2000`).
2. The payload triggers the `CLOCK_THREAD_CPUTIME_ID` + `execve`/`de_thread`
   race (CVE-2026-98260), reclaims the freed `posix_timers_cache` order-1 slab
   page with a sprayed `kmalloc-8k` buffer containing a forged RB-tree node, and
   uses the second `timerqueue_del → rb_erase` to write one 8-byte value to an
   arbitrary kernel address.
3. Stage 0 writes `selinux_state.enforcing = 0`; stage 1 writes
   `task_struct.cred` to a fake cred page (uid 0, full caps).
4. The payload relinks the embedded KernelSU LKM for the current KASLR slide,
   patches its init so it does not force enforcing back on, `insmod`s it, and
   leaves a `rootd` daemon (abstract socket `@o3p_rootd`, peer-uid 0/2000).

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for offsets, the write
primitive, the KernelSU patch and the porting guide.

## Quick start

1. On the device: enable Developer options → Wireless debugging (or ADB).
   Install **Shizuku** / ShizukuX and start it in **shell mode**:
   `shizuku_plus_server` must run as `uid=2000`, `u:r:shell:s0`.
2. Install `release/o3root2-v1.8.apk` (MIUI may need "Install via USB" allowed).
3. Open **O3 Root**, grant the Shizuku permission, press
   **Continue to UID 0**. The log shows `★ UIDNOW uid=0`,
   `★ KSU_MODULE LIVE`, and the KernelSU manager opens automatically.

No manager installed? The button turns into **Install KernelSU manager** and
installs the bundled APK (accept the system prompts).

## Repository layout

```
AGENTS.md   entry point for automated contributors (invariants, traps, checks)
app/        Android app sources (Java), manifest, res, build.sh
payload/    o3p.c (exploit payload), KernelSU symbol table / relinker,
            mk_payload.py, build-payload.sh, legacy/ (earlier revisions)
tools/      OTA/kernel extraction + verification scripts
docs/       architecture, security, build, OTA analysis, feasibility report,
            evidence screenshots
scripts/    toolchain fetcher, env template, build-all
release/    prebuilt APK + checksums
third_party/ notices for bundled third-party components
```

## Build

```bash
bash scripts/fetch-toolchain.sh
cp scripts/env.example.sh env.sh && . ./env.sh   # edit paths
bash payload/build-payload.sh                    # -> payload/build/libo3p.so
bash app/build.sh                                # -> app/build/o3root2.apk
```

Full prerequisites and troubleshooting: [`docs/BUILD.md`](docs/BUILD.md).

## Limitations

- **Boot-only**: reboot removes root, KernelSU and the permissive SELinux
  state. Persistence would require patching `boot`/`init_boot` (blocked by the
  locked bootloader) and is out of scope.
- **Probabilistic**: the UAF race needs 1–6 attempts (occasionally more). The
  app retries; a reboot usually helps.
- **Permissive SELinux posture**: while active, MAC is off for the whole
  system. See the security notes.
- **Policycap side effect**: if anything sets SELinux back to Enforcing, the
  Wi-Fi TX path stalls until `setenforce 0` or reboot. The app v1.8 detects
  this and offers one-click recovery.
- **KernelSU LKM ABI**: upstream built the module for `6.18.32-4k`; it is
  relinked for this `6.18.21` kernel and therefore taints the kernel. Do not
  treat it as a production root solution.
- **Profile-bound**: offsets/addresses are compiled for this exact kernel. A
  different build needs a new profile (see the porting section in
  `docs/ARCHITECTURE.md`).

## Evidence

`docs/evidence/` contains device screenshots: app success log (`v17app.png`),
KernelSU manager (`v17ksu.png`), the Enforcing-rebound warning (`v18risk.png`)
and the one-click recovery (`v18rec.png`). Detailed logs and the write-up live
in [`docs/APP_O3_ROOT.md`](docs/APP_O3_ROOT.md).

## Docs

| file | content |
|---|---|
| `docs/AGENT_HANDOFF.md` | handoff for automated contributors: verified state, failed experiments, open work, repro workflow |
| `docs/AGENT_HANDOFF.zh-CN.md` | 交接说明（中文版） |
| `docs/ARCHITECTURE.md` | exploit chain, write primitive, KernelSU patch, constants, porting |
| `docs/ARCHITECTURE.zh-CN.md` | 架构说明（中文版） |
| `docs/SECURITY.md` | threat model, side effects, recovery/cleanup |
| `docs/SECURITY.zh-CN.md` | 安全说明（中文版） |
| `docs/BUILD.md` | build + install + verification |
| `docs/BUILD.zh-CN.md` | 构建说明（中文版） |
| `docs/APP_O3_ROOT.md` | full development log / validation report |
| `docs/OTA_4.0.16_kernel_report.md` | OTA extraction, kernel analysis, KASLR constants |
| `docs/CVE-2026-98260_FEASIBILITY.md` | vulnerability analysis and upstream fix references |
| `docs/CVE-2026-98260_FEASIBILITY.zh-CN.md` | 可行性报告（中文版） |
| `docs/ROOT_ACHIEVED_OS4.0.16.md` | first end-to-end run notes |
| `docs/ALTERNATIVE_ROOT_DIRECTIONS.md` | directions considered before this chain |
| `AGENTS.zh-CN.md` | 自动化贡献者入口（中文版） |
| `README.zh-CN.md` | 本 README 的中文版 |
| `payload/README.md` / `payload/README.zh-CN.md` | payload internals (EN / 中文) |
| `tools/README.md` / `tools/README.zh-CN.md` | OTA/kernel tooling (EN / 中文) |
| `NOTICE.zh-CN.md` | 第三方组件说明（中文版） |
| `third_party/NOTICE.zh-CN.md` | 第三方组件明细（中文版） |

## License

MIT — see [`LICENSE`](LICENSE). Bundled third-party components keep their own
licenses; see [`NOTICE.md`](NOTICE.md) and
[`third_party/NOTICE.md`](third_party/NOTICE.md).
