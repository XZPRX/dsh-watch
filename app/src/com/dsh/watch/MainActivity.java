package com.dsh.watch;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 单页极简手表端：选形状 / 连接 / 事件流 / 发指令 / 审批，共五个屏。
 *
 * 尺寸全部走 Ui：按屏幕宽度线性缩放，并尊重系统字体缩放；
 * 圆屏会把内容收进内切圆，方屏用满四角。换一块 200dp 或 300dp 的表也不会挤成一坨。
 */
public class MainActivity extends Activity implements Net.Listener {

    private enum Screen { SHAPE, SETUP, PAIR, HOME, COMPOSE, APPROVAL, SESSIONS }

    private static final int MAX_EVENTS = 120;
    private static final long REFRESH_MS = 15000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Event> events = new ArrayList<Event>();
    private final ArrayList<View> rows = new ArrayList<View>();
    private final LinkedHashMap<String, Approval> approvals = new LinkedHashMap<String, Approval>();

    private Store store;
    private Net net;

    private FrameLayout root;
    private LinearLayout body;
    private Screen screen = Screen.HOME;
    private boolean stopRefresh;

    private View shapeView;
    private View setupView;
    private View homeView;
    private View composeView;
    private View approvalView;
    private View sessionsView;

    private LinearLayout sessionList;
    private TextView sessionsHint;

    private EditText hostInput;
    private EditText tokenInput;
    private TextView setupStatus;
    private LinearLayout hostList;
    private TextView scanHint;
    private TextView remoteHint;

    private View dot;
    private TextView headerText;
    private TextView sessionText;
    private LinearLayout eventList;
    private ScrollView eventScroll;
    private TextView approvalButton;

    private EditText composeInput;
    private TextView composeHint;

    private TextView approvalTool;
    private TextView approvalReason;
    private TextView approvalPreview;
    private TextView approvalCountdown;
    private TextView approvalProgress;
    private String approvalCurrentId;

    private String sessionId = "";
    private String sessionTitle = "";
    private boolean running;
    private int statusState = 0;
    private String statusDetail = "未连接";

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setFontScale(getResources().getConfiguration().fontScale);
        store = new Store(this);
        Ui.setShape(store.shape());
        Ui.init(this);
        net = new Net(this);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        buildRoot();

        applyIntentExtras();
        if (store.shape().isEmpty()) {
            show(Screen.SHAPE);
        } else if (store.paired()) {
            net.connect(store.endpoints(), store.token());
            show(Screen.HOME);
        } else {
            show(Screen.SETUP);
        }
        ui.postDelayed(refresher, REFRESH_MS);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntentExtras();
        if (store.paired()) {
            net.connect(store.endpoints(), store.token());
            show(Screen.HOME);
        }
    }

    /** 支持 adb 预置：am start -n com.dsh.watch/.MainActivity --es host 1.2.3.4:8765 --es token xxx
     *  也可以用配对码：--es host 1.2.3.4:8765 --es pair 123456 */
    private void applyIntentExtras() {
        try {
            Bundle extras = getIntent() == null ? null : getIntent().getExtras();
            if (extras == null) return;
            String host = extras.getString("host");
            String token = extras.getString("token");
            String pairCode = extras.getString("pair");
            if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(token)) {
                store.promote(host, token);
            } else if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(pairCode)) {
                pendingHost = host.trim();
                pendingHostLabel = pendingHost;
                net.pair(pendingHost, pairCode.trim(), new Net.PairCallback() {
                    @Override
                    public void done(final boolean ok, final String message, final String issued, final String[] addresses) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!ok) {
                                    if (setupStatus != null) setupStatus.setText(message);
                                    return;
                                }
                                store.saveEndpoints(addresses == null || addresses.length == 0
                                        ? new String[]{pendingHost} : addresses, issued);
                                resetFeed();
                                net.connect(store.endpoints(), issued);
                                show(Screen.HOME);
                            }
                        });
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (stopRefresh) return;
            if (net.isRunning()) net.refreshState();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onDestroy() {
        stopRefresh = true;
        ui.removeCallbacksAndMessages(null);
        discovery.cancel();
        scanPool.shutdownNow();
        net.disconnect();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.setFontScale(newConfig.fontScale);
        Ui.setShape(store.shape());
        Ui.init(this);
        shapeView = null;
        setupView = null;
        homeView = null;
        composeView = null;
        approvalView = null;
        sessionsView = null;
        pairScreenView = null;
        buildRoot();
        show(screen);
    }

    @Override
    public void onBackPressed() {
        if (screen != Screen.HOME) {
            show(Screen.HOME);
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------------
    // 手势：右滑返回上一屏，左滑进「新建 / 切换会话」
    // ---------------------------------------------------------------------

    private float touchDownX;
    private float touchDownY;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = ev.getX();
                touchDownY = ev.getY();
                break;
            case MotionEvent.ACTION_UP: {
                float dx = ev.getX() - touchDownX;
                float dy = ev.getY() - touchDownY;
                float threshold = Math.max(Ui.dp(26), getResources().getDisplayMetrics().widthPixels * 0.16f);
                // 横向位移够大、且明显比竖向大，才算滑动（避免和纵向滚动打架）。
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (dx > 0) swipeBack();
                    else swipeForward();
                }
                break;
            }
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void swipeBack() {
        hideKeyboard();
        if (screen == Screen.HOME) return;
        show(Screen.HOME);
    }

    private void swipeForward() {
        hideKeyboard();
        if (screen == Screen.SHAPE || screen == Screen.SETUP || screen == Screen.PAIR) return;
        show(Screen.SESSIONS);
    }

    // ---------------------------------------------------------------------
    // 骨架
    // ---------------------------------------------------------------------

    private void buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int inset = Ui.roundInset(getResources().getDisplayMetrics().widthPixels);
        body.setPadding(inset + Ui.dp(8), Ui.dp(8), inset + Ui.dp(8), Ui.dp(8));
        root.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void show(Screen next) {
        screen = next;
        body.removeAllViews();
        switch (next) {
            case SHAPE:
                if (shapeView == null) shapeView = buildShape();
                body.addView(shapeView, full());
                break;
            case SETUP:
                if (setupView == null) setupView = buildSetup();
                body.addView(setupView, full());
                startScan();
                break;
            case PAIR:
                if (pairScreenView == null) pairScreenView = buildPair();
                body.addView(pairScreenView, full());
                break;
            case HOME:
                if (homeView == null) homeView = buildHome();
                body.addView(homeView, full());
                renderEvents();
                updateHeader();
                break;
            case COMPOSE:
                if (composeView == null) composeView = buildCompose();
                body.addView(composeView, full());
                refreshCompose();
                break;
            case APPROVAL:
                if (approvalView == null) approvalView = buildApproval();
                body.addView(approvalView, full());
                renderApproval();
                break;
            case SESSIONS:
                if (sessionsView == null) sessionsView = buildSessions();
                body.addView(sessionsView, full());
                loadSessions();
                break;
        }
    }

    private static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // 首次启动：选圆屏还是方屏
    // ---------------------------------------------------------------------

    private View buildShape() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);

        TextView title = Ui.text(this, "你的表是什么屏？", 14f, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, wrap());

        TextView sub = Ui.text(this, "圆屏把内容收进内切圆，方屏用满四角；之后能在设置里改。", 9f, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.margins(sub, 0, 3, 0, 8);
        root.addView(sub, wrap());

        LinearLayout previews = row();
        previews.setGravity(Gravity.CENTER_HORIZONTAL);

        View roundPreview = new View(this);
        roundPreview.setBackground(Ui.oval(Ui.ACCENT));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(42), Ui.dp(42));
        rp.setMargins(0, 0, Ui.dp(14), Ui.dp(8));
        previews.addView(roundPreview, rp);

        View squarePreview = new View(this);
        squarePreview.setBackground(Ui.stroke(Ui.PANEL2, 4f, Ui.LINE, 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(42), Ui.dp(42));
        sp.setMargins(0, 0, 0, Ui.dp(8));
        previews.addView(squarePreview, sp);
        root.addView(previews, wrap());

        LinearLayout actions = row();
        TextView round = Ui.button(this, "◯  圆屏", 11.5f, Ui.ACCENT, 0xFFFFFFFF);
        round.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseShape("round");
            }
        });
        actions.addView(round, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView square = Ui.button(this, "▢  方屏", 11.5f, Ui.PANEL2, Ui.TEXT);
        square.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseShape("square");
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.setMargins(Ui.dp(6), 0, 0, 0);
        actions.addView(square, slp);
        Ui.margins(actions, 0, 2, 0, 0);
        root.addView(actions, wrap());
        return root;
    }

    private void chooseShape(String shape) {
        store.saveShape(shape);
        Ui.setShape(shape);
        Ui.init(this);
        shapeView = null;
        setupView = null;
        homeView = null;
        composeView = null;
        approvalView = null;
        buildRoot();
        if (store.paired()) {
            if (!net.isRunning()) net.connect(store.endpoints(), store.token());
            show(Screen.HOME);
        } else {
            show(Screen.SETUP);
        }
    }

    // ---------------------------------------------------------------------
    // 连接屏（同时是设置页）
    // ---------------------------------------------------------------------

    private View buildSetup() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = row();
        TextView title = Ui.text(this, "连接电脑", 14f, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rescan = Ui.button(this, "搜索", 9f, Ui.PANEL2, Ui.TEXT);
        rescan.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(4));
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        head.addView(rescan, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(head, wrap());

        scanHint = Ui.text(this, "手表和电脑连同一个 WiFi", 8.5f, Ui.MUTED, false);
        Ui.margins(scanHint, 0, 1, 0, 4);
        root.addView(scanHint, wrap());

        ScrollView scanScroll = new ScrollView(this);
        scanScroll.setVerticalScrollBarEnabled(false);
        scanScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hostList = new LinearLayout(this);
        hostList.setOrientation(LinearLayout.VERTICAL);
        scanScroll.addView(hostList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanLp.setMargins(0, 0, 0, Ui.dp(4));
        root.addView(scanScroll, scanLp);

        View manualDivider = divider("手动输入");
        Ui.margins(manualDivider, 0, 2, 0, 4);
        root.addView(manualDivider, wrap());

        hostInput = editText("192.168.1.5:8765", false);
        hostInput.setText(store.host());
        root.addView(hostInput, wrap());
        Ui.margins(hostInput, 0, 0, 0, 4);

        tokenInput = editText("配对码或令牌", false);
        tokenInput.setText(store.token());
        root.addView(tokenInput, wrap());
        Ui.margins(tokenInput, 0, 0, 0, 5);

        LinearLayout actions = row();
        TextView connect = Ui.button(this, "连接", 11f, Ui.ACCENT, 0xFFFFFFFF);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doConnect();
            }
        });
        actions.addView(connect, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout shapeRow = row();
        final boolean isRound = "round".equals(store.shape());
        TextView roundChip = Ui.button(this, "圆屏", 9f, isRound ? Ui.ACCENT : Ui.PANEL2, isRound ? 0xFFFFFFFF : Ui.TEXT);
        roundChip.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        roundChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseShape("round");
            }
        });
        shapeRow.addView(roundChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView squareChip = Ui.button(this, "方屏", 9f, isRound ? Ui.PANEL2 : Ui.ACCENT, isRound ? Ui.TEXT : 0xFFFFFFFF);
        squareChip.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        LinearLayout.LayoutParams squareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        squareLp.setMargins(Ui.dp(4), 0, 0, 0);
        squareChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseShape("square");
            }
        });
        shapeRow.addView(squareChip, squareLp);
        actions.addView(shapeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(actions, wrap());

        setupStatus = Ui.text(this, statusDetail, 9f, Ui.MUTED, false);
        Ui.margins(setupStatus, 0, 5, 0, 0);
        root.addView(setupStatus, wrap());

        remoteHint = Ui.text(this, "", 8.5f, Ui.MUTED, false);
        Ui.margins(remoteHint, 0, 3, 0, 0);
        root.addView(remoteHint, wrap());

        TextView back = Ui.text(this, "← 返回", 9.5f, Ui.MUTED, false);
        Ui.margins(back, 0, 4, 0, 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(Screen.HOME);
            }
        });
        root.addView(back, wrap());

        // —— 彩蛋：设置页最下面的小人，按住会缩、松手弹性回弹 ——
        int eggSize = Ui.dp(64);
        LinearLayout.LayoutParams eggLp = new LinearLayout.LayoutParams(eggSize, eggSize);
        eggLp.gravity = Gravity.CENTER_HORIZONTAL;
        eggLp.setMargins(0, Ui.dp(8), 0, Ui.dp(6));
        root.addView(buildEasterEgg(), eggLp);

        // 内容比屏幕高时能滚，彩蛋藏在最下面。
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setClipToPadding(false);
        page.setPadding(0, 0, 0, Ui.dp(4));
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    /** 设置页底部的彩蛋：按下缩小、松手用 Overshoot 回弹，带一点震动反馈。 */
    private View buildEasterEgg() {
        ImageView egg = new ImageView(this);
        egg.setImageResource(R.drawable.easter_egg);
        if (egg.getDrawable() instanceof BitmapDrawable) {
            // 像素画别用双线性插值，保持硬边
            ((BitmapDrawable) egg.getDrawable()).setFilterBitmap(false);
        }
        egg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        egg.setClickable(true);

        egg.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.82f).scaleY(0.82f)
                                .setDuration(90)
                                .setInterpolator(new DecelerateInterpolator(1.5f))
                                .start();
                        buzz(12);
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(420)
                                .setInterpolator(new OvershootInterpolator(3.4f))
                                .start();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(300)
                                .setInterpolator(new OvershootInterpolator(2.4f))
                                .start();
                        return true;
                    default:
                        return true;
                }
            }
        });
        return egg;
    }

    private View divider(String label) {
        LinearLayout line = row();
        View left = new View(this);
        left.setBackgroundColor(Ui.LINE);
        line.addView(left, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView text = Ui.text(this, label, 8f, Ui.MUTED, false);
        text.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
        line.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View right = new View(this);
        right.setBackgroundColor(Ui.LINE);
        line.addView(right, new LinearLayout.LayoutParams(0, 1, 1f));
        return line;
    }

    // ---------------------------------------------------------------------
    // 自动发现 / 配对
    // ---------------------------------------------------------------------

    private final Discovery discovery = new Discovery();
    private final java.util.concurrent.ExecutorService scanPool =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void startScan() {
        if (scanHint == null || hostList == null) return;
        scanHint.setText("正在搜索同一 WiFi 下的电脑…");
        hostList.removeAllViews();
        scanPool.execute(new Runnable() {
            @Override
            public void run() {
                discovery.scan(2600, new Discovery.Callback() {
                    @Override
                    public void done(final java.util.List<Discovery.Host> hosts, final String error) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                renderHosts(hosts, error);
                            }
                        });
                    }
                });
            }
        });
    }

    private void renderHosts(java.util.List<Discovery.Host> hosts, String error) {
        if (hostList == null) return;
        hostList.removeAllViews();
        if (error != null) {
            scanHint.setText("搜索失败：" + error);
            return;
        }
        if (hosts == null || hosts.isEmpty()) {
            scanHint.setText("没搜到电脑：确认电脑端插件在跑，或用手动输入");
            return;
        }
        scanHint.setText("找到 " + hosts.size() + " 台，点一下配对");
        for (final Discovery.Host host : hosts) {
            hostList.addView(hostRow(host), wrap());
        }
    }

    private View hostRow(final Discovery.Host host) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.stroke(Ui.PANEL, 9f, Ui.LINE, 1f));
        box.setPadding(Ui.dp(8), Ui.dp(5), Ui.dp(8), Ui.dp(5));

        boolean saved = host.endpoint().equals(store.host()) && !store.token().isEmpty();
        TextView name = Ui.text(this, host.label(), 10.5f, Ui.TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name, wrap());

        StringBuilder meta = new StringBuilder(host.endpoint());
        if (host.running) meta.append(" · 运行中");
        if (saved) meta.append(" · 已配对");
        TextView sub = Ui.text(this, meta.toString(), 8.5f, saved ? Ui.GREEN : Ui.MUTED, false);
        box.addView(sub, wrap());

        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onHostTapped(host);
            }
        });

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        Ui.margins(box, 0, 0, 0, 4);
        wrapper.addView(box, wrap());
        return wrapper;
    }

    private void onHostTapped(Discovery.Host host) {
        String endpoint = host.endpoint();
        // 之前配对过同一台电脑：直接连（带上备用地址），不再输码。
        boolean known = false;
        for (String saved : store.endpoints()) {
            if (saved.equals(endpoint)) known = true;
        }
        if (known && !store.token().isEmpty()) {
            if (setupStatus != null) setupStatus.setText("正在连接…");
            resetFeed();
            net.connect(store.endpoints(), store.token());
            show(Screen.HOME);
            return;
        }
        pendingHost = endpoint;
        pendingHostLabel = host.label();
        showPair();
    }

    private String pendingHost = "";
    private String pendingHostLabel = "";
    private EditText pairCodeInput;
    private TextView pairStatus;
    private View pairScreenView;

    private View buildPair() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = row();
        TextView back = Ui.text(this, "←", 16f, Ui.MUTED, false);
        back.setPadding(Ui.dp(2), 0, Ui.dp(8), Ui.dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(Screen.SETUP);
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Ui.text(this, "配对", 13f, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head, wrap());

        TextView hostLabel = Ui.text(this, pendingHostLabel + "\n" + pendingHost, 9f, Ui.MUTED, false);
        hostLabel.setMaxLines(2);
        Ui.margins(hostLabel, 0, 2, 0, 6);
        root.addView(hostLabel, wrap());

        TextView tip = Ui.text(this, "在电脑浏览器打开 127.0.0.1:8765/pair 看 6 位配对码", 8.5f, Ui.MUTED, false);
        Ui.margins(tip, 0, 0, 0, 4);
        root.addView(tip, wrap());

        pairCodeInput = editText("6 位配对码", false);
        pairCodeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairCodeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(16f));
        pairCodeInput.setGravity(Gravity.CENTER);
        pairCodeInput.setLetterSpacing(0.2f);
        root.addView(pairCodeInput, wrap());

        TextView pairButton = Ui.button(this, "配对并连接", 12f, Ui.ACCENT, 0xFFFFFFFF);
        Ui.margins(pairButton, 0, 8, 0, 0);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doPair();
            }
        });
        root.addView(pairButton, wrap());

        pairStatus = Ui.text(this, "输入电脑上显示的 6 位数字", 9f, Ui.MUTED, false);
        Ui.margins(pairStatus, 0, 6, 0, 0);
        root.addView(pairStatus, wrap());

        TextView useToken = Ui.text(this, "改用令牌连接 →", 8.5f, Ui.MUTED, false);
        Ui.margins(useToken, 0, 8, 0, 0);
        useToken.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hostInput != null) hostInput.setText(pendingHost);
                if (setupStatus != null) setupStatus.setText("把令牌填进「配对码或令牌」后点连接");
                show(Screen.SETUP);
            }
        });
        root.addView(useToken, wrap());
        return root;
    }

    private void showPair() {
        if (pairScreenView == null) pairScreenView = buildPair();
        show(Screen.PAIR);
        if (pairCodeInput != null) pairCodeInput.setText("");
        if (pairStatus != null) pairStatus.setText("输入电脑上显示的 6 位数字");
    }

    private void doPair() {
        String code = pairCodeInput == null ? "" : pairCodeInput.getText().toString().trim();
        if (code.length() < 4) {
            if (pairStatus != null) pairStatus.setText("配对码是电脑上显示的 6 位数字");
            return;
        }
        hideKeyboard();
        if (pairStatus != null) pairStatus.setText("配对中…");
        net.pair(pendingHost, code, new Net.PairCallback() {
            @Override
            public void done(final boolean ok, final String message, final String token, final String[] addresses) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            if (pairStatus != null) pairStatus.setText(message);
                            return;
                        }
                        store.saveEndpoints(addresses == null || addresses.length == 0
                                ? new String[]{pendingHost} : addresses, token);
                        resetFeed();
                        net.connect(store.endpoints(), token);
                        if (pairStatus != null) pairStatus.setText("配对成功");
                        show(Screen.HOME);
                    }
                });
            }
        });
    }

    private EditText editText(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.MUTED);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(11f));
        e.setBackground(Ui.stroke(Ui.PANEL, 9f, Ui.LINE, 1f));
        e.setPadding(Ui.dp(8), Ui.dp(7), Ui.dp(8), Ui.dp(7));
        e.setSingleLine(!multiline);
        if (multiline) {
            e.setMinLines(2);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        return e;
    }

    private void doConnect() {
        final String host = hostInput.getText().toString().trim();
        final String token = tokenInput.getText().toString().trim();
        if (host.isEmpty() || token.isEmpty()) {
            setupStatus.setText("服务器和令牌都要填");
            return;
        }
        hideKeyboard();
        // 手输的如果正好是 6 位数字，当成配对码用 —— 和自动发现走同一条路。
        if (token.matches("\\d{6}")) {
            setupStatus.setText("用配对码连接中…");
            net.pair(host, token, new Net.PairCallback() {
                @Override
                public void done(final boolean ok, final String message, final String issued, final String[] addresses) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!ok) {
                                setupStatus.setText(message);
                                return;
                            }
                            store.saveEndpoints(addresses == null || addresses.length == 0
                                    ? new String[]{host} : addresses, issued);
                            resetFeed();
                            net.connect(store.endpoints(), issued);
                            show(Screen.HOME);
                        }
                    });
                }
            });
            return;
        }
        store.promote(host, token);
        setupStatus.setText("连接中…");
        resetFeed();
        net.connect(store.endpoints(), token);
        show(Screen.HOME);
    }

    /** 换服务器/换会话时把旧的本地内容清掉，避免上一次的事件残留在列表里。 */
    private void resetFeed() {
        events.clear();
        rows.clear();
        approvals.clear();
        approvalCurrentId = null;
        sessionId = "";
        sessionTitle = "";
        running = false;
        if (eventList != null) eventList.removeAllViews();
        updateHeader();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // 主屏
    // ---------------------------------------------------------------------

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = row();
        dot = Ui.dot(this, Ui.MUTED);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(9), Ui.dp(9));
        dotLp.setMargins(0, 0, Ui.dp(6), 0);
        header.addView(dot, dotLp);

        headerText = Ui.text(this, "DSH Watch", 13f, Ui.TEXT, true);
        headerText.setSingleLine(true);
        headerText.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(headerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = Ui.text(this, "⚙", 15f, Ui.MUTED, false);
        gear.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(2), Ui.dp(2));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hostInput != null) {
                    hostInput.setText(store.host());
                    tokenInput.setText(store.token());
                }
                if (setupStatus != null) setupStatus.setText(statusDetail);
                show(Screen.SETUP);
            }
        });
        header.addView(gear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, wrap());

        sessionText = Ui.text(this, statusDetail, 9.5f, Ui.MUTED, false);
        sessionText.setSingleLine(true);
        sessionText.setEllipsize(TextUtils.TruncateAt.END);
        Ui.margins(sessionText, 0, 1, 0, 5);
        root.addView(sessionText, wrap());

        eventScroll = new ScrollView(this);
        eventScroll.setVerticalScrollBarEnabled(false);
        eventScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        eventList = new LinearLayout(this);
        eventList.setOrientation(LinearLayout.VERTICAL);
        eventScroll.addView(eventList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(eventScroll, scrollLp);

        LinearLayout actions = row();

        TextView compose = Ui.button(this, "指令", 11f, Ui.PANEL2, Ui.TEXT);
        compose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(Screen.COMPOSE);
            }
        });
        actions.addView(compose, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        approvalButton = Ui.button(this, "审批", 11f, Ui.PANEL2, Ui.TEXT);
        approvalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (approvals.isEmpty()) {
                    sessionText.setText("当前没有待审批");
                } else {
                    show(Screen.APPROVAL);
                }
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMargins(Ui.dp(5), 0, Ui.dp(5), 0);
        actions.addView(approvalButton, lp2);

        TextView stop = Ui.button(this, "停", 11f, Ui.PANEL2, Ui.dim(Ui.TEXT, 0.8f));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                net.cancelTurn(new Net.Callback() {
                    @Override
                    public void done(final boolean ok, final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (sessionText != null) sessionText.setText(message);
                            }
                        });
                    }
                });
            }
        });
        actions.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.75f));

        Ui.margins(actions, 0, 5, 0, 0);
        root.addView(actions, wrap());
        return root;
    }

    private String statusText() {
        if (statusState == 0) return statusDetail;
        boolean usb = net.currentBase().contains("127.0.0.1");
        return (running ? "模型在跑 · 会实时推送动作" : "待命 · 可以发指令") + (usb ? " · USB" : "");
    }

    private void updateHeader() {
        if (headerText == null) return;
        String title = sessionTitle.isEmpty() ? (sessionId.isEmpty() ? "DSH Watch" : sessionId) : sessionTitle;
        headerText.setText(title);
        if (sessionText != null) sessionText.setText(statusText());
        if (dot != null) {
            int color = statusState == 0 ? Ui.MUTED : (running ? Ui.WARN : Ui.GREEN);
            dot.setBackground(Ui.bg(color, 20f));
        }
        if (approvalButton != null) {
            int count = approvals.size();
            approvalButton.setText(count > 0 ? "审批 " + count : "审批");
            approvalButton.setTextColor(count > 0 ? 0xFF120C00 : Ui.TEXT);
            approvalButton.setBackground(Ui.stroke(count > 0 ? Ui.WARN : Ui.PANEL2, 10f, Ui.LINE, 1f));
        }
    }

    private void renderEvents() {
        if (eventList == null) return;
        eventList.removeAllViews();
        rows.clear();
        int from = Math.max(0, events.size() - 40);
        for (int i = from; i < events.size(); i++) {
            View rowView = makeRow(events.get(i));
            eventList.addView(rowView, wrap());
            rows.add(rowView);
        }
        scrollBottom();
    }

    private View makeRow(Event e) {
        TextView tv = Ui.text(this, e.line(), 10f, e.color(), false);
        tv.setMaxLines(4);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(0, Ui.dp(2), 0, Ui.dp(2));
        return tv;
    }

    private void appendRow(Event e) {
        if (eventList == null) return;
        View rowView = makeRow(e);
        eventList.addView(rowView, wrap());
        rows.add(rowView);
        while (rows.size() > MAX_EVENTS) {
            View first = rows.remove(0);
            eventList.removeView(first);
        }
        scrollBottom();
    }

    private void scrollBottom() {
        if (eventScroll == null) return;
        eventScroll.post(new Runnable() {
            @Override
            public void run() {
                if (eventScroll != null) eventScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ---------------------------------------------------------------------
    // 会话选择屏：新建 / 切换（左滑进入）
    // ---------------------------------------------------------------------

    private View buildSessions() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = row();
        TextView back = Ui.text(this, "←", 16f, Ui.MUTED, false);
        back.setPadding(Ui.dp(2), 0, Ui.dp(8), Ui.dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(Screen.HOME);
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(this, "会话", 12f, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView create = Ui.button(this, "＋ 新建", 9.5f, Ui.ACCENT, 0xFFFFFFFF);
        create.setPadding(Ui.dp(7), Ui.dp(5), Ui.dp(7), Ui.dp(5));
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sessionsHint != null) sessionsHint.setText("正在新建…");
                net.newSession(new Net.Callback() {
                    @Override
                    public void done(final boolean ok, final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) {
                                    resetFeed();
                                    show(Screen.HOME);
                                } else if (sessionsHint != null) {
                                    sessionsHint.setText(message);
                                }
                            }
                        });
                    }
                });
            }
        });
        head.addView(create, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(head, wrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sessionList = new LinearLayout(this);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(sessionList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.setMargins(0, Ui.dp(4), 0, 0);
        root.addView(scroll, scrollLp);

        sessionsHint = Ui.text(this, "左滑=会话 · 右滑=返回", 8.5f, Ui.MUTED, false);
        Ui.margins(sessionsHint, 0, 4, 0, 0);
        root.addView(sessionsHint, wrap());
        return root;
    }

    private void loadSessions() {
        if (sessionList == null) return;
        sessionList.removeAllViews();
        if (sessionsHint != null) sessionsHint.setText("读取会话列表…");
        net.listSessions(new Net.SessionsCallback() {
            @Override
            public void done(final boolean ok, final String message, final java.util.List<Net.SessionInfo> sessions) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sessionList == null) return;
                        sessionList.removeAllViews();
                        if (!ok) {
                            if (sessionsHint != null) sessionsHint.setText("读取失败：" + message);
                            return;
                        }
                        int count = 0;
                        for (final Net.SessionInfo info : sessions) {
                            if (info == null) continue;
                            sessionList.addView(sessionRow(info), wrap());
                            count++;
                        }
                        if (sessionsHint != null) {
                            sessionsHint.setText(count == 0
                                    ? "没有会话，点「＋ 新建」开一个"
                                    : "共 " + count + " 个 · 点一下切换");
                        }
                    }
                });
            }
        });
    }

    private View sessionRow(final Net.SessionInfo info) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        boolean current = info.id.equals(sessionId);
        box.setBackground(Ui.stroke(current ? Ui.PANEL2 : Ui.PANEL, 9f,
                current ? Ui.ACCENT : Ui.LINE, 1f));
        box.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));

        String titleText = info.title == null || info.title.isEmpty() ? info.id : info.title;
        TextView name = Ui.text(this, titleText, 10.5f, Ui.TEXT, current);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name, wrap());

        StringBuilder meta = new StringBuilder();
        if (current) meta.append("当前");
        if (info.running) meta.append(meta.length() > 0 ? " · " : "").append("运行中");
        if (!info.live) meta.append(meta.length() > 0 ? " · " : "").append("已保存");
        if (meta.length() == 0) meta.append("空闲");
        TextView sub = Ui.text(this, meta.toString(), 8.5f, current ? Ui.BLUE : Ui.MUTED, false);
        Ui.margins(sub, 0, 1, 0, 0);
        box.addView(sub, wrap());

        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sessionsHint != null) sessionsHint.setText("切换中…");
                net.selectSession(info.id, new Net.Callback() {
                    @Override
                    public void done(final boolean ok, final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) {
                                    resetFeed();
                                    show(Screen.HOME);
                                } else if (sessionsHint != null) {
                                    sessionsHint.setText(message);
                                }
                            }
                        });
                    }
                });
            }
        });

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        Ui.margins(box, 0, 0, 0, 5);
        wrapper.addView(box, wrap());
        return wrapper;
    }

    // ---------------------------------------------------------------------
    // 发指令
    // ---------------------------------------------------------------------

    private View buildCompose() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = row();
        TextView back = Ui.text(this, "←", 16f, Ui.MUTED, false);
        back.setPadding(Ui.dp(2), 0, Ui.dp(8), Ui.dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                show(Screen.HOME);
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Ui.text(this, "给电脑上的 DSH 发指令", 11f, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head, wrap());

        composeInput = editText("例如：看看现在目录里有什么", true);
        composeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(10.5f));
        // 有 weight 撑开，不需要 minLines 保底；不设会把下面的提示挤出屏幕。
        composeInput.setMinLines(1);
        composeInput.setMinHeight(0);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        inputLp.setMargins(0, Ui.dp(4), 0, Ui.dp(4));
        root.addView(composeInput, inputLp);
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final String[] presets = {"继续", "现在在干什么", "总结一下", "跑测试"};
        for (int i = 0; i < presets.length; i++) {
            final String preset = presets[i];
            TextView chip = Ui.button(this, preset, 9.5f, Ui.PANEL2, Ui.TEXT);
            chip.setPadding(Ui.dp(8), Ui.dp(5), Ui.dp(8), Ui.dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, Ui.dp(4), 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String text = composeInput.getText().toString();
                    composeInput.setText(text.isEmpty() ? preset : text + "\n" + preset);
                    composeInput.setSelection(composeInput.getText().length());
                }
            });
            chips.addView(chip, lp);
        }
        TextView stopChip = Ui.button(this, "停", 9.5f, Ui.PANEL2, Ui.dim(Ui.TEXT, 0.85f));
        stopChip.setPadding(Ui.dp(8), Ui.dp(5), Ui.dp(8), Ui.dp(5));
        stopChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                net.cancelTurn(new Net.Callback() {
                    @Override
                    public void done(final boolean ok, final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setComposeHint(message);
                            }
                        });
                    }
                });
            }
        });
        chips.addView(stopChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chipsScroll.addView(chips);
        root.addView(chipsScroll, wrap());

        LinearLayout actions = row();
        TextView send = Ui.button(this, "发送", 11f, Ui.ACCENT, 0xFFFFFFFF);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSend(false);
            }
        });
        actions.addView(send, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView steer = Ui.button(this, "插入", 11f, Ui.PANEL2, Ui.TEXT);
        steer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSend(true);
            }
        });
        LinearLayout.LayoutParams steerLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        steerLp.setMargins(Ui.dp(5), 0, 0, 0);
        actions.addView(steer, steerLp);

        TextView fresh = Ui.button(this, "＋", 12f, Ui.PANEL2, Ui.TEXT);
        fresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                net.newSession(new Net.Callback() {
                    @Override
                    public void done(final boolean ok, final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) show(Screen.HOME);
                                else setComposeHint(message);
                            }
                        });
                    }
                });
            }
        });
        LinearLayout.LayoutParams freshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        freshLp.setMargins(Ui.dp(5), 0, 0, 0);
        actions.addView(fresh, freshLp);

        Ui.margins(actions, 0, 5, 0, 0);
        root.addView(actions, wrap());

        composeHint = Ui.text(this, "", 8.5f, Ui.MUTED, false);
        Ui.margins(composeHint, 0, 4, 0, 2);
        root.addView(composeHint, wrap());
        return root;
    }

    private void refreshCompose() {
        if (composeHint != null) {
            composeHint.setText(running
                    ? "模型在跑：发送=排队下一轮，插入=立刻插话"
                    : "空闲：发送=开一轮；插入=等下一步");
        }
    }

    private void setComposeHint(String message) {
        if (composeHint != null) composeHint.setText(message);
    }

    private void doSend(final boolean steer) {
        String text = composeInput == null ? "" : composeInput.getText().toString().trim();
        if (text.isEmpty()) {
            setComposeHint("指令是空的");
            return;
        }
        hideKeyboard();
        net.sendPrompt(text, steer, new Net.Callback() {
            @Override
            public void done(final boolean ok, final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            composeInput.setText("");
                            show(Screen.HOME);
                        } else {
                            setComposeHint(message);
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------------
    // 审批
    // ---------------------------------------------------------------------

    private View buildApproval() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = row();
        TextView back = Ui.text(this, "←", 16f, Ui.MUTED, false);
        back.setPadding(Ui.dp(2), 0, Ui.dp(8), Ui.dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(Screen.HOME);
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Ui.text(this, "提权审批", 12f, Ui.WARN, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        approvalProgress = Ui.text(this, "", 9f, Ui.MUTED, false);
        head.addView(approvalProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(head, wrap());

        approvalTool = Ui.text(this, "", 11f, Ui.TEXT, true);
        approvalTool.setSingleLine(true);
        approvalTool.setEllipsize(TextUtils.TruncateAt.END);
        Ui.margins(approvalTool, 0, 2, 0, 2);
        root.addView(approvalTool, wrap());

        approvalReason = Ui.text(this, "", 9.5f, Ui.MUTED, false);
        approvalReason.setMaxLines(3);
        root.addView(approvalReason, wrap());

        ScrollView previewScroll = new ScrollView(this);
        previewScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        approvalPreview = Ui.text(this, "", 9f, Ui.BLUE, false);
        approvalPreview.setTypeface(Typeface.MONOSPACE);
        approvalPreview.setTextIsSelectable(true);
        previewScroll.addView(approvalPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        previewLp.setMargins(0, Ui.dp(4), 0, Ui.dp(4));
        root.addView(previewScroll, previewLp);

        LinearLayout actions = row();
        TextView allow = Ui.button(this, "允许", 11.5f, Ui.OK, 0xFFFFFFFF);
        allow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                decide(true);
            }
        });
        actions.addView(allow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView deny = Ui.button(this, "拒绝", 11.5f, Ui.DANGER, 0xFFFFFFFF);
        deny.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                decide(false);
            }
        });
        LinearLayout.LayoutParams denyLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        denyLp.setMargins(Ui.dp(6), 0, 0, 0);
        actions.addView(deny, denyLp);
        root.addView(actions, wrap());

        approvalCountdown = Ui.text(this, "", 8.5f, Ui.MUTED, false);
        Ui.margins(approvalCountdown, 0, 4, 0, 0);
        root.addView(approvalCountdown, wrap());
        return root;
    }

    private void renderApproval() {
        if (approvals.isEmpty()) {
            approvalCurrentId = null;
            if (approvalTool != null) approvalTool.setText("没有待审批");
            if (approvalReason != null) approvalReason.setText("");
            if (approvalPreview != null) approvalPreview.setText("");
            if (approvalCountdown != null) approvalCountdown.setText("");
            if (approvalProgress != null) approvalProgress.setText("");
            return;
        }
        Approval a = null;
        if (approvalCurrentId != null && approvals.containsKey(approvalCurrentId)) {
            a = approvals.get(approvalCurrentId);
        }
        if (a == null) {
            a = approvals.values().iterator().next();
            approvalCurrentId = a.id;
        }
        int index = 0;
        for (String key : approvals.keySet()) {
            if (key.equals(a.id)) break;
            index++;
        }
        approvalProgress.setText((index + 1) + "/" + approvals.size());
        approvalTool.setText(a.tool.isEmpty() ? "需要授权" : a.tool);
        approvalReason.setText(a.reason.isEmpty() ? "模型请求提升权限以继续这一步。" : a.reason);
        approvalPreview.setText(a.preview);
        updateCountdown();
    }

    private void updateCountdown() {
        if (approvalCountdown == null) return;
        if (approvalCurrentId == null || !approvals.containsKey(approvalCurrentId)) {
            approvalCountdown.setText("");
            return;
        }
        Approval a = approvals.get(approvalCurrentId);
        int sec = a.secondsLeft();
        approvalCountdown.setText(sec > 0 ? (sec + "s 后自动转交电脑端弹窗") : "已超时，转交电脑端");
        if (sec > 0) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateCountdown();
                }
            }, 1000);
        }
    }

    private void decide(boolean allow) {
        if (approvalCurrentId == null) {
            show(Screen.HOME);
            return;
        }
        final String id = approvalCurrentId;
        approvals.remove(id);
        approvalCurrentId = null;
        net.decide(id, allow, new Net.Callback() {
            @Override
            public void done(final boolean ok, final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok && sessionText != null) sessionText.setText(message);
                    }
                });
            }
        });
        buzz(allow ? 40 : 80);
        if (approvals.isEmpty()) {
            show(Screen.HOME);
        } else {
            renderApproval();
        }
    }

    // ---------------------------------------------------------------------
    // Net.Listener（后台线程 → 主线程）
    // ---------------------------------------------------------------------

    @Override
    public void onStatus(final int state, final String detail) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusState = state;
                statusDetail = detail;
                if (dot != null) {
                    dot.setBackground(Ui.bg(state == 0 ? Ui.MUTED : (running ? Ui.WARN : Ui.GREEN), 20f));
                }
                if (sessionText != null && screen == Screen.HOME) {
                    sessionText.setText(running ? "模型在跑 · 会实时推送动作" : detail);
                }
                if (setupStatus != null && screen == Screen.SETUP) setupStatus.setText(detail);
            }
        });
    }

    @Override
    public void onEvent(final Event event) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (event.seq > 0) {
                    for (int i = events.size() - 1; i >= 0 && i >= events.size() - 20; i--) {
                        if (events.get(i).seq == event.seq) return;
                    }
                }
                if ("status".equals(event.kind) || "turn".equals(event.kind)) {
                    running = event.running;
                    updateHeader();
                    return;
                }
                if ("title".equals(event.kind)) {
                    if (event.text != null && !event.text.isEmpty()) {
                        sessionTitle = event.text;
                        updateHeader();
                    }
                    return;
                }
                if (!event.visible()) return;
                events.add(event);
                if (events.size() > MAX_EVENTS) events.remove(0);
                if (screen == Screen.HOME) appendRow(event);
            }
        });
    }

    @Override
    public void onSession(final String id, final String title, final boolean isRunning) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                String nextId = id == null ? "" : id;
                if (!nextId.isEmpty() && !nextId.equals(sessionId)) {
                    // 服务端换了会话：清掉旧内容再显示新会话。
                    events.clear();
                    rows.clear();
                    if (eventList != null) eventList.removeAllViews();
                    approvals.clear();
                    approvalCurrentId = null;
                }
                sessionId = nextId;
                String nextTitle = title == null ? "" : title;
                if (!nextTitle.isEmpty()) sessionTitle = nextTitle;
                running = isRunning;
                updateHeader();
            }
        });
    }

    @Override
    public void onApproval(final Approval approval) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                boolean isNew = !approvals.containsKey(approval.id);
                approvals.put(approval.id, approval);
                updateHeader();
                if (isNew) {
                    buzz(120);
                    if ((screen == Screen.HOME || screen == Screen.APPROVAL) && approvalCurrentId == null) {
                        approvalCurrentId = approval.id;
                        show(Screen.APPROVAL);
                    }
                }
            }
        });
    }

    @Override
    public void onApprovalResolved(final String id) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (approvals.remove(id) == null) return;
                if (id.equals(approvalCurrentId)) {
                    approvalCurrentId = null;
                    if (screen == Screen.APPROVAL) {
                        if (approvals.isEmpty()) show(Screen.HOME);
                        else renderApproval();
                    }
                } else if (screen == Screen.APPROVAL) {
                    renderApproval();
                }
                updateHeader();
            }
        });
    }

    @Override
    public void onLog(final String line) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (setupStatus != null && screen == Screen.SETUP) setupStatus.setText(line);
            }
        });
    }

    /** 电脑报了外网地址：记下来当备用，离开局域网时自动切过去。 */
    @Override
    public void onRemote(final String url) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (url == null || url.isEmpty()) return;
                store.addEndpoint(url);
                net.addEndpoint(url);
                if (remoteHint != null) {
                    remoteHint.setText("外网地址已记住：" + url + "\n离开 WiFi 后会自动切到它");
                }
            }
        });
    }

    /**
     * 拿状态快照里的事件补进列表。
     * 既用于“刚连上补历史”，也用于“外网隧道把 SSE 缓冲了、只能靠轮询”的情况：
     * 按 seq 增量合并，只追加没见过的，不会重复。
     */
    @Override
    public void onBackfill(final java.util.List<Event> past) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (past == null || past.isEmpty()) return;
                long newest = 0;
                for (Event event : events) {
                    if (event.seq > newest) newest = event.seq;
                }
                boolean added = false;
                for (Event event : past) {
                    if (event.seq <= newest) continue;
                    events.add(event);
                    newest = event.seq;
                    added = true;
                }
                if (!added) return;
                while (events.size() > MAX_EVENTS) events.remove(0);
                if (screen == Screen.HOME) renderEvents();
            }
        });
    }
}
