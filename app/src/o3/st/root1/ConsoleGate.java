package o3.st.root1;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Three-step, deliberately slow confirmation gate in front of the root console.
 * Every step is non-cancelable; the last step requires typing a fixed phrase.
 * A one-shot token is handed to ConsoleActivity so the activity cannot be
 * started by an external intent even if it were exported by mistake.
 */
public class ConsoleGate {
    static final String PHRASE = "I ACCEPT ROOT RISK";
    static final String EXTRA_TOKEN = "console_token";

    static String token;

    public static void start(Activity a) {
        step1(a);
    }

    private static void step1(final Activity a) {
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("root 控制台（测试版）");
        b.setMessage("root 控制台会以 root 身份执行你输入的任意命令。\n\n"
                + "• 可以读写系统分区，绕过 SELinux 和 Android 权限模型\n"
                + "• 一条错误命令就可能损坏系统、丢失数据，甚至让设备无法启动\n"
                + "• root 只在本次开机内有效，重启后需要重新运行载荷\n\n"
                + "继续前，请确认你知道如何恢复这台设备。");
        b.setCancelable(false);
        b.setPositiveButton("我已知悉风险", new GateNext(a, 2));
        b.setNegativeButton("取消", null);
        b.show();
    }

    static void step2(final Activity a) {
        final CheckBox cb = new CheckBox(a);
        cb.setText("我已理解：root 命令可能损坏系统、丢失数据甚至需要刷机，"
                + "全部后果由我自己承担。");
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 4), p, 0);
        box.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("再次确认");
        b.setView(box);
        b.setCancelable(false);
        b.setPositiveButton("继续", new GateProceed(a, cb));
        b.setNegativeButton("取消", null);
        AlertDialog d = b.show();
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        pos.setEnabled(false);
        cb.setOnCheckedChangeListener(new GateCheck(pos));
    }

    static void step3(final Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 4), p, 0);

        TextView say = new TextView(a);
        say.setText("这是最后一次确认。输入下面的短语（区分大小写）：");
        box.addView(say);

        TextView phrase = new TextView(a);
        phrase.setText(PHRASE);
        phrase.setTypeface(Typeface.MONOSPACE);
        phrase.setTextSize(16);
        phrase.setPadding(0, dp(a, 8), 0, dp(a, 8));
        box.addView(phrase);

        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setHint(PHRASE);
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(et);

        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("最后确认：root 控制台");
        b.setView(box);
        b.setCancelable(false);
        b.setPositiveButton("启动控制台", new GateLaunch(a));
        b.setNegativeButton("取消", null);
        AlertDialog d = b.show();
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        pos.setEnabled(false);
        et.addTextChangedListener(new GateTypeWatcher(pos));
    }

    static void launch(Activity a) {
        token = Long.toHexString(System.nanoTime());
        Intent i = new Intent(a, ConsoleActivity.class);
        i.putExtra(EXTRA_TOKEN, token);
        a.startActivity(i);
    }

    private static int dp(Activity a, int v) {
        return (int) (a.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private ConsoleGate() {
    }
}

class GateNext implements DialogInterface.OnClickListener {
    private final Activity activity;
    private final int step;

    GateNext(Activity a, int step) {
        this.activity = a;
        this.step = step;
    }

    public void onClick(DialogInterface d, int which) {
        if (step == 2) {
            ConsoleGate.step2(activity);
        } else {
            ConsoleGate.step3(activity);
        }
    }
}

class GateCheck implements CompoundButton.OnCheckedChangeListener {
    private final Button button;

    GateCheck(Button button) {
        this.button = button;
    }

    public void onCheckedChanged(CompoundButton b, boolean checked) {
        button.setEnabled(checked);
    }
}

class GateProceed implements DialogInterface.OnClickListener {
    private final Activity activity;
    private final CheckBox check;

    GateProceed(Activity a, CheckBox check) {
        this.activity = a;
        this.check = check;
    }

    public void onClick(DialogInterface d, int which) {
        if (check.isChecked()) {
            ConsoleGate.step3(activity);
        }
    }
}

class GateTypeWatcher implements TextWatcher {
    private final Button button;

    GateTypeWatcher(Button button) {
        this.button = button;
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    public void afterTextChanged(Editable s) {
        button.setEnabled(ConsoleGate.PHRASE.contentEquals(s));
    }
}

class GateLaunch implements DialogInterface.OnClickListener {
    private final Activity activity;

    GateLaunch(Activity a) {
        this.activity = a;
    }

    public void onClick(DialogInterface d, int which) {
        ConsoleGate.launch(activity);
    }
}
