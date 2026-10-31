package o3.st.root1;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStreamReader;

/**
 * Test-build only root console. Commands are piped through the payload's
 * rootd client mode: Shizuku spawns o3p_cli.bin as uid 2000, rootd accepts
 * uid 2000 and runs the command as root.
 */
public class ConsoleActivity extends Activity {

    private int BG, CARD, CARD_EDGE, TEXT, MUTED, ACCENT, WARN, LOG, LOG_BG, LOG_EDGE, BTN_TEXT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder outBuf = new StringBuilder();
    private TextView outView;
    private TextView statusView;
    private ScrollView scroll;
    private EditText input;
    private Button runBtn;
    private boolean busy;
    private boolean capped;
    private String payloadPath;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String want = getIntent().getStringExtra(ConsoleGate.EXTRA_TOKEN);
        if (want == null || ConsoleGate.token == null || !want.equals(ConsoleGate.token)) {
            finish();
            return;
        }
        applyPalette();
        payloadPath = getApplicationInfo().nativeLibraryDir + "/libo3p.so";
        setContentView(buildUi());
        append("root 控制台 · 测试版\n"
                + "命令由 rootd 以 root 身份执行，只在本次开机内有效。\n"
                + "输入命令后按“执行”；输出可选择、可复制。\n");
    }

    private void applyPalette() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            BG = 0xFF0B0E13; CARD = 0xFF151A22; CARD_EDGE = 0xFF232B36;
            TEXT = 0xFFE8EEF6; MUTED = 0xFF8894A3;
            ACCENT = 0xFF34D399; WARN = 0xFFF5B942;
            LOG = 0xFFB9C6D4; LOG_BG = 0xFF10151C; LOG_EDGE = 0xFF1D2530;
            BTN_TEXT = 0xFF07130E;
        } else {
            BG = 0xFFF5F7FA; CARD = 0xFFFFFFFF; CARD_EDGE = 0xFFDCE3EC;
            TEXT = 0xFF11161D; MUTED = 0xFF5B6675;
            ACCENT = 0xFF0E9F6E; WARN = 0xFFB45309;
            LOG = 0xFF2A3440; LOG_BG = 0xFFF1F4F9; LOG_EDGE = 0xFFDCE3EC;
            BTN_TEXT = 0xFFFFFFFF;
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("root 控制台", 20, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("关闭", 12, ACCENT, false);
        close.setPadding(dp(10), dp(4), 0, dp(4));
        close.setOnClickListener(new ConsoleClose(this));
        head.addView(close);
        root.addView(head);

        TextView warn = text("测试版 · 命令以 root 执行 · 可能损坏系统或数据", 11, WARN, false);
        warn.setPadding(0, dp(6), 0, 0);
        root.addView(warn);

        scroll = new ScrollView(this);
        scroll.setBackground(roundStroked(LOG_BG, 12, LOG_EDGE));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(10);
        root.addView(scroll, slp);

        outView = new TextView(this);
        outView.setTextSize(9);
        outView.setTextColor(LOG);
        outView.setTypeface(Typeface.MONOSPACE);
        outView.setPadding(dp(10), dp(10), dp(10), dp(10));
        outView.setTextIsSelectable(true);
        scroll.addView(outView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        root.addView(row, rlp);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入命令（例如 id）");
        input.setTextSize(12);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(roundStroked(CARD, 10, CARD_EDGE));
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        input.setOnEditorActionListener(new ConsoleEnter(this));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(input, ilp);

        runBtn = new Button(this);
        runBtn.setText("执行");
        runBtn.setTextSize(12);
        runBtn.setTextColor(BTN_TEXT);
        runBtn.setBackground(round(ACCENT, 9));
        runBtn.setPadding(dp(18), 0, dp(18), 0);
        runBtn.setOnClickListener(new ConsoleSend(this));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        row.addView(runBtn, blp);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        root.addView(foot, flp);

        TextView copy = text("复制全部", 11, ACCENT, false);
        copy.setPadding(0, dp(4), dp(16), dp(4));
        copy.setOnClickListener(new ConsoleCopy(this));
        foot.addView(copy);

        TextView clear = text("清空", 11, ACCENT, false);
        clear.setPadding(0, dp(4), 0, dp(4));
        clear.setOnClickListener(new ConsoleClear(this));
        foot.addView(clear);

        statusView = text("就绪", 10, MUTED, false);
        statusView.setGravity(Gravity.END);
        foot.addView(statusView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return root;
    }

    void send() {
        if (busy) {
            return;
        }
        String cmd = input.getText().toString().trim();
        if (cmd.length() == 0) {
            return;
        }
        if (cmd.length() > 900) {
            append("命令过长：rootd 单条命令上限约 1 KB\n");
            return;
        }
        input.setText("");
        append("$ " + cmd + "\n");
        busy = true;
        runBtn.setEnabled(false);
        statusView.setText("执行中…");
        new Thread(new ConsoleTask(this, cmd), "console").start();
    }

    /** Runs off the UI thread (called by ConsoleTask). */
    void exec(String cmd) {
        StringBuilder raw = new StringBuilder();
        int rc = -1;
        try {
            String bin = "/data/local/tmp/o3p_cli.bin";
            String sh = "cp '" + payloadPath + "' '" + bin + "' 2>&1"
                    + " && chmod 755 '" + bin + "' 2>&1"
                    + " && '" + bin + "' --shell " + shQuote(cmd) + " 2>&1";
            Process p = PayloadRunner.newProcess(
                    new String[]{"/system/bin/sh", "-c", sh},
                    new String[]{}, "/data/local/tmp");
            InputStreamReader r = new InputStreamReader(p.getInputStream(), "UTF-8");
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) > 0) {
                String s = new String(buf, 0, n);
                raw.append(s);
                ui.post(new ConsoleChunk(this, s));
            }
            rc = p.waitFor();
        } catch (Throwable t) {
            raw.append("EXEC ").append(t).append('\n');
        }
        ui.post(new ConsoleDone(this, raw.toString(), rc));
    }

    void chunk(String s) {
        if (capped) {
            return;
        }
        if (outBuf.length() > 400000) {
            capped = true;
            outBuf.append("\n[输出过多，已停止显示]\n");
            outView.setText(outBuf);
            return;
        }
        outBuf.append(s);
        outView.setText(outBuf);
        scroll.post(new ConsoleScroll(this));
    }

    void done(String raw, int rc) {
        busy = false;
        runBtn.setEnabled(true);
        statusView.setText("就绪");
        String body = raw;
        int marker = raw.lastIndexOf("__EXIT=");
        int exit = Integer.MIN_VALUE;
        if (marker >= 0) {
            String tail = raw.substring(marker + 7).trim();
            int end = 0;
            while (end < tail.length()) {
                char ch = tail.charAt(end);
                if ((ch >= '0' && ch <= '9') || (end == 0 && ch == '-')) {
                    end++;
                } else {
                    break;
                }
            }
            if (end > 0) {
                try {
                    exit = Integer.parseInt(tail.substring(0, end));
                } catch (Throwable ignored) {
                }
                body = raw.substring(0, marker);
                if (body.endsWith("\n")) {
                    body = body.substring(0, body.length() - 1);
                }
            }
        }
        outBuf.setLength(0);
        outBuf.append(body);
        if (exit != Integer.MIN_VALUE) {
            outBuf.append("\n[exit ").append(exit).append("]");
        } else if (rc >= 0) {
            outBuf.append("\n[transport exit ").append(rc).append("]");
        }
        if (body.contains("CLIENT connect fail")) {
            outBuf.append("\n\nrootd 未运行：请回到主页执行一次载荷（rootd 只在本次开机内有效）。");
        }
        outView.setText(outBuf);
        scroll.fullScroll(ScrollView.FOCUS_DOWN);
    }

    void append(String s) {
        outBuf.append(s);
        outView.setText(outBuf);
        scroll.fullScroll(ScrollView.FOCUS_DOWN);
    }

    void copyAll() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("O3 root console", outBuf.toString()));
            Toast.makeText(this, "控制台输出已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    void clear() {
        if (busy) {
            return;
        }
        outBuf.setLength(0);
        capped = false;
        outView.setText("");
        scroll.fullScroll(ScrollView.FOCUS_DOWN);
    }

    void scrollIfNearBottom() {
        if (outView.getHeight() <= scroll.getHeight() + dp(24)) {
            scroll.fullScroll(ScrollView.FOCUS_DOWN);
            return;
        }
        int y = scroll.getScrollY();
        if (y + scroll.getHeight() >= outView.getBottom() - dp(24)) {
            scroll.fullScroll(ScrollView.FOCUS_DOWN);
        }
    }

    static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable roundStroked(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = round(color, radiusDp);
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }
}

class ConsoleTask implements Runnable {
    private final ConsoleActivity activity;
    private final String cmd;

    ConsoleTask(ConsoleActivity a, String cmd) {
        this.activity = a;
        this.cmd = cmd;
    }

    public void run() {
        activity.exec(cmd);
    }
}

class ConsoleSend implements View.OnClickListener {
    private final ConsoleActivity activity;

    ConsoleSend(ConsoleActivity a) {
        this.activity = a;
    }

    public void onClick(View v) {
        activity.send();
    }
}

class ConsoleClose implements View.OnClickListener {
    private final ConsoleActivity activity;

    ConsoleClose(ConsoleActivity a) {
        this.activity = a;
    }

    public void onClick(View v) {
        activity.finish();
    }
}

class ConsoleCopy implements View.OnClickListener {
    private final ConsoleActivity activity;

    ConsoleCopy(ConsoleActivity a) {
        this.activity = a;
    }

    public void onClick(View v) {
        activity.copyAll();
    }
}

class ConsoleClear implements View.OnClickListener {
    private final ConsoleActivity activity;

    ConsoleClear(ConsoleActivity a) {
        this.activity = a;
    }

    public void onClick(View v) {
        activity.clear();
    }
}

class ConsoleEnter implements TextView.OnEditorActionListener {
    private final ConsoleActivity activity;

    ConsoleEnter(ConsoleActivity a) {
        this.activity = a;
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_GO
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN)) {
            activity.send();
            return true;
        }
        return false;
    }
}

class ConsoleChunk implements Runnable {
    private final ConsoleActivity activity;
    private final String text;

    ConsoleChunk(ConsoleActivity a, String text) {
        this.activity = a;
        this.text = text;
    }

    public void run() {
        activity.chunk(text);
    }
}

class ConsoleDone implements Runnable {
    private final ConsoleActivity activity;
    private final String raw;
    private final int code;

    ConsoleDone(ConsoleActivity a, String raw, int code) {
        this.activity = a;
        this.raw = raw;
        this.code = code;
    }

    public void run() {
        activity.done(raw, code);
    }
}

class ConsoleScroll implements Runnable {
    private final ConsoleActivity activity;

    ConsoleScroll(ConsoleActivity a) {
        this.activity = a;
    }

    public void run() {
        activity.scrollIfNearBottom();
    }
}
