package o3.st.root1;

import android.view.View;

public class ClickExport implements View.OnClickListener {
    private final Main main;

    public ClickExport(Main m) {
        this.main = m;
    }

    public void onClick(View v) {
        main.exportLog();
    }
}
