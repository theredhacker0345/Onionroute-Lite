package org.onionroute.lite.tor

/**
 * JNI bindings for hev-socks5-tunnel (shipped as `libtun2socks.so`).
 *
 * WHY A LIBRARY AND NOT A PROCESS
 * -------------------------------
 * hev-socks5-tunnel has no command-line option for using an existing TUN file
 * descriptor in any upstream release (checked against tags 2.15.0, 2.16.0 and
 * 2.18.0): `main()` always calls `hev_socks5_tunnel_main(argv[1], -1)`, i.e.
 * it creates its own TUN, which requires privileges an Android app does not
 * have. The only fd-aware entry points are C functions, so the library has to
 * be linked into our process and called with the fd handed to us by
 * `VpnService.Builder.establish()`. This is the same approach Orbot takes.
 *
 * The library cannot be exec'd either: upstream's Android.mk ends in
 * `BUILD_SHARED_LIBRARY`, so the ELF has no PT_INTERP and `execve` fails with
 * ENOEXEC. That is why the previous `ProcessBuilder` approach could never have
 * worked, independent of the argument problem.
 *
 * HOW THE BINDING WORKS
 * ---------------------
 * Upstream's `src/hev-jni.c` provides the whole bridge. Its `JNI_OnLoad` does
 * `FindClass(PKGNAME "/" CLSNAME)` and `RegisterNatives(...)` for the three
 * methods below. Upstream's defaults point at its own demo class
 * (`hev/htproxy/TProxyService`), so `scripts/build-tun2socks.sh` overrides
 * `PKGNAME`/`CLSNAME` at compile time to target this class:
 *
 *     -DPKGNAME=org/onionroute/lite/tor -DCLSNAME=Tun2SocksNative
 *
 * That is why these methods are bound by name and not by the usual static
 * naming convention (`Java_org_onionroute_..._TProxyStartService`): the
 * library resolves them reflectively at load time. Two consequences:
 *
 *  1. This class must keep its exact fully-qualified name. R8 is told so via
 *     `-keep` in app/proguard-rules.pro; renaming it makes `FindClass` return
 *     null and `RegisterNatives` then crashes the process natively.
 *  2. The method names and signatures below must match `native_methods[]` in
 *     hev-jni.c exactly:
 *       TProxyStartService(String, int) -> void
 *       TProxyStopService()             -> void
 *       TProxyGetStats()                -> long[]
 */
object Tun2SocksNative {

    /**
     * True once [load] has completed without throwing.
     *
     * Checked before every use so a device that somehow lacks the library
     * degrades to proxy-only mode with a clear message, instead of crashing.
     */
    @Volatile
    var isAvailable: Boolean = false
        private set

    private val loadFailure: Throwable?

    init {
        var failure: Throwable? = null
        try {
            System.loadLibrary("tun2socks")
            isAvailable = true
        } catch (t: UnsatisfiedLinkError) {
            // Most likely cause on a normal build: scripts/build-tun2socks.sh
            // was not run for this ABI, so jniLibs/ has no libtun2socks.so.
            failure = t
        } catch (t: SecurityException) {
            failure = t
        }
        loadFailure = failure
    }

    /** Why the library could not be loaded, or null if it loaded. */
    fun loadError(): Throwable? = loadFailure

    /**
     * Starts the tunnel on a dedicated native thread.
     *
     * [configPath] must be a readable path to a hev YAML config file, NOT the
     * YAML text: upstream copies the Java string and passes it to
     * `hev_socks5_tunnel_main(path, fd)`, which reads the file from disk.
     *
     * [tunFd] must be a real, open TUN file descriptor — normally from
     * `VpnService.Builder.establish()`. hev sets `O_NONBLOCK` on it and then
     * uses it directly; it must stay open for the lifetime of the tunnel, so
     * do NOT close the ParcelFileDescriptor until [stop] has returned.
     *
     * Blocks nothing: the heavy lifting happens on a pthread that this call
     * creates and does not join. Calling this while a tunnel is already
     * running is a no-op, matching upstream's `is_working` guard.
     *
     * @throws UnsatisfiedLinkError if the library is not loaded.
     */
    external fun TProxyStartService(configPath: String, tunFd: Int)

    /**
     * Stops the tunnel and joins the worker thread.
     *
     * Safe to call when no tunnel is running (upstream guards on
     * `is_working`). After this returns, the [tunFd] passed to
     * [TProxyStartService] is no longer touched and may be closed.
     */
    external fun TProxyStopService()

    /**
     * Live counters as `[txPackets, txBytes, rxPackets, rxBytes]`.
     *
     * Returns zeroes when no tunnel is running.
     */
    external fun TProxyGetStats(): LongArray
}