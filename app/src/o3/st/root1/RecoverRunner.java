package o3.st.root1;

public class RecoverRunner implements Runnable {
    private final Main main;

    public RecoverRunner(Main main) {
        this.main = main;
    }

    public void run() {
        String out = PayloadRunner.runShellSync(
                "timeout 12 su -c 'setenforce 0' 2>&1; echo ---; getenforce 2>&1");
        main.recoverDone(out);
    }
}
