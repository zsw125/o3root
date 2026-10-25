package o3.st.root1;

public class ScrollBottom implements Runnable {
    private final Main main;

    public ScrollBottom(Main main) {
        this.main = main;
    }

    public void run() {
        main.scrollBottomNow();
    }
}
