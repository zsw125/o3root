# Publishing the repository (GitHub)

The tree is GitHub-ready: `main` branch, MIT license, third-party notices,
`scripts/verify.sh` (also run by `.github/workflows/verify.yml`), and no
secrets or device identifiers — the script scans both text and binaries.

## Current publication state (2026-11-01)

- `main` head: a docs-only commit on top of the v3.1.1 release commit
  (`93618ac`); tag `v3.1.1` → `93618ac`. The earlier `v3.1` tag stays on its
  own commit (`db24088`).
- Releases (both created 2026-11-01): `v3.1`
  (<https://github.com/zsw125/o3root/releases/tag/v3.1>, notes only — the APK
  stays in git history at tag `v3.1`) and `v3.1.1`, marked **Latest**
  (<https://github.com/zsw125/o3root/releases/tag/v3.1.1>), with
  `o3root2-v3.1.1.apk` (14,239,248 B, sha256 `a013cbff…`) and `SHA256SUMS.txt`.
- v3.0 remains published as history, with its APK attached to the release and
  in git history (`git show v3.0:release/o3root2-v3.0.apk`), not in the tree.
- `scripts/verify.sh` is green locally for the v3.1.1 tree.

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

The built APK is committed at `release/o3root2-v3.1.1.apk` next to
`release/SHA256SUMS.txt`. Also attach it to a GitHub Release:

```bash
git tag -a v3.1.1 -m "v3.1.1: persist the user-selected .ko across app restarts"
git push origin v3.1.1
```

Then create the release in the web UI, or with `gh` (files are positional):

```bash
gh release create v3.1.1 --title "v3.1.1" --notes-file notes.md \
  --verify-tag --latest release/o3root2-v3.1.1.apk release/SHA256SUMS.txt
```

Keeping the committed copy makes `scripts/verify.sh` reproducible; if you
prefer Releases-only, delete the `release/*.apk` files from git and relax that
check first. Older releases: `v3.0` and `v2.1` have their APKs attached; `v3.1`
has notes only (its APK remains in git history at the tag).

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
3. `git tag v3.1.1 && git push origin v3.1.1`，在 Releases 上传
   `release/o3root2-v3.1.1.apk` 与 `SHA256SUMS.txt`。
4. 许可证：本项目 MIT；内置 LKM/管理器为 GPL-3.0（`NOTICE.md` 已列上游源码
   链接）；Shizuku 库 Apache-2.0。不要删除这些声明。
5. 仅供自有设备的安全研究；仓库已通过 `scripts/verify.sh` 的脱敏检查。
