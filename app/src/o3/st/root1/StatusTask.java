package o3.st.root1;

public class StatusTask implements Runnable {
    private final Main main;
    private final int what;

    public StatusTask(Main main, int what) {
        this.main = main;
        this.what = what;
    }

    public void run() {
        main.statusNow(what);
    }
}
