package o3.st.root1;

public class PermProbe implements Runnable {
    private final Main main;

    public PermProbe(Main main) {
        this.main = main;
    }

    public void run() {
        String out = PayloadRunner.runShellSync(StateProbe.probeCommand());
        main.probeNow(out);
        if (out == null) {
            return;
        }
        String s = out.trim();
        if (s.length() == 0 || s.startsWith("ERR")) {
            return;
        }
        int nl = s.indexOf('\n');
        String first = (nl < 0 ? s : s.substring(0, nl)).trim();
        int cnt;
        try {
            cnt = Integer.parseInt(first);
        } catch (Throwable t) {
            return;
        }
        if (cnt == 0) {
            main.startAfterGrant();
        }
    }
}
