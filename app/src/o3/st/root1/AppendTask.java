package o3.st.root1;

public class AppendTask implements Runnable {
    private final Main main;
    private final String line;

    public AppendTask(Main main, String line) {
        this.main = main;
        this.line = line;
    }

    public void run() {
        main.writeFile(line);
        main.appendNow(line);
    }
}
