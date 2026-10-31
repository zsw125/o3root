# Publishing the repository (GitHub)

The tree is GitHub-ready: `main` branch, MIT license, third-party notices,
`scripts/verify.sh` (also run by `.github/workflows/verify.yml`), and no
secrets or device identifiers — the script scans both text and binaries.

## Current publication state (2026-11-01)

- `main` head: the v3.2 release commit (tag `v3.2`); the test build is tagged
  `v3.2-beta1` on the same tree. Earlier tags `v3.1.1` (`93618ac`) and `v3.1`
  (`db24088`) stay where they are.
- Releases: `v3.1` (notes only) and `v3.1.1` remain as history; **v3.2** is
  marked **Latest** with `o3root2-v3.2.apk` (14,239,248 B, sha256
  `64bbc654…`) and `SHA256SUMS.txt`; **v3.2-beta1** is a **pre-release** with
  `release/beta/o3root2-v3.2-beta1.apk` (14,243,344 B, sha256 `4f8845fc…`)
  and `release/beta/SHA256SUMS.txt`.
- The v3.1.1 APK moved out of the tree (it stays in git history at tag
  `v3.1.1`); v3.0 and v2.1 keep their APKs attached to their releases.
- `scripts/verify.sh` is green locally for the v3.2 tree; with
  `VERIFY_BETA=1` it also builds and checks the beta variant.

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

The built APKs are committed at `release/o3root2-v3.2.apk` and
`release/beta/o3root2-v3.2-beta1.apk`, each next to its own `SHA256SUMS.txt`.
Tag and push first:

```bash
git tag -a v3.2 -m "v3.2: selectable/copyable log box; beta root console"
git tag -a v3.2-beta1 -m "v3.2-beta1: hidden root console test build"
git push origin main v3.2 v3.2-beta1
```

Then create the releases in the web UI, or with `gh` (files are positional;
`--latest`/`--prerelease` set the channel):

```bash
gh release create v3.2 --title "v3.2" --notes-file notes.md \
  --verify-tag --latest release/o3root2-v3.2.apk release/SHA256SUMS.txt

gh release create v3.2-beta1 --title "v3.2-beta1" --notes-file beta-notes.md \
  --verify-tag --prerelease release/beta/o3root2-v3.2-beta1.apk \
  release/beta/SHA256SUMS.txt
```

The beta APK has versionCode 16, below the stable 17, so installing it over a
stable install needs `adb install -r -d`. Keeping the committed copies makes
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
3. `git tag v3.2 && git tag v3.2-beta1 && git push origin v3.2 v3.2-beta1`；
   在 Releases 上传 `release/o3root2-v3.2.apk` + `release/SHA256SUMS.txt`
   （勾选 Latest），以及 `release/beta/o3root2-v3.2-beta1.apk` +
   `release/beta/SHA256SUMS.txt`（勾选 Pre-release）。beta 的 versionCode 16
   低于正式版 17，覆盖安装需 `adb install -r -d`。
4. 许可证：本项目 MIT；内置 LKM/管理器为 GPL-3.0（`NOTICE.md` 已列上游源码
   链接）；Shizuku 库 Apache-2.0。不要删除这些声明。
5. 仅供自有设备的安全研究；仓库已通过 `scripts/verify.sh` 的脱敏检查。
