# Publishing the repository (GitHub)

The tree is GitHub-ready: `main` branch, MIT license, third-party notices,
`scripts/verify.sh` (also run by `.github/workflows/verify.yml`), and no
secrets or device identifiers — the script scans both text and binaries.

## Current publication state (2026-11-08)

- `main` head: the v3.3.5 release commit (`git log -1`); tag `v3.3.5` → that
  commit. Tags `v3.3` (previous stable), `v3.2`, `v3.2.1-console`,
  `v3.3.5-o1-test` and `v3.3.4-o1-test` (experimental O1) point at their own
  commits; earlier tags `v3.1.1` (`93618ac`) and `v3.1` (`db24088`) stay where
  they are.
- Releases: **v3.3.5** is marked **Latest** with `o3root2-v3.3.5.apk`
  (14,243,344 B, sha256 `28dd6652…`) and `SHA256SUMS.txt`; **v3.3**
  (14,243,344 B, sha256 `c98a9ced…`) is the previous stable release, with
  **v3.2** (14,239,248 B, sha256 `64bbc654…`) before it; **v3.2.1-console** is
  a **pre-release** with `release/beta/o3root2-v3.2.1-console.apk`
  (14,243,344 B, sha256 `9cba365a…`) and `release/beta/SHA256SUMS.txt`;
  **v3.3.5-o1-test** is a **pre-release** with
  `release/test/o3root2-v3.3.5-o1-test.apk` (14,247,440 B, sha256
  `405158cc…`) and `release/test/SHA256SUMS.txt`, superseding
  **v3.3.4-o1-test** (14,247,440 B, sha256 `1a298929…`), which stays as
  history; `v3.1` (notes only) and `v3.1.1` remain as history.
- The v3.1.1 APK moved out of the tree (it stays in git history at tag
  `v3.1.1`); v3.0 and v2.1 keep their APKs attached to their releases.
- `scripts/verify.sh` is green locally for the v3.3.5 tree; with
  `VERIFY_BETA=1` it also builds and checks the test variant.

## 1. Create an empty repository

Create `o3root` on GitHub **without** a README/license/gitignore (this repo
already has them), then:

```bash
cd o3root
git remote add origin git@github.com:<you>/o3root.git    # or the https URL
git push -u origin main
```

No SSH key? Use HTTPS with a personal access token (classic, `repo` scope):

```bash
git push https://<user>:<token>@github.com/<user>/o3root.git main
```

## 2. Publish the release

The built APKs are committed at `release/o3root2-v3.3.5.apk`,
`release/beta/o3root2-v3.2.1-console.apk` and
`release/test/o3root2-v3.3.5-o1-test.apk` (the previous
`release/test/o3root2-v3.3.4-o1-test.apk` stays as history), each next to its
own `SHA256SUMS.txt`. Tag and push first:

```bash
git tag -a v3.3.5 -m "v3.3.5: O1 skips the KernelSU LKM, root from @o3p_rootd"
git tag -a v3.3.5-o1-test -m "v3.3.5-o1-test: O1 community test build"
git push origin main v3.3.5 v3.3.5-o1-test
```

Earlier tags (`v3.3`, `v3.2.1-console`, `v3.3.4-o1-test`) were pushed with
their own releases and stay where they are. Create the new releases in the
web UI, or with `gh` (files are positional;
`--latest`/`--prerelease` set the channel):

```bash
gh release create v3.3.5 --title "O3 Root v3.3.5" --notes-file notes.md \
  --verify-tag --latest release/o3root2-v3.3.5.apk release/SHA256SUMS.txt

gh release create v3.3.5-o1-test --title "v3.3.5-o1-test — O1 testers" \
  --notes-file o1-notes.md --verify-tag --prerelease \
  release/test/o3root2-v3.3.5-o1-test.apk release/test/SHA256SUMS.txt
```

The test APKs have versionCode 18 (console) and 26 (O1 test); both are above
the published stable v3.3.5 (25), so they install over a stable install as a
normal upgrade (`adb install -r`). Keeping the committed copies makes
`scripts/verify.sh` reproducible; if you prefer Releases-only, delete the
`release/**/*.apk` files from git and relax that check first. Older releases:
`v3.1.1`, `v3.0` and `v2.1` have their APKs attached; `v3.1` has notes only.

## 3. Repository settings

- Description: `Shizuku-driven CVE-2026-98260 root + KernelSU loader for Xiaomi
  yingtian / lhasa (kernel 6.18.21)`
- Topics: `android`, `security-research`, `kernel`, `kernelsu`, `shizuku`,
  `cve-2026-98260`, `xiaomi`
- Enable Issues; keep Actions on (`verify` runs `scripts/verify.sh`).
- Don't mark it as a template repository unless you want forks to carry history.

## 4. Licensing

- Our code: MIT (`LICENSE`).
- `payload/lkm/*.ko` and `app/assets/ksu-manager.apk` are unmodified upstream
  KernelSU-family builds (GPL-3.0); `NOTICE.md` and `third_party/NOTICE.md`
  link the corresponding sources. Keep these files accurate.
- Shizuku client libraries (Apache-2.0) are compiled into the APK; the jars are
  not vendored.

## 5. Safety

This is a working local-privilege-escalation exploit for one firmware family.
Use it only on devices you own. The CVE is public; the README and
`docs/SECURITY.md` carry the disclaimer — keep it.

## 中文摘要

1. 在 GitHub 建**空**仓库 `o3root`（不要初始化 README）。
2. `git remote add origin <url>` → `git push -u origin main`。
3. `git tag v3.3.5 && git tag v3.3.5-o1-test &&
   git push origin v3.3.5 v3.3.5-o1-test`；
   在 Releases 上传 `release/o3root2-v3.3.5.apk` + `release/SHA256SUMS.txt`
   （勾选 Latest），以及 `release/test/o3root2-v3.3.5-o1-test.apk` +
   `release/test/SHA256SUMS.txt`（勾选 Pre-release）。测试版 versionCode 26
   高于正式版 25，可正常 `adb install -r` 升级覆盖（控制台测试版为 18）。
4. 许可证：本项目 MIT；内置 LKM/管理器为 GPL-3.0（`NOTICE.md` 已列上游源码
   链接）；Shizuku 库 Apache-2.0。不要删除这些声明。
5. 仅供自有设备的安全研究；仓库已通过 `scripts/verify.sh` 的脱敏检查。
