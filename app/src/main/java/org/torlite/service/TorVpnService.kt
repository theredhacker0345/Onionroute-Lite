package org.torlite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import org.torlite.Constants
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * VPN Service that routes all device traffic through Tor.
 * Uses Android's VpnService API to create a TUN interface,
 * then runs hev-socks5-tunnel to forward TCP through Tor's SOCKS5.
 */
class TorVpnService : VpnService() {

    private val tag = "TorVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    private var isRunning = false
    private var tunFd: Int = -1
    private val NOTIFICATION_CHANNEL_ID = "torlite_vpn"
    private val VPN_NOTIFICATION_ID = 2

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == Constants.ACTION_STOP_VPN) {
            stopVpn()
            return START_NOT_STICKY
        }

        val notification = buildNotification("VPN active — routing through Tor")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(VPN_NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(VPN_NOTIFICATION_ID, notification)
        }

        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(tag, "VPN permission revoked by user")
        stopVpn()
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            val builder = Builder()
                .setSession("Tor Lite")
                .setMtu(Constants.VPN_MTU)
                .addAddress(Constants.VPN_ADDRESS, 24)
                .addDnsServer(Constants.VPN_DNS)
                .addRoute("0.0.0.0", 0)
                .addSearchDomain("onion")

            // Block all apps from bypassing the VPN on API 21+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addDisallowedApplication(packageName)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(tag, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            tunFd = vpnInterface!!.fd
            isRunning = true

            if (!startNativeTun2Socks()) {
                Log.e(tag, "Failed to start tun2socks")
                stopVpn()
                return
            }

            val intent = Intent(Constants.ACTION_START_VPN)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            Log.i(tag, "VPN started — all traffic routing through Tor")

        } catch (e: Exception) {
            Log.e(tag, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        try { tun2socksProcess?.destroy(); tun2socksProcess = null } catch (_: Exception) {}
        try { vpnInterface?.close(); vpnInterface = null } catch (_: Exception) {}
        tunFd = -1

        try {
            val intent = Intent(Constants.ACTION_STOP_VPN)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (_: Exception) {}

        stopForeground(true)
        stopSelf()
        Log.i(tag, "VPN stopped")
    }

    private fun startNativeTun2Socks(): Boolean {
        try {
            val tun2socksWrapper = File(filesDir, Constants.EXEC_TUN2SOCKS)
            if (!tun2socksWrapper.exists()) {
                Log.e(tag, "tun2socks wrapper not found")
                return false
            }

            val configFile = File(filesDir, "conf/main.yml")
            configFile.parentFile?.mkdirs()
            configFile.writeText(buildString {
                appendLine("tunnel:")
                appendLine("  mtu: ${Constants.VPN_MTU}")
                appendLine("socks5:")
                appendLine("  address: '127.0.0.1'")
                appendLine("  port: ${Constants.TOR_SOCKS_PORT}")
                appendLine("  udp: false")
                appendLine("dns:")
                appendLine("  address: '127.0.0.1'")
                appendLine("  port: ${Constants.TOR_DNS_PORT}")
            })

            val processBuilder = ProcessBuilder(
                tun2socksWrapper.absolutePath, "-c", configFile.absolutePath
            ).directory(File(filesDir, "conf").parentFile)
                .redirectErrorStream(true)

            val env = processBuilder.environment()
            env["TUN_FD"] = tunFd.toString()

            tun2socksProcess = processBuilder.start()

            // Note: VpnService.protect() only works on Socket/DatagramSocket,
            // not on a Process object. The tun2socks binary reads the TUN FD
            // from the TUN_FD environment variable and opens it directly,
            // so its traffic goes through the VPN interface by design.
            // No protect() call is needed here.

            Thread({
                try {
                    val reader = BufferedReader(InputStreamReader(tun2socksProcess?.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d("Tun2Socks", line ?: "")
                    }
                } catch (_: Exception) {}
            }, "Tun2SocksLogger").start()

            Log.i(tag, "Native tun2socks started with TUN fd=$tunFd")
            return true

        } catch (e: Exception) {
            Log.e(tag, "Failed to start native tun2socks", e)
            return false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tor VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val builder = Notification.Builder(this)
            .setContentTitle("Tor Lite VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ID)
        } else {
            builder.setPriority(Notification.PRIORITY_LOW)
        }

        return builder.build()
    }
}
