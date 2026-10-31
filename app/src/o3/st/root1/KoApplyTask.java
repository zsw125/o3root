package o3.st.root1;

/** Delivers a completed .ko read/validation back to the UI thread. */
public class KoApplyTask implements Runnable {
    private final Main main;
    private final String label;
    private final byte[] data;
    private final String err;
    private final KoResult result;

    public KoApplyTask(Main main, String label, byte[] data, String err, KoResult result) {
        this.main = main;
        this.label = label;
        this.data = data;
        this.err = err;
        this.result = result;
    }

    public void run() {
        main.applyKoRead(label, data, err, result);
    }
}
