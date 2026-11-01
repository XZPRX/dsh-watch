package com.dsh.watch;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 局域网自动发现：往 UDP 8766 广播一句 DSHWATCH_DISCOVER，电脑端插件会回一条 JSON。
 *
 * 用裸 DatagramSocket（系统自带），不引第三方库，也不用 mDNS —— 手表上少一个失败点。
 */
final class Discovery {
    static final int PORT = 8766;
    private static final String PROBE = "DSHWATCH_DISCOVER";

    static final class Host {
        String name = "";
        String address = "";
        int port = 8765;
        int sessions;
        boolean running;
        boolean pairing;

        String endpoint() {
            return address + ":" + port;
        }

        String label() {
            return name == null || name.isEmpty() ? address : name;
        }
    }

    interface Callback {
        void done(List<Host> hosts, String error);
    }

    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
    }

    /** 阻塞扫描（放到线程池里跑）：广播 + 收集应答，默认 2.6 秒。 */
    void scan(int timeoutMs, Callback callback) {
        cancelled = false;
        DatagramSocket socket = null;
        LinkedHashMap<String, Host> found = new LinkedHashMap<String, Host>();
        String error = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(400);
            byte[] probe = PROBE.getBytes("UTF-8");
            for (String broadcast : broadcastTargets()) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length, InetAddress.getByName(broadcast), PORT));
                } catch (Throwable ignored) {
                    // 某个网卡不支持广播就跳过
                }
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[2048];
            while (!cancelled && System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    Host host = parse(text, packet.getAddress().getHostAddress());
                    if (host != null) found.put(host.endpoint(), host);
                    if (found.size() >= 8) break;
                } catch (java.net.SocketTimeoutException timeout) {
                    // 继续等
                }
            }
        } catch (Throwable throwable) {
            error = Net.shortError(throwable);
        } finally {
            if (socket != null) socket.close();
        }
        callback.done(new ArrayList<Host>(found.values()), error);
    }

    private static Host parse(String text, String remoteAddress) {
        try {
            JSONObject json = new JSONObject(text);
            if (!"dshwatch".equals(json.optString("t", ""))) return null;
            Host host = new Host();
            host.address = remoteAddress;
            host.name = json.optString("name", "");
            host.port = json.optInt("port", 8765);
            host.sessions = json.optInt("sessions", 0);
            host.running = json.optBoolean("running", false);
            host.pairing = json.optBoolean("pairing", false);
            return host;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 255.255.255.255 + 每个网卡按 /24 推出来的广播地址。 */
    private static List<String> broadcastTargets() {
        List<String> targets = new ArrayList<String>();
        targets.add("255.255.255.255");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) continue;
                    byte[] raw = address.getAddress();
                    if (raw.length != 4) continue;
                    short first = (short) (raw[0] & 0xFF);
                    if (first == 127 || (first == 169 && (raw[1] & 0xFF) == 254)) continue;
                    String guess = (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + ".255";
                    if (!targets.contains(guess)) targets.add(guess);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到网卡就用 255.255.255.255
        }
        return Collections.unmodifiableList(targets);
    }
}
