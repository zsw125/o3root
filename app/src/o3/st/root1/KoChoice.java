package o3.st.root1;

import android.content.DialogInterface;

/** Dialog button callback for the custom-.ko fallback question. */
public class KoChoice implements DialogInterface.OnClickListener {
    public static final int FALLBACK = 0;
    public static final int FORCE = 1;
    public static final int CANCEL = 2;

    private final Main main;
    private final int action;

    public KoChoice(Main main, int action) {
        this.main = main;
        this.action = action;
    }

    public void onClick(DialogInterface dialog, int which) {
        main.koDialogChoice(action);
    }
}
