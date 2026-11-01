package com.dsh.watch;

import org.json.JSONObject;

/** 一条要显示在手表上的事件（由电脑端 SSE 推来）。 */
final class Event {
    long seq;
    String kind = "";
    String text = "";
    String name = "";
    boolean ok = true;
    boolean running;
    String sessionId = "";

    static Event from(JSONObject o) {
        Event e = new Event();
        e.seq = o.optLong("seq", 0);
        e.kind = o.optString("kind", "");
        e.text = o.optString("text", "");
        e.name = o.optString("name", "");
        e.ok = o.optBoolean("ok", true);
        e.running = o.optBoolean("running", false);
        e.sessionId = o.optString("sessionId", "");
        return e;
    }

    boolean visible() {
        switch (kind) {
            case "user":
            case "assistant":
            case "tool":
            case "tool-result":
            case "info":
                return true;
            default:
                return false;
        }
    }

    /** 列表里显示的一行文字。 */
    String line() {
        switch (kind) {
            case "user":
                return "你 · " + text;
            case "assistant":
                return text;
            case "tool":
                return "▸ " + (name.isEmpty() ? "工具" : name) + "  " + text;
            case "tool-result":
                return (ok ? "✓ " : "✗ ") + text;
            case "info":
                return "· " + text;
            default:
                return text;
        }
    }

    int color() {
        switch (kind) {
            case "user":
                return Ui.BLUE;
            case "assistant":
                return Ui.TEXT;
            case "tool":
                return Ui.PURPLE;
            case "tool-result":
                return ok ? Ui.dim(Ui.TEXT, 0.62f) : Ui.WARN;
            case "info":
                return Ui.MUTED;
            default:
                return Ui.MUTED;
        }
    }
}
