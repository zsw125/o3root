package o3.st.root1;

import android.view.View;

/** Long-press on the module row clears a user-selected .ko and returns to adaptive mode. */
public class LongClearKo implements View.OnLongClickListener {
    private final Main main;

    public LongClearKo(Main main) {
        this.main = main;
    }

    public boolean onLongClick(View v) {
        main.clearKoFromLongPress();
        return true;
    }
}
