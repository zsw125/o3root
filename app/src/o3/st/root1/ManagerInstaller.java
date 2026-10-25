package o3.st.root1;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.InputStream;
import java.io.OutputStream;

public class ManagerInstaller implements Runnable {
    private final Main main;

    public ManagerInstaller(Main main) {
        this.main = main;
    }

    public void run() {
        Context ctx = main.getApplicationContext();
        try {
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            InputStream in = ctx.getAssets().open("ksu-manager.apk");
            OutputStream out = s.openWrite("base.apk", 0, -1);
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            out.close();
            in.close();
            Intent cb = new Intent(ctx, InstallReceiver.class);
            cb.setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pit = PendingIntent.getBroadcast(ctx, 0, cb, flags);
            s.commit(pit.getIntentSender());
            s.close();
            main.installLog("session " + id + " wrote " + total + " bytes");
        } catch (Throwable t) {
            main.installLog("fail " + t);
        }
    }
}
