package o3.st.root1;

public class DelayLaunch implements Runnable {
    private final Main main;
    private final int delayMs;

    public DelayLaunch(Main main, int delayMs) {
        this.main = main;
        this.delayMs = delayMs;
    }

    public void run() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        main.launchManager();
    }
}
