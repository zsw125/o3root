package o3.st.root1;

public class BinderTask implements Runnable {
    private final Main main;
    private final boolean alive;

    public BinderTask(Main main, boolean alive) {
        this.main = main;
        this.alive = alive;
    }

    public void run() {
        main.binderNow(alive);
    }
}
