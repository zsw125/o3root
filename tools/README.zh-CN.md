# tools（中文版）

> 英文原版：[`README.md`](README.md)。若两者不一致，以英文版为准并同步本文件。

用于从 OTA 中提取内核、恢复符号、校验载荷常量的辅助脚本。它们都是普通 Python 3
脚本；输入从环境变量或 argv 读取（最初是针对某一份 OTA 写的，默认值体现了这点）。

| 脚本 | 用途 |
|---|---|
| `probe_payload.py <ota.zip>` | 读取 A/B OTA 的 `payload.bin` 头和 protobuf manifest，打印分区/操作结构 |
| `extract_payload.py` | 从 OTA 提取 `boot`、`init_boot`、`vendor_boot`、`dtbo`、`vbmeta*`；逐分区校验 SHA-256 并写 `extract_summary.json`。环境/参数：`OTA_ZIP`、`OUTDIR`（`<ota.zip> [outdir]`） |
| `unpack_boot.py <boot.img>` | 解析 Android boot 镜像（v0–v4），打印头，写出 `kernel`、`ramdisk`、`second`、`dtb` |
| `check_hash.py <ota.zip>` | 研究用：确认 operation 的 `data_sha256` 覆盖的是存储（压缩后）块还是解压后数据 |
| `hash_payload.py <ota.zip> [expected_b64]` | 用 `payload_properties.txt` 的 `FILE_HASH` 校验 `payload.bin` 的 SHA-256 |
| `kernel_cfg_btf.py <kernel>` | 打印内嵌内核配置（`IKCONFIG`）、BTF 布局（`task_struct`、`cred`、`k_itimer`、`selinux_state` 等），并反汇编探针点（`begin_new_exec`、`timerqueue_del`、`rb_erase`、`selinux_inode_permission`）。需要 `pyelftools` + `capstone`（设 `PYELFTOOLS`） |

## 获取内核

```bash
python3 tools/probe_payload.py device-ota.zip
OTA_ZIP=device-ota.zip OUTDIR=out python3 tools/extract_payload.py
python3 tools/unpack_boot.py out/boot.img          # -> out/kernel
```

`out/kernel` 是原始 arm64 `Image`。要得到带符号的 ELF，用外部工具
[vmlinux-to-elf](https://github.com/clabreti/vmlinux-to-elf)
（`vmlinux-to-elf out/kernel out/vmlinux.elf`）；结构布局也可以在与该构建匹配的
环境里用 `pahole` / `bpftool btf dump` 得到。

## 注意

- `kernel_cfg_btf.py` 里内置了本内核的静态基址（`0xffffffc080000000`）与探针点
  偏移；换内核时需要调整。
- OTA 本体（小米专有）**不**包含在本仓库中；仓库只包含 `docs/` 里的哈希与派生
  分析。
