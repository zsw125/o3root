package o3.st.root1;

public class PermTask implements Runnable {
    private final Main main;
    private final int grant;

    public PermTask(Main main, int grant) {
        this.main = main;
        this.grant = grant;
    }

    public void run() {
        main.permNow(grant);
    }
}
