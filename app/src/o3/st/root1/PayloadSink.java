package o3.st.root1;

public interface PayloadSink {
    void onLine(String line);
    void onExit(int code);
}
