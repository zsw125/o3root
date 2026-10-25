package o3.st.root1;

import rikka.shizuku.Shizuku;

public class StateProbe implements Runnable {
    private final Main main;

    public StateProbe(Main main) {
        this.main = main;
    }

    static String probeCommand() {
        return "grep -c kernelsu /proc/modules 2>/dev/null; getenforce 2>/dev/null; "
                + "pm path me.weishu.kernelsu >/dev/null 2>&1 && echo tiann; "
                + "pm path com.rifsxd.ksunext >/dev/null 2>&1 && echo next; "
                + "pm path com.sukisu.ultra >/dev/null 2>&1 && echo sukisu; true";
    }

    public void run() {
        try {
            if (Shizuku.checkSelfPermission() != 0) {
                main.probeNoPerm();
                return;
            }
        } catch (Throwable t) {
            main.probeNoPerm();
            return;
        }
        String out = PayloadRunner.runShellSync(probeCommand());
        main.probeNow(out);
    }
}
