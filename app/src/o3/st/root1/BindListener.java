package o3.st.root1;

import rikka.shizuku.Shizuku;

public class BindListener implements Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener {
    private final Main main;

    public BindListener(Main main) {
        this.main = main;
    }

    public void onBinderReceived() {
        main.uiBinder(true);
    }

    public void onBinderDead() {
        main.uiBinder(false);
    }
}
