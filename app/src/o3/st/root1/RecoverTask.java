package o3.st.root1;

public class RecoverTask implements Runnable {
    private final Main main;
    private final String out;

    public RecoverTask(Main main, String out) {
        this.main = main;
        this.out = out;
    }

    public void run() {
        main.recoverApply(out);
    }
}
