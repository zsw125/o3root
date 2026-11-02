package o3.st.root1;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import rikka.shizuku.Shizuku;

public class Main extends Activity implements PayloadSink {

    // palette, filled by applyPalette() from the current system night mode
    private int BG, CARD, CARD_EDGE, TEXT, MUTED, ACCENT, WARN, LOG, OK, BAD;
    private int DEV, LOG_BG, LOG_EDGE, BTN_TEXT;

    private LinearLayout rootView;
    private LinearLayout cardView;
    private TextView titleView, subView, devView, warnView, logTitleView;
    private boolean binderAlive;
    private boolean lastNight;

    private TextView tvShizuku;
    private TextView tvSelinux;
    private TextView tvKsu;
    private TextView logView;
    private ScrollView logScroll;
    private Button btn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private final SpannableStringBuilder logSpan = new SpannableStringBuilder();
    private String payloadPath;
    private TextView koView;
    private byte[] customKoBytes;
    private String customKoLabel;
    private String customKoExtPath;
    private KoResult customKoCheck;
    private boolean customKoForced;
    private boolean running;
    private boolean rootOk;
    private boolean ksuOk;
    private String runBootId;
    private boolean fwOk;
    private boolean mgrOk;
    private String mgrFork;
    private String mgrPkg;
    private boolean netRisk;
    private boolean netRiskShown;
    private boolean o1Profile;

    static final String EXPECT_FP =
            "Xiaomi/yingtian/yingtian:17/CP2A.260605.016/OS4.0.16.0.XBMCNXM:user/release-keys";
    static final String EXPECT_KERNEL =
            "6.18.21-android17-5-g805aa6adea19-abogki565292102-4k";
    /* Experimental O1 (XRING) profile: one shared kernel for dijun/jinghu/violin. */
    static final String O1_KERNEL =
            "6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k";
    static final String[] O1_DEVICES = {"dijun", "jinghu", "violin"};
    static final String WARN_DEFAULT =
            "需要 Shizuku 以 shell 模式运行（无线调试启动，无需电脑）";

    private static final int REQ_KO = 1002;
    static final String KO_FILE = "custom-ko.ko";
    static final String KO_META = "custom-ko.meta";

    private static Main INSTANCE;

    static Main instance() {
        return INSTANCE;
    }

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        INSTANCE = this;
        payloadPath = getApplicationInfo().nativeLibraryDir + "/libo3p.so";
        applyPalette();
        lastNight = isNight();
        setContentView(buildUi());
        applyWindowTheme();
        append("O3 Root v" + BuildInfo.VERSION + " · CVE-2026-98260");
        append("payload=" + payloadPath);
        restoreCustomKo();
        checkFirmware();
        Shizuku.addBinderReceivedListenerSticky(new BindListener(this));
        Shizuku.addBinderDeadListener(new BindListener(this));
        Shizuku.addRequestPermissionResultListener(new PermListener(this));
        uiBinder(Shizuku.pingBinder());
    }

    protected void onResume() {
        super.onResume();
        if (!running) {
            adoptManager(detectManager());
            probeState();
        }
    }

    private boolean isNight() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyPalette() {
        if (isNight()) {
            BG = 0xFF0B0E13; CARD = 0xFF151A22; CARD_EDGE = 0xFF232B36;
            TEXT = 0xFFE8EEF6; MUTED = 0xFF8894A3; DEV = 0xFF66727F;
            ACCENT = 0xFF34D399; WARN = 0xFFF5B942;
            LOG = 0xFFB9C6D4; LOG_BG = 0xFF10151C; LOG_EDGE = 0xFF1D2530;
            BTN_TEXT = 0xFF07130E; OK = 0xFF34D399; BAD = 0xFFF26D6D;
        } else {
            BG = 0xFFF5F7FA; CARD = 0xFFFFFFFF; CARD_EDGE = 0xFFDCE3EC;
            TEXT = 0xFF11161D; MUTED = 0xFF5B6675; DEV = 0xFF7A8796;
            ACCENT = 0xFF0E9F6E; WARN = 0xFFB45309;
            LOG = 0xFF2A3440; LOG_BG = 0xFFF1F4F9; LOG_EDGE = 0xFFDCE3EC;
            BTN_TEXT = 0xFFFFFFFF; OK = 0xFF0E9F6E; BAD = 0xFFC0392B;
        }
    }

    private void applyWindowTheme() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        int flags = isNight() ? 0
                : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        w.getDecorView().setSystemUiVisibility(flags);
    }

    private void restyle() {
        if (rootView == null) {
            return;
        }
        rootView.setBackgroundColor(BG);
        cardView.setBackground(roundStroked(CARD, 14, CARD_EDGE));
        titleView.setTextColor(TEXT);
        subView.setTextColor(MUTED);
        devView.setTextColor(DEV);
        warnView.setTextColor(WARN);
        logTitleView.setTextColor(MUTED);
        logScroll.setBackground(roundStroked(LOG_BG, 12, LOG_EDGE));
        logView.setTextColor(LOG);
        btn.setBackground(round(ACCENT, 14));
        btn.setTextColor(BTN_TEXT);
        if (koView != null) {
            updateKoView();
        }
        tvShizuku.setTextColor(binderAlive ? OK : BAD);
        if (binderAlive) {
            tvSelinux.setTextColor(MUTED);
            tvKsu.setTextColor(MUTED);
        }
        applyWindowTheme();
        if (binderAlive && !running) {
            probeState();
        }
    }

    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        boolean night = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night == lastNight) {
            return;
        }
        lastNight = night;
        applyPalette();
        restyle();
        append("theme -> " + (night ? "dark" : "light") + " (system uiMode)");
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
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

    private LinearLayout statusRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView l = text(label, 13, MUTED, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(l, lp);
        value.setTextSize(13);
        value.setTextColor(TEXT);
        row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        rootView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(26), dp(18), dp(16));

        TextView title = text("O3 Root", 30, TEXT, true);
        titleView = title;
        root.addView(title);

        TextView sub = text("v" + BuildInfo.VERSION + " · CVE-2026-98260 · OS4", 12, MUTED, false);
        subView = sub;
        sub.setPadding(0, dp(4), 0, 0);
        if (BuildInfo.CONSOLE) {
            sub.setOnLongClickListener(new ConsoleEntry(this));
        }
        root.addView(sub);

        String dev = Build.MODEL + " · " + System.getProperty("os.version");
        TextView devv = text(dev, 11, DEV, false);
        devView = devv;
        devv.setPadding(0, dp(10), 0, 0);
        root.addView(devv);

        LinearLayout card = new LinearLayout(this);
        cardView = card;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundStroked(CARD, 14, CARD_EDGE));
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        root.addView(card, clp);

        tvShizuku = text("未连接", 13, BAD, false);
        tvSelinux = text("未知", 13, MUTED, false);
        tvKsu = text("检测中…", 13, MUTED, false);
        card.addView(statusRow("Shizuku", tvShizuku));
        card.addView(statusRow("SELinux", tvSelinux));
        card.addView(statusRow("KernelSU", tvKsu));

        TextView warn = text(WARN_DEFAULT, 11, WARN, false);
        warnView = warn;
        warn.setPadding(0, dp(14), 0, 0);
        root.addView(warn);

        btn = new Button(this);
        btn.setText("Continue to UID 0");
        btn.setTextSize(16);
        btn.setTextColor(BTN_TEXT);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(round(ACCENT, 14));
        btn.setEnabled(false);
        btn.setOnClickListener(new ClickStart(this));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        blp.topMargin = dp(14);
        root.addView(btn, blp);

        LinearLayout koRow = new LinearLayout(this);
        koRow.setOrientation(LinearLayout.HORIZONTAL);
        koRow.setGravity(Gravity.CENTER_VERTICAL);
        koRow.setPadding(0, dp(10), 0, 0);
        koView = text("内核模块: 自适应（内置 KSU / Next / SukiSU）", 11, MUTED, false);
        koRow.addView(koView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView koPick = text("选择 .ko", 11, ACCENT, false);
        koPick.setOnClickListener(new ClickPickKo(this));
        koRow.addView(koPick);
        koRow.setOnLongClickListener(new LongClearKo(this));
        root.addView(koRow);

        TextView lt = text("LogBox", 11, MUTED, false);
        logTitleView = lt;
        LinearLayout lh = new LinearLayout(this);
        lh.setOrientation(LinearLayout.HORIZONTAL);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.setPadding(0, dp(16), 0, dp(6));
        lh.addView(lt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cp = text("复制", 11, ACCENT, false);
        cp.setPadding(0, 0, dp(14), 0);
        cp.setOnClickListener(new ClickCopyLog(this));
        lh.addView(cp);
        TextView ex = text("导出日志", 11, ACCENT, false);
        ex.setOnClickListener(new ClickExport(this));
        lh.addView(ex);
        root.addView(lh);

        logScroll = new ScrollView(this);
        logScroll.setBackground(roundStroked(LOG_BG, 12, LOG_EDGE));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(logScroll, slp);

        logView = new TextView(this);
        logView.setTextSize(9);
        logView.setTextColor(LOG);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        return root;
    }

    public void append(final String line) {
        ui.post(new AppendTask(this, line));
    }

    void appendNow(String line) {
        if (logBuf.length() > 200000) {
            logBuf.delete(0, 80000);
            if (logSpan.length() > 80000) {
                logSpan.delete(0, 80000);
            }
        }
        logBuf.append(line).append('\n');
        logSpan.append(decor(line)).append('\n');
        logView.setText(logSpan);
        logScroll.post(new ScrollBottom(this));
    }

    private CharSequence decor(String line) {
        int color = LOG;
        boolean bold = false;
        String pre = "";
        if (line.startsWith("固件门禁通过")) {
            color = OK; bold = true; pre = "✔ ";
        } else if (line.startsWith("固件门禁未通过") || line.startsWith("固件不受支持")) {
            color = BAD; bold = true; pre = "✘ ";
        } else if (line.startsWith("binder received") || line.startsWith("binder uid=")) {
            color = ACCENT; pre = "● ";
        } else if (line.startsWith("=== running payload")) {
            color = ACCENT; bold = true; pre = "▶ ";
        } else if (line.startsWith("UIDNOW uid=0")) {
            color = OK; bold = true; pre = "★ ";
        } else if (line.startsWith("KSU_MODULE LIVE")) {
            color = OK; bold = true; pre = "★ ";
        } else if (line.startsWith("KSU_MODULE ABSENT")) {
            color = WARN; pre = "⚠ ";
        } else if (line.startsWith("ROOTD sock ready") || line.startsWith("ROOTD fork")) {
            color = ACCENT; pre = "· ";
        } else if (line.startsWith("全部完成")) {
            color = OK; bold = true; pre = "✔ ";
        } else if (line.startsWith("未获得 UID 0")) {
            color = BAD; bold = true; pre = "✘ ";
        } else if (line.startsWith("诊断:")) {
            color = WARN; pre = "⚠ ";
        } else if (line.startsWith("installer status=0")) {
            color = OK; bold = true; pre = "✔ ";
        } else if (line.startsWith("installer status=")) {
            color = WARN; pre = "⚠ ";
        } else if (line.startsWith("installer:")) {
            color = MUTED; pre = "· ";
        } else if (line.startsWith("payload exit=") || line.startsWith("am start ->")
                || line.startsWith("top ->") || line.startsWith("probe ->")
                || line.startsWith("KSU_")) {
            color = MUTED;
        } else if (line.startsWith("custom ko")) {
            color = (line.indexOf("失败") >= 0 || line.indexOf("未通过") >= 0) ? WARN : ACCENT;
            pre = "· ";
        } else if (line.startsWith("CUSTOM_KO")) {
            color = (line.indexOf("reject") >= 0 || line.indexOf("fallback") >= 0) ? WARN : ACCENT;
            pre = "· ";
        } else if (line.startsWith("O3 Root v")) {
            color = ACCENT; bold = true;
        } else if (line.startsWith("checkSelfPermission=") || line.startsWith("requesting permission")
                || line.startsWith("permission result=") || line.startsWith("theme ->")) {
            color = ACCENT;
        }
        CharSequence body = pre + line;
        SpannableString ss = new SpannableString(body);
        ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            ss.setSpan(new StyleSpan(Typeface.BOLD), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    void scrollBottomNow() {
        if (nearBottom()) {
            logScroll.fullScroll(ScrollView.FOCUS_DOWN);
        }
    }

    boolean nearBottom() {
        if (logView.getHeight() <= logScroll.getHeight() + dp(24)) {
            return true;
        }
        int y = logScroll.getScrollY();
        return y + logScroll.getHeight() >= logView.getBottom() - dp(24);
    }

    void writeFile(String line) {
        try {
            File f = new File(getFilesDir(), "run.log");
            FileOutputStream out = new FileOutputStream(f, true);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public void uiBinder(boolean alive) {
        ui.post(new BinderTask(this, alive));
    }

    void binderNow(boolean alive) {
        binderAlive = alive;
        if (alive) {
            String uid;
            String ctx;
            try {
                uid = String.valueOf(Shizuku.getUid());
            } catch (Throwable t) {
                uid = "?";
            }
            try {
                ctx = Shizuku.getSELinuxContext();
            } catch (Throwable t) {
                ctx = "?";
            }
            tvShizuku.setText("已连接 uid=" + uid + " " + ctx);
            tvShizuku.setTextColor(OK);
            btn.setEnabled(!running);
            append("binder uid=" + uid + " ctx=" + ctx);
            if (!running) {
                adoptManager(detectManager());
                probeState();
            }
        } else {
            tvShizuku.setText("未连接");
            tvShizuku.setTextColor(BAD);
            tvKsu.setText("未知");
            btn.setEnabled(false);
            append("binder dead");
        }
    }

    private void checkFirmware() {
        String fp = Build.FINGERPRINT;
        String kv = System.getProperty("os.version", "");
        append("fingerprint=" + fp);
        append("kernel=" + kv);
        boolean kernelOk = EXPECT_KERNEL.equals(kv);
        boolean o1Kernel = O1_KERNEL.equals(kv);
        /* OS4.0.0.x on O1 may carry other builds of the same 6.18.21
           android17 4K series; accept the series and warn instead of
           pinning the one build that was analysed. */
        boolean o1Series = kv.startsWith("6.18.21-android17")
                && kv.endsWith("-4k");
        boolean yt = fp.contains("/yingtian/");
        boolean lh = fp.contains("/lhasa/");
        String o1Dev = null;
        for (int i = 0; i < O1_DEVICES.length; i++) {
            if (fp.contains("/" + O1_DEVICES[i] + "/")) {
                o1Dev = O1_DEVICES[i];
            }
        }
        if (!kernelOk && !o1Kernel && !(o1Dev != null && o1Series)) {
            fwOk = false;
            append("固件门禁未通过：内核不是已验证的 yingtian/lhasa 构建，"
                    + "也不在 O1 的 6.18.21-android17 系列");
            if (warnView != null) {
                warnView.setText("当前内核与已验证版本不一致：" + kv + "；为避免写坏内核对象，已禁用一键提权");
                warnView.setTextColor(BAD);
            }
            if (btn != null) {
                btn.setEnabled(false);
            }
            return;
        }
        if (o1Kernel || o1Series) {
            if (o1Dev == null) {
                fwOk = false;
                append("固件门禁未通过：O1 内核但设备代号未知（仅支持 dijun / jinghu / violin）");
                if (warnView != null) {
                    warnView.setText("O1 内核，但未识别的设备：" + fp);
                    warnView.setTextColor(BAD);
                }
                if (btn != null) {
                    btn.setEnabled(false);
                }
                return;
            }
            fwOk = true;
            o1Profile = true;
            if (o1Kernel) {
                append("固件门禁通过（O1 实验性：" + o1Dev + " / " + kv + "）");
                append("⚠ O1 支持仅经静态分析，未真机验证；如失败请导出日志反馈");
                if (warnView != null) {
                    warnView.setText("O1 实验性支持（未真机验证）：如失败请导出日志反馈");
                    warnView.setTextColor(WARN);
                }
            } else {
                append("固件门禁通过（O1 宽松模式：" + o1Dev + " / " + kv + "）");
                append("⚠ 该内核构建未做静态分析：按 OS4.0.0 / 6.18.21-android17 系列放行，"
                        + "偏移可能不匹配；如失败请导出日志反馈");
                if (warnView != null) {
                    warnView.setText("O1 宽松模式（内核构建未验证）：如失败请导出日志反馈");
                    warnView.setTextColor(WARN);
                }
            }
            return;
        }
        if (yt && fp.contains("OS4.0.16.0.XBMCNXM")) {
            fwOk = true;
            append("固件门禁通过（yingtian / OS4.0.16.0.XBMCNXM）");
        } else if (lh) {
            fwOk = true;
            append("固件门禁通过（lhasa；内核与 yingtian OS4.0.16 为同一 build，偏移/符号通用）");
            if (!fp.contains("OS4.0.26.0.XPNCNXM")) {
                append("提示：lhasa build 串与已验证的 OS4.0.26.0.XPNCNXM 不同，但内核一致，继续执行");
            }
        } else if (yt) {
            fwOk = true;
            append("固件门禁通过（yingtian；build 串非 4.0.16，但内核一致，继续执行）");
        } else {
            fwOk = false;
            append("固件门禁未通过：未识别的设备（支持 yingtian / lhasa；O1 实验性：dijun / jinghu / violin）");
            if (warnView != null) {
                warnView.setText("未识别的设备：" + fp + "；本版本支持 yingtian / lhasa，"
                    + "以及 O1 系列（OS4.0.0.x、6.18.21-android17）");
                warnView.setTextColor(BAD);
            }
            if (btn != null) {
                btn.setEnabled(false);
            }
        }
    }

    void installLog(String s) {
        append("installer: " + s);
    }

    void installPrompt(Intent it) {
        if (it == null) {
            append("installer: 系统未返回确认界面");
            return;
        }
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(it);
            append("installer: 等待系统安装确认");
        } catch (Throwable x) {
            append("installer: 打开确认界面失败 " + x);
        }
    }

    void installResult(int status, String msg) {
        append("installer status=" + status + " msg=" + msg);
        if (status == 0) {
            adoptManager(detectManager());
            if (!mgrOk) {
                mgrOk = true;
                mgrFork = "tiann";
                mgrPkg = "me.weishu.kernelsu";
            }
            append("KernelSU 管理器安装完成");
            applyButton();
        } else {
            btn.setText("安装 KernelSU 管理器");
            append("管理器安装未完成：可重试，或用文件管理器手动安装 APK 内 assets/ksu-manager.apk");
        }
        btn.setEnabled(true);
        probeState();
    }

    public void probeState() {
        if (!Shizuku.pingBinder()) {
            return;
        }
        if (!mgrOk) {
            adoptManager(detectManager());
        }
        try {
            if (Shizuku.checkSelfPermission() != 0) {
                probeNoPerm();
                return;
            }
        } catch (Throwable t) {
            probeNoPerm();
            return;
        }
        new Thread(new StateProbe(this), "probe").start();
    }

    void probeNoPerm() {
        append("probe: 需要 Shizuku 授权（点按钮可授权）");
        if (!mgrOk) {
            adoptManager(detectManager());
        }
        tvKsu.setText("未知");
        tvKsu.setTextColor(MUTED);
        tvSelinux.setText("未知");
        tvSelinux.setTextColor(MUTED);
        applyButton();
    }

    void probeFailed(String raw) {
        append("probe -> " + raw);
        adoptManager(detectManager());
        tvKsu.setText("未知");
        tvKsu.setTextColor(MUTED);
        tvSelinux.setText("未知");
        tvSelinux.setTextColor(MUTED);
        applyButton();
        append("probe 失败：若 Shizuku 权限未授予，点按钮授权；否则导出日志反馈");
    }

    private String detectManager() {
        try {
            String[] forks = {"tiann", "next", "sukisu"};
            String[] pkgs = {"me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra"};
            for (int i = 0; i < 3; i++) {
                try {
                    getPackageManager().getPackageInfo(pkgs[i], 0);
                    return forks[i];
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void adoptManager(String fork) {
        if (fork == null) {
            return;
        }
        mgrOk = true;
        mgrFork = fork;
        if (fork.equals("tiann")) {
            mgrPkg = "me.weishu.kernelsu";
        } else if (fork.equals("next")) {
            mgrPkg = "com.rifsxd.ksunext";
        } else {
            mgrPkg = "com.sukisu.ultra";
        }
    }

    private void applyButton() {
        if (running) {
            return;
        }
        if (!fwOk) {
            btn.setEnabled(false);
        } else if (!mgrOk) {
            btn.setText("安装 KernelSU 管理器");
            btn.setEnabled(true);
        } else if (netRisk) {
            btn.setText("恢复 SELinux 宽容模式");
            btn.setEnabled(Shizuku.pingBinder());
        } else if (ksuOk) {
            btn.setText("打开 KernelSU");
            btn.setEnabled(true);
        } else {
            btn.setText("Continue to UID 0");
            btn.setEnabled(Shizuku.pingBinder());
        }
    }

    public void probeNow(String out) {
        ui.post(new ProbeTask(this, out));
    }

    void probeApply(String s) {
        if (s == null) {
            s = "";
        }
        String raw = s.trim();
        if (raw.startsWith("ERR")) {
            probeFailed(raw);
            return;
        }
        String[] parts = raw.split("\n");
        int cnt = -1;
        if (parts.length > 0) {
            try {
                cnt = Integer.parseInt(parts[0].trim());
            } catch (Throwable ignored) {
            }
        }
        String enf = parts.length > 1 ? parts[1].trim() : "";
        StringBuilder pd = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                pd.append("|");
            }
            pd.append(parts[i].trim());
        }
        append("probe -> " + pd);
        String fork = null;
        for (int i = 2; i < parts.length; i++) {
            String f = parts[i].trim();
            if (f.equals("tiann") || f.equals("next") || f.equals("sukisu")) {
                fork = f;
                break;
            }
        }
        if (fork == null) {
            fork = detectManager();
        }
        adoptManager(fork);
        if (mgrOk) {
            append("manager -> fork=" + mgrFork + " pkg=" + mgrPkg);
        }
        if (enf.length() > 0) {
            tvSelinux.setText(enf);
            tvSelinux.setTextColor(enf.indexOf("Permissive") >= 0 ? OK : MUTED);
        }
        if (cnt > 0) {
            ksuOk = true;
            tvKsu.setText("已加载");
            tvKsu.setTextColor(OK);
        } else if (cnt == 0) {
            ksuOk = false;
            tvKsu.setText("未加载");
            tvKsu.setTextColor(MUTED);
        }
        boolean risk = false; /* v3: Enforcing is the normal end state (clean policycap) */
        netRisk = risk;
        if (risk) {
            tvSelinux.setText("Enforcing ⚠");
            tvSelinux.setTextColor(WARN);
            if (!netRiskShown) {
                netRiskShown = true;
                append("⚠ SELinux 已回到 Enforcing：policycap 仍是被污染的，网络会中断");
                append("⚠ 点按钮可一键恢复（先 su setenforce 0，失败则重跑载荷）");
            }
            if (warnView != null) {
                warnView.setText("SELinux 已回到 Enforcing：网络可能中断，点按钮恢复 Permissive");
                warnView.setTextColor(BAD);
            }
        } else {
            netRiskShown = false;
            if (warnView != null) {
                warnView.setText(WARN_DEFAULT);
                warnView.setTextColor(WARN);
            }
        }
        applyButton();
    }

    public void uiPermission(int grant) {
        ui.post(new PermTask(this, grant));
    }

    void permNow(int grant) {
        append("permission result=" + grant);
        if (grant == 0) {
            adoptManager(detectManager());
            new Thread(new PermProbe(this), "permprobe").start();
        }
    }

    void startAfterGrant() {
        if (running || netRisk) {
            return;
        }
        append("已授权：KSU 未加载，开始执行载荷");
        startPayload();
    }

    public void onStartClicked() {
        if (!fwOk) {
            append("固件不受支持，已阻止执行");
            return;
        }
        if (!mgrOk) {
            adoptManager(detectManager());
        }
        if (!mgrOk) {
            append("KernelSU 管理器未安装：开始安装内置管理器…");
            btn.setEnabled(false);
            btn.setText("正在安装管理器…");
            new Thread(new ManagerInstaller(this), "installer").start();
            return;
        }
        if (running) {
            append("正在运行，请稍候");
            return;
        }
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行：请先在 Shizuku 里用无线调试启动");
            return;
        }
        int perm = Shizuku.checkSelfPermission();
        append("checkSelfPermission=" + perm);
        if (perm != 0) {
            append("requesting permission...");
            Shizuku.requestPermission(1001);
            return;
        }
        if (netRisk) {
            recoverNow();
            return;
        }
        if (ksuOk) {
            append("KernelSU 已加载，直接打开");
            new Thread(new DelayLaunch(this, 100), "launch").start();
            return;
        }
        startPayload();
    }

    void recoverNow() {
        btn.setEnabled(false);
        btn.setText("恢复中…");
        append("== 恢复 SELinux 宽容模式：su -c setenforce 0");
        new Thread(new RecoverRunner(this), "recover").start();
    }

    public void recoverDone(String out) {
        ui.post(new RecoverTask(this, out));
    }

    void recoverApply(String out) {
        String[] lines = out == null ? new String[0] : out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.length() > 0) {
                append("recover: " + t);
            }
        }
        boolean ok = out != null && out.indexOf("Permissive") >= 0;
        if (ok) {
            append("✔ 已恢复 SELinux 宽容模式（网络应已恢复）");
            tvSelinux.setText("Permissive");
            tvSelinux.setTextColor(OK);
            netRisk = false;
            netRiskShown = false;
            btn.setEnabled(true);
            btn.setText("打开 KernelSU");
            probeState();
        } else {
            append("⚠ su 恢复失败，改为重跑载荷（会重新执行提权链）");
            btn.setEnabled(true);
            startPayload();
        }
    }

    void startPayload() {
        if (running) {
            return;
        }
        running = true;
        rootOk = false;
        btn.setEnabled(false);
        btn.setText("运行中…");
        append("=== running payload as Shizuku shell ===");
        runBootId = readBootId();
        if (runBootId != null) {
            append("boot_id=" + runBootId);
        }
        new Thread(new PayloadRunner(payloadPath, this, mgrFork, customKoBytes, customKoLabel,
                customKoExtPath, customKoForced, o1Profile ? "o1" : null), "payload").start();
    }

    public void onLine(String line) {
        append(line);
        if (line.indexOf("UIDNOW uid=0") >= 0) {
            rootOk = true;
            ui.post(new StatusTask(this, 2));
        }
        if (line.indexOf("Permissive") >= 0) {
            ui.post(new StatusTask(this, 1));
        }
        if (line.indexOf("KSU_MODULE LIVE") >= 0) {
            ksuOk = true;
            ui.post(new StatusTask(this, 3));
        }
        if (line.indexOf("KSU_PATCH_OK") >= 0) {
            ui.post(new StatusTask(this, 4));
        }
    }

    public void onExit(int code) {
        ui.post(new ExitTask(this, code));
    }

    void exitNow(int code) {
        running = false;
        append("payload exit=" + code);
        diagnose();
        btn.setEnabled(true);
        if (ksuOk) {
            btn.setText("打开 KernelSU");
            tvKsu.setText("已加载");
            tvKsu.setTextColor(OK);
            append("全部完成，正在打开 KernelSU…");
            new Thread(new DelayLaunch(this, 1200), "launch").start();
        } else {
            probeState();
            if (rootOk) {
                btn.setText("重试 KernelSU");
                append("已获得 UID 0，但 KernelSU 未加载");
            } else {
                btn.setText("重试");
                append("未获得 UID 0，可再试一次");
            }
        }
    }

    void statusNow(int what) {
        if (what == 1) {
            tvSelinux.setText("Permissive");
            tvSelinux.setTextColor(OK);
        } else if (what == 2) {
            tvSelinux.setText("UID 0 已获得");
            tvSelinux.setTextColor(OK);
        } else if (what == 3) {
            tvKsu.setText("已加载");
            tvKsu.setTextColor(OK);
        } else if (what == 4) {
            tvKsu.setText("打补丁完成");
            tvKsu.setTextColor(WARN);
        }
    }

    String readBootId() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/sys/kernel/random/boot_id");
            byte[] b = new byte[64];
            int n = in.read(b);
            in.close();
            if (n > 0) {
                return new String(b, 0, n, "UTF-8").trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                append("复制失败: 剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("O3 Root log", logBuf.toString()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            append("复制失败: " + t);
        }
    }

    void exportLog() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            File f = new File(dir, "o3root-log-" + System.currentTimeMillis() + ".log");
            FileOutputStream out = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(logBuf.toString());
            w.close();
            append("log -> " + f.getAbsolutePath());
        } catch (Throwable t) {
            append("导出失败: " + t);
        }
    }

    void diagnose() {
        if (rootOk && ksuOk) {
            return;
        }
        String s = logBuf.toString();
        int mark = s.lastIndexOf("=== running payload");
        if (mark >= 0) {
            s = s.substring(mark);
        }
        String now = readBootId();
        if (runBootId != null && now != null && !runBootId.equals(now)) {
            append("诊断: 运行期间设备重启过（boot_id 变化）——请重新运行");
            return;
        }
        int vUaf = s.lastIndexOf("VERDICT NO_UAF_TRIGGER");
        int vRec = s.lastIndexOf("VERDICT NO_RECLAIM");
        if (vRec < 0) {
            vRec = s.lastIndexOf("PAGERECLAIM_MISS");
        }
        int vStale = s.lastIndexOf("VERDICT NO_STALE_LINK");
        int vOff = s.lastIndexOf("BAD_OFFSET");
        int vSlide = s.lastIndexOf("KSU_SKIP no_slide");
        int best = Math.max(Math.max(vUaf, vRec), Math.max(vStale, vOff));
        best = Math.max(best, vSlide);
        String tip = null;
        if (best < 0) {
            if (s.indexOf("KSU_INSMOD_OUT rc=0") < 0 && s.indexOf("KSU_INSMOD_OUT") >= 0) {
                tip = "KSU 模块加载失败（insmod 返回非 0）——检查内核版本或已有其它 root 方案";
            } else if (s.indexOf("KSU_PATCH symtabs=") >= 0 && s.indexOf("missing=0") < 0) {
                tip = "KSU 符号缺失——内核与内置模块版本不匹配";
            } else if (!rootOk) {
                tip = "载荷未取得成功标志，请查看上方日志";
            }
        } else if (best == vRec) {
            tip = "页回收失败——先清理后台应用再重试；连续失败建议重启设备";
        } else if (best == vUaf) {
            tip = "竞态未命中（UAF 未触发）——再点一次通常就能中";
        } else if (best == vStale) {
            tip = "内核对象状态异常（stale link 校验失败）——重试";
        } else if (best == vOff) {
            tip = "对象偏移不匹配——当前固件/内核可能不受支持";
        } else {
            tip = "未取到 KASLR slide——重试";
        }
        if (tip != null) {
            append("诊断: " + tip);
        }
    }

    // ---- custom .ko (user supplied LKM) ----

    void pickKo() {
        if (running) {
            append("正在运行，稍后再选择内核模块");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_KO);
            append("custom ko: 选择自定义 .ko 文件");
        } catch (Throwable t) {
            append("custom ko: 无法打开文件选择器 " + t);
        }
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_KO) {
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) {
            append("custom ko: 已取消选择");
            return;
        }
        Uri uri = data.getData();
        String label = displayName(uri);
        append("custom ko: 读取 " + label);
        new Thread(new KoReadTask(this, uri, label), "ko-read").start();
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        name = c.getString(idx);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        if (name == null || name.length() == 0) {
            String p = uri.getPath();
            name = (p == null || p.lastIndexOf('/') < 0)
                    ? "custom.ko" : p.substring(p.lastIndexOf('/') + 1);
        }
        return name;
    }

    void postKoRead(String label, byte[] data, String err, KoResult r) {
        ui.post(new KoApplyTask(this, label, data, err, r));
    }

    void applyKoRead(String label, byte[] data, String err, KoResult r) {
        if (err != null || data == null || r == null) {
            customKoBytes = null;
            customKoLabel = null;
            customKoCheck = null;
            customKoForced = false;
            updateKoView();
            append("custom ko: 读取失败 " + (err == null ? "" : err) + "；继续使用自适应");
            return;
        }
        append("custom ko: " + label + " name=" + r.name
                + " vermagic=" + (r.vermagic.length() == 0 ? "?" : r.vermagic));
        if (r.level == CustomKo.FAIL) {
            append("custom ko: 兼容性验证未通过 — " + r.reason);
            customKoBytes = data;
            customKoLabel = label;
            customKoCheck = r;
            customKoForced = false;
            updateKoView();
            showKoFailDialog();
            return;
        }
        if (r.level == CustomKo.WARN) {
            append("custom ko: 兼容性警告 — " + r.reason);
        }
        adoptCustomKo(label, data, r, false);
    }

    private void showKoFailDialog() {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("自定义 .ko 兼容性验证未通过")
                    .setMessage(customKoCheck.reason
                            + "。退回自适应将使用内置的 KernelSU / KernelSU-Next / SukiSU 模块（推荐）。")
                    .setPositiveButton("退回自适应", new KoChoice(this, KoChoice.FALLBACK))
                    .setNeutralButton("仍然尝试", new KoChoice(this, KoChoice.FORCE))
                    .setNegativeButton("取消", new KoChoice(this, KoChoice.CANCEL))
                    .show();
        } catch (Throwable t) {
            append("custom ko: 无法显示对话框 " + t + "；已退回自适应");
            clearCustomKo("兼容性验证未通过，已退回自适应");
        }
    }

    void koDialogChoice(int action) {
        if (action == KoChoice.FALLBACK) {
            clearCustomKo("已选择退回自适应（内置模块）");
        } else if (action == KoChoice.FORCE) {
            if (customKoBytes != null && customKoCheck != null) {
                append("custom ko: 用户选择仍然尝试（未通过验证）");
                adoptCustomKo(customKoLabel, customKoBytes, customKoCheck, true);
            }
        } else {
            clearCustomKo("已取消，继续使用自适应（内置模块）");
        }
    }

    void adoptCustomKo(String label, byte[] data, KoResult r, boolean forced) {
        customKoBytes = data;
        customKoLabel = label;
        customKoCheck = r;
        customKoForced = forced;
        customKoExtPath = persistKo(data, label, forced);
        updateKoView();
        append("custom ko: 已采用 " + label + (forced ? "（未通过验证，强制使用）" : "")
                + "；加载失败时载荷会自动退回内置模块；下次启动自动沿用");
    }

    void clearCustomKo(String why) {
        customKoBytes = null;
        customKoLabel = null;
        customKoCheck = null;
        customKoForced = false;
        customKoExtPath = null;
        deleteKoFiles();
        updateKoView();
        append("custom ko: " + why);
    }

    void clearKoFromLongPress() {
        if (customKoBytes == null) {
            append("custom ko: 当前为自适应模式，无需清除");
            return;
        }
        clearCustomKo("已清除自定义模块，恢复自适应（内置模块）");
        try {
            Toast.makeText(this, "已恢复自适应（内置模块）", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private File koDir() {
        return getExternalFilesDir(null);
    }

    private File koFile() {
        File d = koDir();
        return d == null ? null : new File(d, KO_FILE);
    }

    private File koMeta() {
        File d = koDir();
        return d == null ? null : new File(d, KO_META);
    }

    private void deleteKoFiles() {
        try {
            File f = koFile();
            if (f != null && f.exists()) {
                f.delete();
            }
            File m = koMeta();
            if (m != null && m.exists()) {
                m.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private String persistKo(byte[] data, String label, boolean forced) {
        try {
            File f = koFile();
            File m = koMeta();
            if (f == null || m == null) {
                return null;
            }
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.close();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(m), "UTF-8");
            w.write((forced ? "1" : "0") + "\n"
                    + (label == null || label.length() == 0 ? "custom.ko" : label) + "\n");
            w.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            append("custom ko: 外部暂存失败 " + t);
            return null;
        }
    }

    void restoreCustomKo() {
        File f = koFile();
        if (f == null || !f.exists()) {
            return;
        }
        boolean forced = false;
        String label = "custom.ko";
        try {
            File m = koMeta();
            if (m != null && m.exists()) {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(m), "UTF-8"));
                String l1 = r.readLine();
                String l2 = r.readLine();
                r.close();
                forced = "1".equals(l1 == null ? "" : l1.trim());
                if (l2 != null && l2.trim().length() > 0) {
                    label = l2.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        long len = f.length();
        if (len <= 0 || len > CustomKo.MAX_BYTES) {
            deleteKoFiles();
            append("custom ko: 上次选择的文件已失效，已清除");
            return;
        }
        byte[] data = null;
        try {
            FileInputStream in = new FileInputStream(f);
            data = new byte[(int) len];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            in.close();
            if (off != data.length) {
                data = null;
            }
        } catch (Throwable t) {
            data = null;
        }
        if (data == null) {
            deleteKoFiles();
            append("custom ko: 上次选择的文件读取失败，已清除");
            return;
        }
        KoResult r = CustomKo.validate(data, System.getProperty("os.version", ""));
        if (r.level == CustomKo.FAIL && !forced) {
            deleteKoFiles();
            append("custom ko: 上次选择的文件未通过验证（" + r.reason + "），已清除");
            return;
        }
        customKoBytes = data;
        customKoLabel = label;
        customKoCheck = r;
        customKoForced = forced;
        customKoExtPath = f.getAbsolutePath();
        updateKoView();
        append("custom ko: 已恢复上次选择 " + label
                + (forced ? "（强制）" : "") + " name=" + r.name);
    }

    void updateKoView() {
        if (koView == null) {
            return;
        }
        if (customKoBytes == null || customKoCheck == null) {
            koView.setText("内核模块: 自适应（内置 KSU / Next / SukiSU）");
            koView.setTextColor(MUTED);
            return;
        }
        String s = "内核模块: 自定义 " + customKoLabel;
        if (customKoForced) {
            s = s + " ⚠ 未通过验证";
            koView.setTextColor(WARN);
        } else if (customKoCheck.level == CustomKo.FAIL) {
            s = s + " ⚠ 未通过验证（待选择）";
            koView.setTextColor(WARN);
        } else if (customKoCheck.level == CustomKo.WARN) {
            s = s + " ⚠ 同系列不同构建";
            koView.setTextColor(WARN);
        } else {
            s = s + " ✔ " + customKoCheck.kernelRelease;
            koView.setTextColor(OK);
        }
        koView.setText(s);
    }

    public void launchManager() {
        String pkg = mgrPkg != null ? mgrPkg : "me.weishu.kernelsu";
        String comp = oneLine(PayloadRunner.runShellSync(
                "cmd package resolve-activity --brief " + pkg + " 2>/dev/null | tail -1"));
        String target = comp.indexOf('/') > 0 ? comp : (pkg + "/.ui.MainActivity");
        String out = PayloadRunner.runShellSync(
                "am start -W -n " + target + " 2>&1 | head -6");
        append("am start -> " + oneLine(out) + " [" + target + "]");
        boolean bad = out == null || out.indexOf("Error") >= 0 || out.indexOf("Exception") >= 0
                || out.indexOf("Warning") >= 0;
        if (bad) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                append("intent 兜底已发送");
            } catch (Throwable t) {
                append("intent 兜底失败: " + t);
            }
        }
        String top = PayloadRunner.runShellSync(
                "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity");
        append("top -> " + oneLine(top));
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replace('\n', ' ');
    }
}
