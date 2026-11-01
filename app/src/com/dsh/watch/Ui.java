package com.dsh.watch;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 尺寸与配色。
 *
 * DPI 自适应：以 189dp 宽（本机 378px / 320dpi）为基准设计，按实际屏幕宽度线性缩放，
 * 上下夹在 0.85x ~ 1.9x。所有 dp/sp 都过这里，换到 200dp / 300dp 的表上也按比例放大，
 * 不会出现字体挤成一坨或按钮点不到。
 */
final class Ui {
    static final int BG = 0xFF0B0D12;
    static final int PANEL = 0xFF131826;
    static final int PANEL2 = 0xFF1B2334;
    static final int LINE = 0xFF243149;
    static final int TEXT = 0xFFE8ECF4;
    static final int MUTED = 0xFF8794AC;
    static final int ACCENT = 0xFF2B6CF6;
    static final int OK = 0xFF1F8F4E;
    static final int DANGER = 0xFF8F2431;
    static final int WARN = 0xFFFFB020;
    static final int GREEN = 0xFF3DDC84;
    static final int PURPLE = 0xFFC9A7FF;
    static final int BLUE = 0xFF9FC3FF;

    private static float density = 2f;
    private static float scale = 1f;
    private static boolean round = false;
    private static boolean roundForced = false;

    private Ui() {
    }

    static void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;
        float widthDp = ctx.getResources().getDisplayMetrics().widthPixels / density;
        float raw = widthDp / 189f;
        scale = Math.max(0.85f, Math.min(1.9f, raw));
        if (!roundForced) {
            try {
                round = ctx.getResources().getConfiguration().isScreenRound();
            } catch (Throwable ignored) {
                round = false;
            }
        }
    }

    /** 首次启动由用户选形状后强制生效（优先于系统上报，很多方表其实四角圆但仍是矩形）。 */
    static void setShape(String shape) {
        if ("round".equals(shape)) {
            round = true;
            roundForced = true;
        } else if ("square".equals(shape)) {
            round = false;
            roundForced = true;
        } else {
            roundForced = false;
        }
    }

    static float scale() {
        return scale;
    }

    static boolean isRound() {
        return round;
    }

    static int dp(float value) {
        return Math.round(value * scale * density);
    }

    static float sp(float value) {
        return (value * scale) * (fontScale() >= 1f ? Math.min(1.25f, fontScale()) : fontScale());
    }

    private static float fontScale = 1f;

    static void setFontScale(float v) {
        fontScale = v <= 0f ? 1f : v;
    }

    private static float fontScale() {
        return fontScale;
    }

    /** 全屏圆表：左右各留出内切圆的边距，矩形表返回 0。 */
    static int roundInset(int widthPx) {
        if (!round) return 0;
        return Math.round(widthPx * 0.145f);
    }

    static GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    static GradientDrawable stroke(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = bg(color, radiusDp);
        d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static TextView text(Context ctx, CharSequence value, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(dp(1f), 1f);
        return tv;
    }

    static TextView button(Context ctx, CharSequence label, float sizeSp, int bgColor, int textColor) {
        TextView tv = text(ctx, label, sizeSp, textColor, true);
        tv.setBackground(stroke(bgColor, 10f, LINE, 1f));
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(8), dp(9), dp(8), dp(9));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    static void margins(View v, float l, float t, float r, float b) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp == null) return;
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(lp);
    }

    static View dot(Context ctx, int color) {
        View v = new View(ctx);
        v.setBackground(bg(color, 20f));
        return v;
    }

    static int dim(int color, float alpha) {
        return Color.argb((int) (alpha * 255), Color.red(color), Color.green(color), Color.blue(color));
    }
}
