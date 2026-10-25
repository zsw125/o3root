# Build

The project does **not** use Gradle. Everything is plain `javac` + Android SDK
build tools + NDK, driven by shell scripts so the output is easy to inspect and
modify.

## Prerequisites

| component | tested | notes |
|---|---|---|
| JDK | 17 / 21 / 27 | `javac`, `keytool`; the app targets Java 17 |
| Python | 3.10+ | `pyelftools` needed only for `relink_ksu.py` / `mksymtab.py` |
| Android SDK build-tools | r34 | `aapt2`, `d8`, `zipalign`, `apksigner` |
| `android.jar` | platform 34 (ext12) | compile SDK for the app |
| Android NDK | r26+ (r28c tested) | only to build the native payload |
| Shizuku jars | 13.1.5 | `dev.rikka.shizuku:{api,provider,aidl,shared}` |
| OS | Linux / macOS / Windows | on Windows use Git Bash; scripts auto-pick `.exe` |

## 1. Toolchain

```bash
bash scripts/fetch-toolchain.sh          # downloads into .toolchain/
```

It fetches:

- `android.jar` from `platform-34-ext12_r01.zip` (Google)
- `build-tools_r34` for the current OS
- Shizuku 13.1.5 `api`, `provider`, `aidl`, `shared` AARs from Maven Central
- `pyelftools` into `.toolchain/pylib`

If you are offline, put the same files under `.toolchain/` manually — see
`scripts/fetch-toolchain.sh` for the expected names.

## 2. Environment

```bash
cp scripts/env.example.sh env.sh
$EDITOR env.sh
. ./env.sh
```

The example file documents every variable. The important ones:

```sh
ANDROID_JAR=.../android.jar
BUILD_TOOLS=.../build-tools/34.0.0
SHIZUKU_JARS=.../shizuku          # dir with the four *-classes.jar
NDK_CC=.../aarch64-linux-android35-clang      # or .cmd on Windows
KEYSTORE=.../release.p12
KS_PASS=...
KEY_ALIAS=...
KEY_PASS=...
PAYLOAD=.../payload/build/libo3p.so           # default is this
```

## 3. Payload

```bash
bash payload/build-payload.sh
# build/o3p.bin      ~62 KB
# build/libo3p.so    payload + LKM + trailer
```

`build-payload.sh` only needs `NDK_CC`. The KernelSU LKM is in
`payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko`; the default output matches
the reference build in `release/o3root2-v1.8.apk`
(`sha256 a7e98338…` for `libo3p.so`).

You can run the payload directly on the device (root/shell):

```sh
adb push build/libo3p.so /data/local/tmp/o3p.bin
adb shell 'chmod 755 /data/local/tmp/o3p.bin && /data/local/tmp/o3p.bin 10000 14000 300 1 6 1 8000 800'
```

Arguments: `NFILL g_pairs g_delay g_mode g_att g_reps g_churn g_chwait`
(defaults above are the validated ones; `g_att` is the number of UAF attempts).

## 4. App

```bash
bash app/build.sh
# build/o3root2.apk
```

It compiles the Java sources, dexes them, links resources (including
`assets/ksu-manager.apk`), packs the payload as `lib/arm64-v8a/libo3p.so`,
zipaligns and signs.

If you have no keystore yet:

```bash
keytool -genkeypair -keystore release.p12 -storetype PKCS12 \
  -alias o3root -keyalg RSA -keysize 4096 -validity 3650 \
  -storepass 'change-me' -keypass 'change-me' -dname 'CN=O3 Root'
```

## 5. Install and run

1. Device: enable Developer options → USB debugging; install **Shizuku**
   (or ShizukuX) and start it in **shell mode** (Wireless debugging or ADB).
   Verify the server:
   `ps -A -o UID,LABEL,ARGS | grep shizuku_plus_server` → `2000 u:r:shell:s0`.
2. Install the APK. MIUI sometimes refuses: `INSTALL_FAILED_USER_RESTRICTED`.
   Either allow "Install via USB" in Developer options, or on a rooted device
   `pm install -r -d /data/local/tmp/o3root2.apk`.
3. Open the app. It shows Shizuku / SELinux / KernelSU state and performs a
   firmware gate (kernel release + `yingtian`/`lhasa` fingerprint). Grant the
   Shizuku permission when asked.
4. Press **Continue to UID 0** and wait. A successful run logs
   `★ UIDNOW uid=0`, `KSU_INSMOD_OUT:[rc=0]`, `★ KSU_MODULE LIVE`,
   then opens the KernelSU manager.
5. If the built-in KernelSU manager is missing, the button becomes
   **Install KernelSU manager** and installs the bundled APK (you must accept
   the system installer prompts).

## Verifying

```sh
adb shell 'grep kernelsu /proc/modules; getenforce; id'
# kernelsu 192512 1 - Live 0x0000000000000000 (O)
# Permissive
# uid=0(root) ... context=u:r:ksu:s0   (only if KSU granted shell root)
```

The app's LogBox shows every payload stage; use **Export log** to write
`Android/data/o3.st.root1/files/o3root-log-*.log`.

## Troubleshooting

| symptom | fix |
|---|---|
| `固件门禁未通过` | kernel/fingerprint mismatch — see `docs/ARCHITECTURE.md` (kernel profile) |
| `VERDICT NO_RECLAIM` repeatedly | race is probabilistic; press Continue again (or reboot), usually succeeds in 1–6 attempts |
| Network dead after loading KernelSU | SELinux went back to Enforcing and `policycap` is polluted — press the recovery button (`su -c setenforce 0`) or reboot |
| Shizuku `root mode` instead of shell | start Shizuku in adb/wireless-debug mode on a device without `su`; root mode is rejected by the chain |
| `INSTALL_FAILED_USER_RESTRICTED` | allow install-via-USB, or root `pm install` |
| `KSU_CODEPATCH … -2` | the bundled KernelSU LKM does not match this build; re-check the patch offset in `payload/o3p.c` (`ksu_patch`) |

## Reproducibility

Reference build (v1.8, release/o3root2-v1.8.apk):

- APK `13,973,008` bytes
- `sha256 0d7c5ea8b1a4d4c35d0c7c444d0c022b36ed5af9b28f7d2f9c7b31e04afff05e`
- payload `libo3p.so` `437,400` bytes,
  `sha256 a7e9833883189eeb2a893e2ccff063b55601f382b8dd5d15ad7e81af61975ec4`
