package o3.st.root1;

/**
 * Basic compatibility validation for a user-selected KernelSU LKM (.ko).
 *
 * <p>Pure Java on purpose (no Android APIs): the same rules are implemented in
 * the native payload ({@code payload/o3p.c}, {@code ko_validate()}) and this
 * class can be exercised from a desktop unit test before the app is built.
 *
 * <p>Policy:
 * <ul>
 *   <li>FAIL: not a 64-bit little-endian AArch64 relocatable object, no
 *       {@code .modinfo}, no {@code name=}, module name outside the
 *       {@code kernelsu} family, no usable bytes, or a different kernel
 *       <em>series</em> (e.g. 6.6 vs 6.18).</li>
 *   <li>WARN: same kernel series but a different build (the shipped LKMs are
 *       built for 6.18.32 and run relinked on 6.18.21), or vermagic missing.</li>
 *   <li>OK: vermagic matches the device kernel release exactly.</li>
 * </ul>
 */
public final class CustomKo {

    public static final int OK = 0;
    public static final int WARN = 1;
    public static final int FAIL = 2;

    /** Sentinel for a file that is too large to be a plausible module. */
    public static final int MAX_BYTES = 8 << 20;

    private CustomKo() {
    }

    public static KoResult validate(byte[] d, String deviceRelease) {
        if (d == null || d.length < 64) {
            return fail("文件过小，不是有效的内核模块", "", "", "");
        }
        if (!(d[0] == 0x7f && d[1] == 'E' && d[2] == 'L' && d[3] == 'F')) {
            return fail("不是 ELF 文件（文件头魔数不匹配）", "", "", "");
        }
        if (d[4] != 2) {
            return fail("不是 64 位 ELF（EI_CLASS=" + (d[4] & 0xff) + "）", "", "", "");
        }
        if (d[5] != 1) {
            return fail("不是小端 ELF（EI_DATA=" + (d[5] & 0xff) + "）", "", "", "");
        }
        int type = u16(d, 16);
        if (type != 1) {
            return fail("不是可重定位目标 ET_REL（e_type=" + type + "），可能不是 .ko 模块", "", "", "");
        }
        int machine = u16(d, 18);
        if (machine != 183) {
            return fail("架构不是 AArch64（e_machine=" + machine + "）", "", "", "");
        }
        long shoff = u64(d, 0x28);
        int shent = u16(d, 0x3a);
        int shnum = u16(d, 0x3c);
        int shstr = u16(d, 0x3e);
        if (shoff < 0 || shent < 64 || shnum <= 0 || shnum > 512) {
            return fail("节头表异常（entsize=" + shent + " count=" + shnum + "）", "", "", "");
        }
        if (shoff + (long) shent * shnum > d.length) {
            return fail("ELF 被截断（节头表超出文件末尾）", "", "", "");
        }
        if (shstr >= shnum) {
            return fail("缺少节名字符串表（e_shstrndx=" + shstr + "）", "", "", "");
        }
        long strs = u64(d, (int) (shoff + (long) shstr * shent + 0x18));
        long strl = u64(d, (int) (shoff + (long) shstr * shent + 0x20));
        if (strs < 0 || strl < 0 || strs + strl > d.length) {
            return fail("节名字符串表损坏或超出文件", "", "", "");
        }

        String name = "";
        String vermagic = "";
        boolean haveModinfo = false;
        for (int i = 0; i < shnum; i++) {
            int base = (int) (shoff + (long) i * shent);
            long no = u32(d, base);
            if (no < 0 || no >= strl) {
                continue;
            }
            String sec = cstr(d, (int) (strs + no), (int) (strs + strl));
            if (!".modinfo".equals(sec)) {
                continue;
            }
            haveModinfo = true;
            long off = u64(d, base + 0x18);
            long size = u64(d, base + 0x20);
            if (off < 0 || size < 0 || off + size > d.length) {
                return fail("“.”modinfo 节损坏或超出文件", "", "", "");
            }
            int end = (int) (off + size);
            for (int p = (int) off; p < end; ) {
                int q = p;
                while (q < end && d[q] != 0) {
                    q++;
                }
                String kv = new String(d, p, q - p, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (kv.startsWith("name=") && name.isEmpty()) {
                    name = kv.substring(5);
                } else if (kv.startsWith("vermagic=") && vermagic.isEmpty()) {
                    vermagic = kv.substring(9);
                }
                p = q + 1;
            }
            break;
        }
        if (!haveModinfo) {
            return fail("缺少 .modinfo 节：不是内核模块", "", "", "");
        }
        if (name.isEmpty()) {
            return fail("缺少 name=：不是内核模块", "", "", "");
        }
        if (!name.startsWith("kernelsu")) {
            return fail("模块名 “" + name + "” 不是 KernelSU 系列（需要 kernelsu）", name, vermagic, "");
        }

        String release = kernelRelease(vermagic);
        if (release.isEmpty()) {
            return warn("模块缺少 vermagic，无法判断内核版本；仅适合你确认过的固件", name, vermagic, "");
        }
        int a = series(release);
        int b = series(deviceRelease);
        if (a < 0 || b < 0) {
            return warn("无法解析内核版本（模块 " + release + "，设备 " + deviceRelease + "）", name, vermagic, release);
        }
        if (a != b) {
            return fail("模块为 " + release + " 构建，设备内核为 " + deviceRelease
                    + "；主次版本不一致（" + fmtSeries(a) + " vs " + fmtSeries(b) + "）", name, vermagic, release);
        }
        if (!release.equals(deviceRelease)) {
            return warn("模块为 " + release + " 构建，设备内核为 " + deviceRelease
                    + "；同属 " + fmtSeries(a) + " 系列，加载器会重定位符号，但仍有失败风险", name, vermagic, release);
        }
        return new KoResult(OK, "兼容性检查通过", name, vermagic, release);
    }

    /** Kernel release part of a vermagic string ("6.18.32-4k-... SMP preempt" -> "6.18.32-4k-..."). */
    public static String kernelRelease(String vermagic) {
        if (vermagic == null) {
            return "";
        }
        String v = vermagic.trim();
        int sp = v.indexOf(' ');
        return sp < 0 ? v : v.substring(0, sp);
    }

    private static int series(String release) {
        if (release == null) {
            return -1;
        }
        String s = release.trim();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0 || i >= s.length() || s.charAt(i) != '.') {
            return -1;
        }
        int major = parseInt(s.substring(0, i));
        int j = i + 1;
        int k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) {
            k++;
        }
        if (k == j) {
            return -1;
        }
        return major * 1000 + parseInt(s.substring(j, k));
    }

    private static String fmtSeries(int s) {
        return (s / 1000) + "." + (s % 1000);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static KoResult fail(String why, String name, String vermagic, String release) {
        return new KoResult(FAIL, why, name, vermagic, release);
    }

    private static KoResult warn(String why, String name, String vermagic, String release) {
        return new KoResult(WARN, why, name, vermagic, release);
    }

    private static String cstr(byte[] d, int from, int to) {
        if (from < 0 || from >= d.length) {
            return "";
        }
        int end = from;
        while (end < to && end < d.length && d[end] != 0) {
            end++;
        }
        return new String(d, from, end - from, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] d, int o) {
        return ((long) (d[o] & 0xff))
                | ((long) (d[o + 1] & 0xff) << 8)
                | ((long) (d[o + 2] & 0xff) << 16)
                | ((long) (d[o + 3] & 0xff) << 24);
    }

    private static long u64(byte[] d, int o) {
        return u32(d, o) | (u32(d, o + 4) << 32);
    }
}
