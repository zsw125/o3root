package o3.st.root1;

public class ExitTask implements Runnable {
    private final Main main;
    private final int code;

    public ExitTask(Main main, int code) {
        this.main = main;
        this.code = code;
    }

    public void run() {
        main.exitNow(code);
    }
}
