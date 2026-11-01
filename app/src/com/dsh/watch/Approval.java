package com.dsh.watch;

import org.json.JSONObject;

/** 一条等待手表审批的提权请求。 */
final class Approval {
    String id = "";
    String tool = "";
    String reason = "";
    String preview = "";
    long expiresAt = 0;

    static Approval from(JSONObject o) {
        Approval a = new Approval();
        a.id = o.optString("id", "");
        a.tool = o.optString("tool", "");
        a.reason = o.optString("reason", "");
        a.preview = o.optString("preview", o.optString("text", ""));
        a.expiresAt = o.optLong("expiresAt", 0);
        return a;
    }

    int secondsLeft() {
        if (expiresAt <= 0) return 60;
        long left = expiresAt - System.currentTimeMillis();
        return (int) Math.max(0, Math.round(left / 1000.0));
    }
}
