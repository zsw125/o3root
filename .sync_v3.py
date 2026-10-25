import glob, io, os

GLOBAL = [
    ('o3root2-v2.1.apk', 'o3root2-v3.0.apk'),
    ('85c3516b84010af241dc95bbca345ebf1d8ed1f2c6ebd8cc0f82161371067178',
     'c80f499a6b06ddf92241aa8cb0aa8874767eccdd960e631b28dcff98e7c141dd'),
    ('85c3516b\u2026', 'c80f499a\u2026'),
    ('98936d14b5f50b1fc4a9b35741404088a33e1a1fe68042b61e8e948c31f87dfa',
     'e64217c8fac2950763869881efdd15ff9fb76f1423a3388185bca7e72499aac3'),
    ('98936d14\u2026', 'e64217c8\u2026'),
    ('1,488,284', '1,492,484'),
    ('69,568 B', '73,768 B'),
    ('69,568', '73,768'),
]

TARGET = [
    ('AGENTS.md', 'Release **v2.1**', 'Release **v3.0**'),
    ('AGENTS.zh-CN.md', '\u53d1\u5e03 **v2.1**', '\u53d1\u5e03 **v3.0**'),
    ('README.md', '| release | **v2.1** (', '| release | **v3.0** ('),
    ('README.zh-CN.md', '| \u53d1\u5e03 | **v2.1**\uff08', '| \u53d1\u5e03 | **v3.0**\uff08'),
    ('docs/AGENT_HANDOFF.md', '| release | v2.1,', '| release | v3.0,'),
    ('docs/AGENT_HANDOFF.zh-CN.md', '| \u53d1\u5e03 | v2.1\uff0c', '| \u53d1\u5e03 | v3.0\uff0c'),
    ('docs/BUILD.md', 'Reference build (v2.1,', 'Reference build (v3.0,'),
    ('docs/BUILD.zh-CN.md', '\u53c2\u8003\u6784\u5efa\uff08v2.1\uff0c', '\u53c2\u8003\u6784\u5efa\uff08v3.0\uff0c'),
    ('PUBLISH.md', 'git tag -a v2.1 -m "v2.1 permission-aware probe"',
     'git tag -a v3.0 -m "v3: two-reap clean enforcing-only zero, no KSU init patch"'),
    ('PUBLISH.md', 'git push origin v2.1', 'git push origin v3.0'),
    ('PUBLISH.md', 'gh release create v2.1', 'gh release create v3.0'),
    ('PUBLISH.md', 'git tag v2.1 && git push origin v2.1', 'git tag v3.0 && git push origin v3.0'),
    ('scripts/verify.sh',
     'case "$v" in *"\'2.1\'"*) ok "APK rebuilt ($v)";; *) bad "APK version $v (expected 2.1)";; esac',
     'case "$v" in *"\'3.0\'"*) ok "APK rebuilt ($v)";; *) bad "APK version $v (expected 3.0)";; esac'),
    ('NOTICE.md',
     'the payload generates a *runtime copy* with rebased symbols\n    (via `/proc/kallsyms`) plus one patched init immediate, then loads that\n    copy.',
     'the payload generates a *runtime copy* with rebased symbols\n    (via `/proc/kallsyms`); v3 applies no init patch by default (legacy\n    `O3P_CODEPATCH=1` restores it), then loads that copy.'),
    ('NOTICE.md',
     'The runtime/offline rebasing only rewrites ELF symbol values (and one\n  `mov` immediate in `.init.text`)',
     'The runtime/offline rebasing only rewrites ELF symbol values (and, only in\n  legacy mode, one `mov` immediate in `.init.text`)'),
    ('NOTICE.zh-CN.md',
     '\u8f7d\u8377\u5728\n    \u52a0\u8f7d\u65f6\u751f\u6210*\u8fd0\u884c\u65f6\u526f\u672c*\uff08\u7ecf `/proc/kallsyms` \u91cd\u5b9a\u4f4d\u7b26\u53f7\uff0c\u5e76\u6539\u4e00\u5904 init\n    \u7acb\u5373\u6570\uff09\u518d\u52a0\u8f7d\u3002',
     '\u8f7d\u8377\u5728\n    \u52a0\u8f7d\u65f6\u751f\u6210*\u8fd0\u884c\u65f6\u526f\u672c*\uff08\u7ecf `/proc/kallsyms` \u91cd\u5b9a\u4f4d\u7b26\u53f7\uff09\u518d\u52a0\u8f7d\uff1bv3 \u9ed8\u8ba4\u4e0d\u6539 init\n    \u7acb\u5373\u6570\uff08`O3P_CODEPATCH=1` \u53ef\u56de\u9000\u65e7\u8865\u4e01\uff09\u3002'),
]

AGENTS_OLD = '''2. **The KernelSU init patch is dynamic, but still exactly one immediate.**
   The loader parses `.rela.init.text`, finds the relocation whose symbol is
   `setenforce`, and rewrites the 4 bytes at `r_offset - 4` (`mov w0,#1` \u2192
   `mov w0,#0`: `52 80 00 20` \u2192 `52 80 00 00`). Observed offsets inside
   `.init.text`: `0x144` (tiann / KernelSU-Next) and `0x178` (SukiSU). If the
   relocation cannot be resolved it falls back to scanning for a unique
   `mov w0,#1` followed by `BL`.'''

AGENTS_NEW = '''2. **v3 applies no KernelSU init patch by default \u2014 keep it that way.**
   The network break is fixed at the source: stage 0 zeroes only the
   `enforcing` byte (8-byte unaligned zero at `selinux_state-7`, leaving
   `initialized`/`policycap` intact), so when KernelSU restores Enforcing the
   Wi-Fi path stays healthy. `KSU_CODEPATCH skip` is the expected line.
   `O3P_CODEPATCH=1` restores the legacy patch (dynamic `.rela.init.text`
   rewrite, offsets `0x144` tiann / KernelSU-Next, `0x178` SukiSU) for
   comparison only.'''

AGENTS_ZH_OLD = '''2. **KernelSU init \u8865\u4e01\u53ea\u6539\u4e00\u4e2a\u7acb\u5373\u6570**\uff1a`.init.text+0x144`
   \uff08`52 80 00 20` \u2192 `52 80 00 00`\uff0c\u5373 `mov w0,#1` \u2192 `mov w0,#0`\uff09\uff0c\u6587\u4ef6
   \u504f\u79fb `0x22144`\uff0c\u7531 `ksu_patch()` \u6821\u9a8c\u3002**\u7edd\u5bf9\u4e0d\u8981**\u6539 `+0x148` \u7684
   `bl setenforce`\uff1a\u8be5\u69fd\u4f4d\u5e26 `CALL26` \u91cd\u5b9a\u4f4d\uff0c\u52a0\u8f7d\u5668\u4f1a\u91cd\u5199\u5b83\uff0c\u7ed3\u679c\u662f
   `Undefined instruction`\u3001\u6a21\u5757\u5361\u5728 `coming`\uff08\u89c1 `docs/APP_O3_ROOT.md`\uff09\u3002'''

AGENTS_ZH_NEW = '''2. **v3 \u9ed8\u8ba4\u4e0d\u7ed9 KernelSU \u6253 init \u8865\u4e01 \u2014\u2014 \u4fdd\u6301\u8fd9\u4e2a\u9ed8\u8ba4\u3002**
   \u65ad\u7f51\u6839\u56e0\u5df2\u6d88\u9664\uff1astage0 \u53ea\u628a `enforcing` \u5b57\u8282\u6e05\u96f6\uff08\u5728 `selinux_state-7` \u505a 8 \u5b57\u8282
   \u975e\u5bf9\u9f50\u96f6\u5199\uff0c`initialized`/`policycap` \u539f\u6837\u4fdd\u7559\uff09\uff0c\u6240\u4ee5 KSU \u91cd\u65b0\u62c9\u56de
   Enforcing \u540e Wi-Fi \u4ecd\u6b63\u5e38\uff1b\u65e5\u5fd7\u91cc `KSU_CODEPATCH skip` \u5373\u6b63\u786e\u8def\u5f84\u3002
   \u4ec5\u7528\u4e8e\u5bf9\u6bd4\u65f6\u53ef\u7528 `O3P_CODEPATCH=1` \u56de\u9000\u65e7\u8865\u4e01\uff08`.rela.init.text` \u52a8\u6001\u5b9a\u4f4d\uff0c
   tiann/Next `0x144`\u3001SukiSU `0x178`\uff1b\u6587\u4ef6\u504f\u79fb `0x22144`/`0x37144`/`0x3e178`\uff09\u3002
   **\u7edd\u5bf9\u4e0d\u8981**\u6539 `+0x148` \u7684 `bl setenforce`\uff1a\u8be5\u69fd\u4f4d\u5e26 `CALL26` \u91cd\u5b9a\u4f4d\uff0c\u52a0\u8f7d\u5668\u4f1a\u91cd\u5199\u5b83\uff0c
   \u7ed3\u679c\u662f `Undefined instruction`\u3001\u6a21\u5757\u5361\u5728 `coming`\uff08\u89c1 `docs/APP_O3_ROOT.md`\uff09\u3002'''

APPEND = {
 'AGENTS.md': '''
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
''',
 'AGENTS.zh-CN.md': '''
### v3\uff08\u5f53\u524d\uff09

v3 \u4fdd\u7559\u4e24\u6bb5\u5f0f\u5229\u7528\uff0c\u4f46\u4e24\u6b21\u5199\u90fd\u6539\u4e86\uff1astage0 \u662f**\u53ea\u6e05 enforcing** \u7684\u96f6\u5199
\uff08\u5728 `selinux_state-7` \u505a 8 \u5b57\u8282\u975e\u5bf9\u9f50\u5199\uff09\uff0cstage1 \u4ecd\u662f `task_cred` \u6362\u6307\u9488\u3002\u4e4b\u540e\u7528\u8fd0\u884c\u65f6
kallsyms \u52a0\u8f7d\u5668\u88c5 KSU\uff0c**\u4e0d\u6253 init \u8865\u4e01**\uff1bKSU \u81ea\u5df1\u7684 `setenforce(1)` \u65e0\u5bb3\uff08policycap
\u5e72\u51c0\uff09\uff0c\u6240\u4ee5\u8bbe\u5907\u505c\u5728 Enforcing \u4e14 Wi-Fi \u6b63\u5e38\u3002\u66fe\u5c1d\u8bd5\u201c\u4e00\u6b21 reap \u4e09\u6b21\u5199\u201d\uff0c\u8fde\u7eed panic\uff0c
\u73b0\u4ec5\u4fdd\u7559\u5728 `O3P_D3V3` \u540e\u4f9b\u5206\u6790\u3002yingtian \u51b7\u542f\u5df2\u9a8c\u8bc1\uff08`KSU_CODEPATCH skip`\u3001
`KALLSYMS_PATCH found=225`\u3001`KSU_MODULE LIVE`\u3001Enforcing\u3001`ping`/`nc` \u6b63\u5e38\u3001\u7ba1\u7406\u5668\u5df2\u6253\u5f00\uff09\u3002
''',
 'docs/APP_O3_ROOT.md': '''
## v3 (2026-10-25 22:34, branch exp/v3-tracepoint → main)

Two-reap v3: stage 0 is an unaligned 8-byte zero at `selinux_state-7` (clears
`enforcing`, keeps `initialized`/`policycap`), stage 1 is the `task_cred`
swap. KernelSU loads through the runtime kallsyms loader with **no init
patch**; its own `setenforce(1)` now leaves the network healthy.

Cold boot (yingtian, boot `42860b0e-b370-4594-b14d-da2d521fa62b`, no KSU):

```
KSU_FORK chosen=tiann tag=1 src=env
KSU_BLOB v2 tag=1 len=375328 count=3 matched=1
KALLSYMS_PATCH found=225 missing=0
KSU_CODEPATCH skip
KSU_WROTE /data/local/tmp/o3p_ksu.ko 375328
KSU_EXEC done=1 status=0x0
KSU_INSMOD_OUT:[rc=0]
KSU_MODULE LIVE
ROOTD sock ready
UIDNOW uid=0
payload exit=0
am start -> me.weishu.kernelsu/.ui.MainActivity Status: ok
top -> me.weishu.kernelsu/.ui.MainActivity
```

Final state: Enforcing, `kernelsu` Live, `ping 10.30.0.1` 22 ms,
`nc 223.5.5.5:443` rc=0, manager shows `工作中 [越狱模式]`.

Single-reap, three-write attempt (v3a/v3b/v3c) panicked three times right
after the first `D3ADDR`; it stays behind `O3P_D3V3` for analysis only.
''',
}

def apply(path, old, new):
    s = io.open(path, encoding='utf-8').read()
    if old not in s:
        return 0
    s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    return 1

changed = 0
miss = []
for p in glob.glob('**/*.md', recursive=True) + ['scripts/verify.sh']:
    if p in ('docs/APP_O3_ROOT.md', 'docs/V3-DESIGN.md'):
        continue
    s = io.open(p, encoding='utf-8').read()
    o = s
    for a, b in GLOBAL:
        s = s.replace(a, b)
    if s != o:
        io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
        changed += 1

for f, a, b in TARGET:
    if os.path.exists(f):
        if not apply(f, a, b):
            miss.append((f, a[:40]))
    else:
        miss.append((f, 'MISSINGFILE'))

for f, a, b in (('AGENTS.md', AGENTS_OLD, AGENTS_NEW),
                ('AGENTS.zh-CN.md', AGENTS_ZH_OLD, AGENTS_ZH_NEW)):
    if not apply(f, a, b):
        miss.append((f, 'invariant2'))

for f, text in APPEND.items():
    with io.open(f, 'a', encoding='utf-8', newline='\n') as fh:
        fh.write(text)
    changed += 1

print('changed', changed, 'miss', len(miss))
for m in miss:
    print('MISS', m)
