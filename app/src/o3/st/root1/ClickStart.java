package o3.st.root1;

import android.view.View;

public class ClickStart implements View.OnClickListener {
    private final Main main;

    public ClickStart(Main main) {
        this.main = main;
    }

    public void onClick(View v) {
        main.onStartClicked();
    }
}
