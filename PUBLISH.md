# Publishing the repository (GitHub)

The tree is GitHub-ready: `main` branch, MIT license, third-party notices,
`scripts/verify.sh` (also run by `.github/workflows/verify.yml`), and no
secrets or device identifiers — the script scans both text and binaries.

## Current publication state (2026-10-25)

- `main` head: `17a8cb6`; tag `v3.0` → `dc06899`.
- Release: <https://github.com/zsw125/o3root/releases/tag/v3.0> with
  `o3root2-v3.0.apk` (14,218,768 B, sha256 `c80f499a…`) and `SHA256SUMS.txt`.
- CI: `verify` succeeded on `main`, on the merge commit, and on tag `v3.0`.

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

The built APK is committed at `release/o3root2-v3.0.apk` next to
`release/SHA256SUMS.txt`. Also attach it to a GitHub Release:

```bash
git tag -a v3.0 -m "v3: two-reap clean enforcing-only zero, no KSU init patch"
git push origin v3.0
```

Then create the release in the web UI (or `gh release create v3.0 ...`) and
attach `release/o3root2-v3.0.apk`. Keeping the committed copy makes
`scripts/verify.sh` reproducible; if you prefer Releases-only, delete the
`release/*.apk` files from git and relax that check first.

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
3. `git tag v3.0 && git push origin v3.0`，在 Releases 上传
   `release/o3root2-v3.0.apk` 与 `SHA256SUMS.txt`。
4. 许可证：本项目 MIT；内置 LKM/管理器为 GPL-3.0（`NOTICE.md` 已列上游源码
   链接）；Shizuku 库 Apache-2.0。不要删除这些声明。
5. 仅供自有设备的安全研究；仓库已通过 `scripts/verify.sh` 的脱敏检查。
