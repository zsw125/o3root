package o3.st.root1;

public class ProbeTask implements Runnable {
    private final Main main;
    private final String out;

    public ProbeTask(Main main, String out) {
        this.main = main;
        this.out = out;
    }

    public void run() {
        main.probeApply(out);
    }
}
