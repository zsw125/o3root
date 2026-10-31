package o3.st.root1;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Reads the user-selected document off the UI thread and validates it. */
public class KoReadTask implements Runnable {
    private final Main main;
    private final Uri uri;
    private final String label;

    public KoReadTask(Main main, Uri uri, String label) {
        this.main = main;
        this.uri = uri;
        this.label = label;
    }

    public void run() {
        byte[] data = null;
        String err = null;
        try {
            InputStream in = main.getContentResolver().openInputStream(uri);
            if (in == null) {
                err = "无法打开所选文件";
            } else {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > CustomKo.MAX_BYTES) {
                        err = "文件超过 8 MB，拒绝读取";
                        break;
                    }
                    bos.write(buf, 0, n);
                }
                in.close();
                if (err == null) {
                    data = bos.toByteArray();
                }
            }
        } catch (Throwable t) {
            err = t.toString();
        }
        KoResult r = null;
        if (err == null && data != null) {
            r = CustomKo.validate(data, System.getProperty("os.version", ""));
        }
        main.postKoRead(label, data, err, r);
    }
}
