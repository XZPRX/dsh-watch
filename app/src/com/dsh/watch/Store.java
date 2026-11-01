package com.dsh.watch;

import android.content.SharedPreferences;
import android.content.Context;

/** 本地保存：服务器地址、配对令牌、上次选中的会话。 */
final class Store {
    private final SharedPreferences sp;

    Store(Context ctx) {
        sp = ctx.getSharedPreferences("dsh-watch", Context.MODE_PRIVATE);
    }

    String host() {
        return sp.getString("host", "");
    }

    String token() {
        return sp.getString("token", "");
    }

    String sessionId() {
        return sp.getString("sessionId", "");
    }

    /** 表盘形状：首次启动由用户选择，'round' / 'square'，未选为空。 */
    String shape() {
        return sp.getString("shape", "");
    }

    void saveShape(String shape) {
        sp.edit().putString("shape", shape).apply();
    }

    void save(String host, String token) {
        sp.edit().putString("host", host).putString("altHosts", "").putString("token", token).apply();
    }

    /** 保存一组候选地址：hosts[0] 为主，其余作为备用（连不上自动换）。 */
    void saveEndpoints(String[] hosts, String token) {
        if (hosts == null || hosts.length == 0) return;
        StringBuilder alts = new StringBuilder();
        for (int i = 1; i < hosts.length; i++) {
            if (hosts[i] == null || hosts[i].isEmpty()) continue;
            if (alts.length() > 0) alts.append(',');
            alts.append(hosts[i]);
        }
        sp.edit().putString("host", hosts[0]).putString("altHosts", alts.toString()).putString("token", token).apply();
    }

    /** 主地址 + 备用地址，按顺序返回。 */
    String[] endpoints() {
        String primary = host();
        String alts = sp.getString("altHosts", "");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        if (!primary.isEmpty()) set.add(primary);
        if (alts != null && !alts.isEmpty()) {
            for (String item : alts.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) set.add(trimmed);
            }
        }
        return set.toArray(new String[0]);
    }

    /** 记住一个备用地址（比如电脑报的外网隧道地址），已存在就不重复加。 */
    void addEndpoint(String url) {
        if (url == null) return;
        String clean = url.trim();
        if (clean.isEmpty()) return;
        for (String existing : endpoints()) {
            if (existing.equals(clean)) return;
        }
        String alts = sp.getString("altHosts", "");
        String next = (alts == null || alts.isEmpty()) ? clean : alts + "," + clean;
        sp.edit().putString("altHosts", next).apply();
    }

    /**
     * 把某个地址设为主地址，但**保留**已有备用地址。
     * 这样从局域网切到外网隧道（或反过来）时，另一边的地址不会丢，随时能切回来。
     */
    void promote(String host, String token) {
        if (host == null) return;
        String clean = host.trim();
        if (clean.isEmpty()) return;
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        set.add(clean);
        for (String existing : endpoints()) {
            if (!existing.equals(clean)) set.add(existing);
        }
        StringBuilder alts = new StringBuilder();
        int index = 0;
        for (String item : set) {
            if (index++ == 0) continue;
            if (alts.length() > 0) alts.append(',');
            alts.append(item);
        }
        sp.edit().putString("host", clean).putString("altHosts", alts.toString()).putString("token", token).apply();
    }

    void saveSession(String id) {
        sp.edit().putString("sessionId", id).apply();
    }

    boolean paired() {
        return !host().isEmpty() && !token().isEmpty();
    }
}
