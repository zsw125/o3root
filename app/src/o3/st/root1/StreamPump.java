package o3.st.root1;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class StreamPump extends Thread {
    private final InputStream in;
    private final PayloadSink sink;

    public StreamPump(InputStream in, PayloadSink sink, String name) {
        super(name);
        this.in = in;
        this.sink = sink;
        setDaemon(true);
    }

    public void run() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                sink.onLine(l);
            }
        } catch (Throwable t) {
            sink.onLine("PUMP " + t);
        }
    }
}
