package com.suvo.agent.netgate;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Net Gate: a VPN on the phone itself (nothing leaves it) that decides which apps get internet.
 * <ul>
 *   <li><b>Without a proxy</b> Android's own per-app VPN lists do the work: the apps that must not
 *       have internet go into the VPN, where their traffic is dropped; the others are not touched.
 *       Cheap, no engine needed.</li>
 *   <li><b>With a proxy</b> every app goes into the VPN. For each new connection Android says
 *       which app made it; the blocked ones are dropped, the allowed ones go to hev-socks5-tunnel
 *       (a native tun2socks engine) and from there through {@link LocalSocks} to the user's
 *       proxies (SOCKS5 / HTTP, rotated).</li>
 * </ul>
 * Your Friend itself is always outside the VPN: its internet is never blocked.
 */
public final class NetGateService extends VpnService {

    private static final String TAG = "YF.NetGate";
    public static final String ACTION_STOP = "com.suvo.agent.netgate.STOP";
    private static final String TUN_V4 = "198.18.0.1", TUN_V6 = "fc00::1", DNS_V4 = "198.18.0.2";
    private static final int MTU = 8500;

    private static volatile boolean running;
    private static volatile NetGateService instance;
    private static volatile String lastError = "";

    private ParcelFileDescriptor tun;
    private Thread[] threads = new Thread[0];
    private FileDescriptor appSide, engineSide;
    private LocalSocks socks;
    private boolean engine;
    private volatile boolean stopping;

    public static boolean isRunning() {
        return running;
    }

    public static String lastError() {
        return lastError;
    }

    /** Starts (or restarts with new settings). Null = started, else an Intent for the VPN consent. */
    public static Intent start(Context ctx) {
        Intent consent = VpnService.prepare(ctx);
        if (consent != null) return consent;
        new NetGateStore(ctx).setOn(true);
        ctx.startService(new Intent(ctx, NetGateService.class));
        return null;
    }

    public static void stop(Context ctx) {
        new NetGateStore(ctx).setOn(false);
        NetGateService s = instance;
        if (s != null) {
            s.shutdown();
            s.stopSelf();
            com.suvo.agent.launch.Tiles.refreshNetGate(ctx);
            return;
        }
        try {
            ctx.startService(new Intent(ctx, NetGateService.class).setAction(ACTION_STOP));
        } catch (RuntimeException ignored) {
            // Not running anyway.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        // Restarted by Android after it was switched off: stay off.
        if (!new NetGateStore(this).on()) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        // (Re)start with the current settings.
        shutdown();
        try {
            establish();
            running = true;
            lastError = "";
        } catch (Exception e) {
            Log.w(TAG, "start failed", e);
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            shutdown();
            stopSelf();
        }
        com.suvo.agent.launch.Tiles.refreshNetGate(this);
        return START_STICKY;
    }

    private void establish() throws Exception {
        NetGateStore st = new NetGateStore(this);
        Set<String> apps = st.apps();
        boolean allowMode = NetGateStore.ALLOW.equals(st.mode());
        boolean proxy = st.useProxy();
        Builder b = new Builder()
                .setSession(getString(com.suvo.agent.R.string.netgate_name))
                .setMtu(MTU)
                .addAddress(TUN_V4, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_V6, 128)
                .addRoute("::", 0)
                .setBlocking(true);
        Intent open = new Intent(this, com.suvo.agent.ui.NetGateActivity.class);
        b.setConfigureIntent(PendingIntent.getActivity(this, 70, open, PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        PackageManager pm = getPackageManager();
        if (proxy) {
            // Everyone in; each connection is checked by app. Your Friend stays out.
            b.addDisallowedApplication(getPackageName());
            b.addDnsServer(DNS_V4);
        } else if (allowMode) {
            // Only these apps (and Your Friend) stay outside: everything else is dropped.
            b.addDisallowedApplication(getPackageName());
            for (String p : apps) {
                try {
                    b.addDisallowedApplication(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // uninstalled since
                }
            }
        } else {
            // Only these apps go in (and are dropped); with none chosen there is nothing to do.
            int added = 0;
            for (String p : apps) {
                if (p.equals(getPackageName())) continue;
                try {
                    b.addAllowedApplication(p);
                    added++;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            if (added == 0) throw new IllegalStateException(getString(com.suvo.agent.R.string.netgate_no_apps));
        }
        tun = b.establish();
        if (tun == null) throw new IllegalStateException(getString(com.suvo.agent.R.string.netgate_no_consent));
        if (!proxy) {
            startThreads(new Thread(this::drain, "netgate-drain"));
            return;
        }
        // Proxy: the engine gets one end of a packet pipe; we sit between it and the VPN and
        // drop the connections of apps that must not have internet.
        socks = new LocalSocks(st.proxies(), st.rotate());
        int port = socks.start();
        FileDescriptor a = new FileDescriptor(), e = new FileDescriptor();
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, a, e);
        appSide = a;
        engineSide = e;
        File cfg = new File(getFilesDir(), "netgate.yml");
        try (FileOutputStream o = new FileOutputStream(cfg)) {
            o.write(config(port).getBytes(StandardCharsets.UTF_8));
        }
        int fd = ParcelFileDescriptor.dup(e).detachFd();
        if (!hev.htproxy.TProxyService.TProxyStartService(cfg.getAbsolutePath(), fd)) {
            throw new IllegalStateException("The proxy engine did not start");
        }
        engine = true;
        Filter f = new Filter(this, allowMode, apps);
        startThreads(new Thread(() -> toEngine(f), "netgate-in"), new Thread(this::fromEngine, "netgate-out"));
    }

    private static String config(int socksPort) {
        return "tunnel:\n"
                + "  mtu: " + MTU + "\n"
                + "  ipv4: " + TUN_V4 + "\n"
                + "  ipv6: '" + TUN_V6 + "'\n"
                + "socks5:\n"
                + "  port: " + socksPort + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "mapdns:\n"
                + "  address: " + DNS_V4 + "\n"
                + "  port: 53\n"
                + "  network: 100.64.0.0\n"
                + "  netmask: 255.192.0.0\n"
                + "  cache-size: 10000\n"
                + "misc:\n"
                + "  task-stack-size: 81920\n"
                + "  log-level: warn\n";
    }

    private void startThreads(Thread... ts) {
        threads = ts;
        for (Thread t : ts) t.start();
    }

    /** No-proxy mode: what comes in belongs to blocked apps — read and forget it. */
    private void drain() {
        byte[] buf = new byte[MTU];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (!stopping) {
                if (in.read(buf) < 0) break;
            }
        } catch (IOException ignored) {
        }
    }

    /** VPN → engine, for the apps that may; dropped for the others. */
    private void toEngine(Filter f) {
        byte[] buf = new byte[MTU];
        byte[] dns = ipv4(DNS_V4);
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (!stopping) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                Packet p = Packet.parse(buf, n);
                if (p == null) continue;
                boolean pass;
                if (p.protocol != Packet.TCP && p.protocol != Packet.UDP) pass = true;
                else if (p.isDnsTo(dns)) pass = true; // names are answered by the engine; the connection is what is checked
                else pass = f.allowed(p);
                if (!pass) continue;
                try {
                    Os.write(appSide, buf, 0, n);
                } catch (Exception e) {
                    if (stopping) break;
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Engine → VPN: answers go back to the apps. */
    private void fromEngine() {
        byte[] buf = new byte[MTU + 64];
        try (FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            while (!stopping) {
                int n;
                try {
                    n = Os.read(appSide, buf, 0, buf.length);
                } catch (Exception e) {
                    break;
                }
                if (n <= 0) {
                    if (n < 0) break;
                    continue;
                }
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
        }
    }

    private static byte[] ipv4(String s) {
        String[] p = s.split("\\.");
        return new byte[]{(byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]), (byte) Integer.parseInt(p[2]), (byte) Integer.parseInt(p[3])};
    }

    /** Which app a connection is from, and whether it may go out (remembered per connection). */
    private static final class Filter {
        private final ConnectivityManager cm;
        private final PackageManager pm;
        private final boolean allowMode;
        private final Set<String> apps;
        private final int me = Process.myUid();
        private final Map<String, Boolean> flows = new LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > 8192;
            }
        };
        private final Map<Integer, Boolean> uids = new java.util.HashMap<>();

        Filter(Context ctx, boolean allowMode, Set<String> apps) {
            this.cm = ctx.getSystemService(ConnectivityManager.class);
            this.pm = ctx.getPackageManager();
            this.allowMode = allowMode;
            this.apps = apps;
        }

        boolean allowed(Packet p) {
            String key = p.flowKey();
            Boolean known = flows.get(key);
            if (known != null) return known;
            int uid = owner(p);
            boolean ok = uid >= 0 && byUid(uid);
            // Remembered either way: asking Android again for every packet of a connection it does
            // not know (one opened before Net Gate started) would cost a lot. Such connections stay dropped.
            flows.put(key, ok);
            return ok;
        }

        private int owner(Packet p) {
            if (Build.VERSION.SDK_INT < 29) return -1;
            try {
                InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(p.src), p.srcPort);
                InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort);
                return cm.getConnectionOwnerUid(p.protocol == Packet.TCP ? OsConstants.IPPROTO_TCP : OsConstants.IPPROTO_UDP, local, remote);
            } catch (Exception e) {
                return -1;
            }
        }

        private boolean byUid(int uid) {
            if (uid == me) return true;
            // The system itself (time, updates, downloads by the system) keeps working.
            if (uid < Process.FIRST_APPLICATION_UID) return true;
            Boolean c = uids.get(uid);
            if (c != null) return c;
            boolean chosen = false;
            String[] pk = pm.getPackagesForUid(uid);
            if (pk != null) for (String s : pk) if (apps.contains(s)) chosen = true;
            boolean ok = allowMode == chosen;
            uids.put(uid, ok);
            return ok;
        }
    }

    private void shutdown() {
        stopping = true;
        if (engine) {
            try {
                hev.htproxy.TProxyService.TProxyStopService();
            } catch (Throwable ignored) {
            }
            engine = false;
        }
        if (socks != null) socks.stop();
        socks = null;
        closeFd(appSide);
        closeFd(engineSide);
        appSide = engineSide = null;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
        }
        tun = null;
        for (Thread t : threads) t.interrupt();
        for (Thread t : threads) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        threads = new Thread[0];
        stopping = false;
        running = false;
    }

    private static void closeFd(FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRevoke() {
        // Another VPN took over, or the user switched it off in Android's settings.
        new NetGateStore(this).setOn(false);
        shutdown();
        stopSelf();
        com.suvo.agent.launch.Tiles.refreshNetGate(this);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        shutdown();
        com.suvo.agent.launch.Tiles.refreshNetGate(this);
        super.onDestroy();
    }
}
