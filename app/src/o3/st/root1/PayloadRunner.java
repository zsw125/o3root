package o3.st.root1;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public class PayloadRunner implements Runnable {
    private final String payloadPath;
    private final PayloadSink sink;
    private final String fork;

    public PayloadRunner(String payloadPath, PayloadSink sink) {
        this(payloadPath, sink, null);
    }

    public PayloadRunner(String payloadPath, PayloadSink sink, String fork) {
        this.payloadPath = payloadPath;
        this.sink = sink;
        this.fork = fork;
    }

    public void run() {
        try {
            String env = fork == null ? "" : ("export O3P_KSU_FORK=" + fork + "; ");
            String bin = "/data/local/tmp/o3p_" + System.currentTimeMillis() + ".bin";
            String cmd = env + "cp '" + payloadPath + "' " + bin
                    + " && chmod 755 " + bin
                    + " && cd /data/local/tmp"
                    + " && " + bin + " 10000 14000 300 1 6 1 8000 800";
            sink.onLine("payload=" + payloadPath + " fork=" + (fork == null ? "auto" : fork));
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

    private static Process newProcess(String[] cmd, String[] env, String dir) throws Exception {
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
