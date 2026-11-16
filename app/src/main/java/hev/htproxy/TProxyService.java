package hev.htproxy;

/**
 * JNI binding of hev-socks5-tunnel (MIT, https://github.com/heiher/hev-socks5-tunnel), built by CI
 * (tools/netgate/build_hev.sh). The native library registers its methods to exactly this class
 * name and these signatures, so neither may change. Used by Net Gate's proxy mode.
 */
public final class TProxyService {

    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("hev-socks5-tunnel");
            ok = true;
        } catch (Throwable t) {
            // Not built into this APK: Net Gate works without the proxy option.
            ok = false;
        }
        LOADED = ok;
    }

    private TProxyService() {
    }

    /** True when the engine is in this APK. */
    public static boolean available() {
        return LOADED;
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
