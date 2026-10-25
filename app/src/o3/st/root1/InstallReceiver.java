package o3.st.root1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public class InstallReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        Main m = Main.instance();
        if (m == null) {
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent it = i.getParcelableExtra(Intent.EXTRA_INTENT);
            m.installPrompt(it);
            return;
        }
        m.installResult(status, (msg == null ? "" : msg) + " pkg=" + (pkg == null ? "?" : pkg));
    }
}
