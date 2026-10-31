package o3.st.root1;

import android.view.View;

public class ClickPickKo implements View.OnClickListener {
    private final Main main;

    public ClickPickKo(Main main) {
        this.main = main;
    }

    public void onClick(View v) {
        main.pickKo();
    }
}
