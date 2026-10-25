package o3.st.root1;

import rikka.shizuku.Shizuku;

public class PermListener implements Shizuku.OnRequestPermissionResultListener {
    private final Main main;

    public PermListener(Main main) {
        this.main = main;
    }

    public void onRequestPermissionResult(int requestCode, int grantResult) {
        main.uiPermission(grantResult);
    }
}
