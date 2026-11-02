package o3.st.root1;

import android.content.DialogInterface;
import android.widget.Button;
import android.widget.CompoundButton;

/** Two-step confirmation shown before any payload run on an O1 device. */
class O1WarnNext implements DialogInterface.OnClickListener {
    private final Main main;

    O1WarnNext(Main main) {
        this.main = main;
    }

    public void onClick(DialogInterface d, int which) {
        main.showO1Checkbox();
    }
}

class O1WarnCheck implements CompoundButton.OnCheckedChangeListener {
    private final Button button;

    O1WarnCheck(Button button) {
        this.button = button;
    }

    public void onCheckedChanged(CompoundButton b, boolean checked) {
        button.setEnabled(checked);
    }
}

class O1WarnStart implements DialogInterface.OnClickListener {
    private final Main main;

    O1WarnStart(Main main) {
        this.main = main;
    }

    public void onClick(DialogInterface d, int which) {
        main.startAfterO1Warning();
    }
}
