package o3.st.root1;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public class PayloadRunner implements Runnable {
    private final String payloadPath;
    private final PayloadSink sink;
    private final String fork;
    private final byte[] customKo;
    private final String customKoLabel;
    private final String customKoExtPath;
    private final boolean customKoForce;
    private final String selinuxProfile;

    public PayloadRunner(String payloadPath, PayloadSink sink) {
        this(payloadPath, sink, null, null, null, null, false);
    }

    public PayloadRunner(String payloadPath, PayloadSink sink, String fork) {
        this(payloadPath, sink, fork, null, null, null, false);
    }

    public PayloadRunner(String payloadPath, PayloadSink sink, String fork,
                         byte[] customKo, String customKoLabel, String customKoExtPath,
                         boolean customKoForce) {
        this(payloadPath, sink, fork, customKo, customKoLabel, customKoExtPath,
                customKoForce, null);
    }

    public PayloadRunner(String payloadPath, PayloadSink sink, String fork,
                         byte[] customKo, String customKoLabel, String customKoExtPath,
                         boolean customKoForce, String selinuxProfile) {
        this.payloadPath = payloadPath;
        this.sink = sink;
        this.fork = fork;
        this.customKo = customKo;
        this.customKoLabel = customKoLabel;
        this.customKoExtPath = customKoExtPath;
        this.customKoForce = customKoForce;
        this.selinuxProfile = selinuxProfile;
    }

    public void run() {
        try {
            String env = fork == null ? "" : ("export O3P_KSU_FORK=" + fork + "; ");
            if (selinuxProfile != null && selinuxProfile.length() > 0) {
                env = env + "export O3P_SELINUX_PROFILE='" + selinuxProfile + "'; ";
                if ("o1".equals(selinuxProfile)) {
                    /* O1: sys_call_table is in the EL2-protected "Kernel
                       code" range, so the KernelSU LKM's text patch cannot
                       land; its sys_enter tracepoint hook would then turn
                       execve/newfstatat into -ENOSYS for pid1/zygote/uid2000.
                       Skip the built-in LKM and use @o3p_rootd instead. */
                    env = env + "export O3P_NOKSU=1; ";
                }
            }
            String customPath = null;
            if (customKo != null && customKo.length > 0) {
                customPath = stageCustomKo(customKo, customKoLabel, customKoExtPath);
                if (customPath != null) {
                    env = env + "export O3P_CUSTOM_KO='" + customPath + "'; ";
                    if (customKoForce) {
                        env = env + "export O3P_CUSTOM_KO_FORCE=1; ";
                    }
                } else {
                    sink.onLine("custom ko: 未能推送到设备，本次使用自适应内置模块");
                }
            }
            String bin = "/data/local/tmp/o3p_" + System.currentTimeMillis() + ".bin";
            String cmd = env + "cp '" + payloadPath + "' " + bin
                    + " && chmod 755 " + bin
                    + " && cd /data/local/tmp"
                    + " && " + bin + " 10000 14000 300 1 6 1 8000 800";
            sink.onLine("payload=" + payloadPath + " fork=" + (fork == null ? "auto" : fork)
                    + (customPath == null ? "" : " custom-ko=" + customPath));
            Process p = newProcess(new String[]{"/system/bin/sh", "-c", cmd},
                    new String[]{}, "/data/local/tmp");
            StreamPump out = new StreamPump(p.getInputStream(), sink, "payload-out");
            StreamPump err = new StreamPump(p.getErrorStream(), sink, "payload-err");
            out.start();
            err.start();
            int code = p.waitFor();
            out.join(3000);
            err.join(3000);
            sink.onExit(code);
        } catch (Throwable t) {
            sink.onLine("RUNNER " + t);
            sink.onExit(-1);
        }
    }

    /* Copy the chosen .ko to /data/local/tmp so the payload can read it.
       Two independent paths: the world-readable external copy is copied by the
       Shizuku shell; if that fails the bytes are piped over stdin. */
    private String stageCustomKo(byte[] data, String label, String extPath) {
        String path = "/data/local/tmp/o3p_custom_" + System.currentTimeMillis() + ".ko";
        try {
            if (extPath != null) {
                String cmd = "rm -f /data/local/tmp/o3p_custom_*.ko 2>/dev/null; "
                        + "cp '" + extPath + "' '" + path + "' 2>/dev/null; "
                        + "chmod 644 '" + path + "' 2>/dev/null; "
                        + "wc -c < '" + path + "' 2>/dev/null";
                long size = sizeOf(runShellSync(cmd));
                if (size == data.length) {
                    sink.onLine("custom ko -> " + path + " (" + data.length + " B, "
                            + (label == null ? "?" : label) + ")");
                    return path;
                }
            }
            String cmd = "rm -f /data/local/tmp/o3p_custom_*.ko 2>/dev/null; "
                    + "cat > '" + path + "'; chmod 644 '" + path + "'; wc -c < '" + path + "'";
            Process p = newProcess(new String[]{"/system/bin/sh", "-c", cmd},
                    new String[]{}, "/data/local/tmp");
            OutputStream in = p.getOutputStream();
            in.write(data);
            in.flush();
            in.close();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            int code = p.waitFor();
            long size = sizeOf(new String(bos.toByteArray(), "UTF-8"));
            if (code == 0 && size == data.length) {
                sink.onLine("custom ko -> " + path + " (" + data.length + " B, stdin fallback)");
                return path;
            }
            sink.onLine("custom ko: 推送失败 rc=" + code + " size=" + size);
            return null;
        } catch (Throwable t) {
            sink.onLine("custom ko: 推送失败 " + t);
            return null;
        }
    }

    private static long sizeOf(String out) {
        if (out == null) {
            return -1;
        }
        String digits = out.trim().replaceAll("[^0-9]", "");
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static Process newProcess(String[] cmd, String[] env, String dir) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        Object o = m.invoke(null, cmd, env, dir);
        return (Process) o;
    }

    public static String runShellSync(String cmd) {
        try {
            Process p = newProcess(new String[]{"/system/bin/sh", "-c", cmd},
                    new String[]{}, "/data/local/tmp");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            p.waitFor();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            return "ERR " + t.getClass().getSimpleName() + ": " + c;
        }
    }
}
