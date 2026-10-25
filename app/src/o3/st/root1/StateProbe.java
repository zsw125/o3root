package o3.st.root1;

public class StateProbe implements Runnable {
    private final Main main;

    public StateProbe(Main main) {
        this.main = main;
    }

    public void run() {
        String out = PayloadRunner.runShellSync(
                "grep -c kernelsu /proc/modules 2>/dev/null; getenforce 2>/dev/null; (pm path me.weishu.kernelsu >/dev/null 2>&1 && echo 1 || echo 0)");
        main.probeNow(out);
    }
}
