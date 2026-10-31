package o3.st.root1;

import android.app.Activity;
import android.view.View;

/**
 * Hidden console entry: long-press the version line under the title.
 * The stable build ships without the console classes, so the gate is looked up
 * reflectively; if it is absent (CONSOLE=false) nothing happens.
 */
public class ConsoleEntry implements View.OnLongClickListener {
    private final Activity activity;

    public ConsoleEntry(Activity activity) {
        this.activity = activity;
    }

    public boolean onLongClick(View v) {
        try {
            Class<?> gate = Class.forName("o3.st.root1.ConsoleGate");
            gate.getMethod("start", Activity.class).invoke(null, activity);
        } catch (Throwable ignored) {
        }
        return true;
    }
}
