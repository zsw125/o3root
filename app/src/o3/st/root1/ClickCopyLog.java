package o3.st.root1;

import android.view.View;

public class ClickCopyLog implements View.OnClickListener {
    private final Main main;

    public ClickCopyLog(Main main) {
        this.main = main;
    }

    public void onClick(View v) {
        main.copyLog();
    }
}
