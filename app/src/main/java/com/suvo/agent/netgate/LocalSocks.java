package com.suvo.agent.netgate;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Your Friend — Original project by Suvo.
 *
 * A small SOCKS5 server on this phone only (127.0.0.1). The tunnel engine hands it every
 * connection of the allowed apps; it opens each one through the user's proxy (SOCKS5 or HTTP,
 * with login), taking the proxies in turn when rotation is on and trying the next when one fails.
 * Your Friend is outside the VPN, so these outgoing connections never loop back into it.
 */
final class LocalSocks {

    private static final String TAG = "YF.NetGate";
    private static final int CONNECT_TIMEOUT = 10_000;

    private final List<NetGateStore.Proxy> proxies;
    private final boolean rotate;
    private final AtomicInteger next = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "netgate-relay");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket server;
    private volatile boolean running;

    LocalSocks(List<NetGateStore.Proxy> proxies, boolean rotate) {
        this.proxies = proxies;
        this.rotate = rotate;
    }

    /** Starts listening; returns the port. */
    int start() throws IOException {
        server = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket c = server.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept: " + e.getMessage());
                }
            }
        });
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void handle(Socket c) {
        Socket up = null;
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(30_000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            // Greeting: any methods → "no login" (only this phone can reach us).
            if (in.read() != 5) throw new IOException("not SOCKS5");
            int n = in.read();
            readFully(in, new byte[Math.max(0, n)]);
            out.write(new byte[]{5, 0});
            byte[] h = new byte[4];
            readFully(in, h);
            int cmd = h[1], atyp = h[3];
            String host;
            if (atyp == 1) {
                byte[] a = new byte[4];
                readFully(in, a);
                host = InetAddress.getByAddress(a).getHostAddress();
            } else if (atyp == 4) {
                byte[] a = new byte[16];
                readFully(in, a);
                host = InetAddress.getByAddress(a).getHostAddress();
            } else if (atyp == 3) {
                byte[] a = new byte[in.read()];
                readFully(in, a);
                host = new String(a, StandardCharsets.US_ASCII);
            } else {
                throw new IOException("bad address type");
            }
            int port = (in.read() << 8) | in.read();
            if (cmd != 1) {
                // UDP through a proxy is not offered: apps fall back to TCP (e.g. QUIC → HTTPS).
                out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0});
                return;
            }
            up = open(host, port);
            if (up == null) {
                out.write(new byte[]{5, 5, 0, 1, 0, 0, 0, 0, 0, 0});
                return;
            }
            out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            c.setSoTimeout(0);
            up.setSoTimeout(0);
            final Socket u = up;
            pool.execute(() -> pipe(u, c));
            pipe(c, u);
        } catch (IOException e) {
            // A client that went away, or a broken request.
        } finally {
            close(c);
            close(up);
        }
    }

    /** Through the proxies: the next in turn first (rotation), then the others until one works. */
    private Socket open(String host, int port) {
        int n = proxies.size();
        if (n == 0) return null;
        int first = rotate ? Math.floorMod(next.getAndIncrement(), n) : 0;
        for (int k = 0; k < n; k++) {
            NetGateStore.Proxy p = proxies.get((first + k) % n);
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(p.host, p.port), CONNECT_TIMEOUT);
                s.setSoTimeout(CONNECT_TIMEOUT);
                if ("http".equals(p.type)) httpConnect(s, p, host, port);
                else socksConnect(s, p, host, port);
                return s;
            } catch (IOException e) {
                Log.i(TAG, "proxy " + p.host + ":" + p.port + " failed: " + e.getMessage());
                close(s);
            }
        }
        return null;
    }

    static void socksConnect(Socket s, NetGateStore.Proxy p, String host, int port) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        boolean login = !p.user.isEmpty();
        out.write(login ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        byte[] r = new byte[2];
        readFully(in, r);
        if (r[0] != 5) throw new IOException("not a SOCKS5 proxy");
        if (r[1] == 2) {
            byte[] u = p.user.getBytes(StandardCharsets.UTF_8), pw = p.pass.getBytes(StandardCharsets.UTF_8);
            byte[] a = new byte[3 + u.length + pw.length];
            a[0] = 1;
            a[1] = (byte) u.length;
            System.arraycopy(u, 0, a, 2, u.length);
            a[2 + u.length] = (byte) pw.length;
            System.arraycopy(pw, 0, a, 3 + u.length, pw.length);
            out.write(a);
            readFully(in, r);
            if (r[1] != 0) throw new IOException("proxy login refused");
        } else if (r[1] != 0) {
            throw new IOException("proxy wants a login method we do not have");
        }
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        byte[] req;
        byte[] ip = literal(host);
        if (ip != null) {
            req = new byte[4 + ip.length + 2];
            req[3] = (byte) (ip.length == 4 ? 1 : 4);
            System.arraycopy(ip, 0, req, 4, ip.length);
        } else {
            req = new byte[5 + name.length + 2];
            req[3] = 3;
            req[4] = (byte) name.length;
            System.arraycopy(name, 0, req, 5, name.length);
        }
        req[0] = 5;
        req[1] = 1;
        req[req.length - 2] = (byte) (port >> 8);
        req[req.length - 1] = (byte) port;
        out.write(req);
        byte[] h = new byte[4];
        readFully(in, h);
        if (h[1] != 0) throw new IOException("proxy refused the connection (" + h[1] + ")");
        int skip = h[3] == 1 ? 4 : h[3] == 4 ? 16 : in.read();
        readFully(in, new byte[skip + 2]);
    }

    static void httpConnect(Socket s, NetGateStore.Proxy p, String host, int port) throws IOException {
        String target = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        StringBuilder req = new StringBuilder("CONNECT ").append(target).append(" HTTP/1.1\r\nHost: ").append(target).append("\r\n");
        if (!p.user.isEmpty()) {
            String cred = Base64.encodeToString((p.user + ":" + p.pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            req.append("Proxy-Authorization: Basic ").append(cred).append("\r\n");
        }
        req.append("\r\n");
        s.getOutputStream().write(req.toString().getBytes(StandardCharsets.US_ASCII));
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            head.append((char) b);
            if (head.length() > 8192) throw new IOException("proxy answer too long");
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        String status = head.toString().split("\r\n", 2)[0];
        if (!status.matches("HTTP/1\\.[01] 2\\d\\d.*")) throw new IOException("proxy said: " + status);
    }

    private static byte[] literal(String host) {
        if (!host.matches("[0-9.]+|[0-9a-fA-F:]+")) return null;
        try {
            return InetAddress.getByName(host).getAddress();
        } catch (IOException e) {
            return null;
        }
    }

    private void pipe(Socket from, Socket to) {
        byte[] buf = new byte[16 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("closed");
            off += n;
        }
    }

    private static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** Checks one proxy: opens example.com:80 through it. Null = works, else why not. */
    static String test(NetGateStore.Proxy p) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(p.host, p.port), CONNECT_TIMEOUT);
            s.setSoTimeout(CONNECT_TIMEOUT);
            if ("http".equals(p.type)) httpConnect(s, p, "example.com", 80);
            else socksConnect(s, p, "example.com", 80);
            s.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            int b = s.getInputStream().read();
            return b < 0 ? "no answer through the proxy" : null;
        } catch (IOException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            close(s);
        }
    }
}
