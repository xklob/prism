package com.caleb.prism

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder

/**
 * Media3's trust check needs to see the controller's package on Android 11+.
 * A platform session token alone does not grant that visibility. Binding to the
 * selected player's media service makes prism visible to that player, so it can
 * verify the user's notification-access grant. See androidx/media issue #3507.
 */
internal class PlayerVisibility(private val context: Context) {
    private var playerPackage: String? = null
    private val connections = mutableListOf<ServiceConnection>()

    fun select(packageName: String?) {
        if (Build.VERSION.SDK_INT < 30 || packageName == playerPackage) return
        clear()
        playerPackage = packageName
        if (packageName == null) return
        val seen = mutableSetOf<ComponentName>()
        for (action in listOf("androidx.media3.session.MediaSessionService", "androidx.media3.session.MediaLibraryService")) {
            val intent = Intent(action).setPackage(packageName)
            val services = runCatching { context.packageManager.queryIntentServices(intent, 0) }.getOrDefault(emptyList())
            for (result in services) {
                val service = result.serviceInfo ?: continue
                val component = ComponentName(service.packageName, service.name)
                if (!service.exported || !service.enabled || !seen.add(component)) continue
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) = Unit
                    override fun onServiceDisconnected(name: ComponentName) { release(this) }
                    override fun onBindingDied(name: ComponentName) { release(this) }
                    override fun onNullBinding(name: ComponentName) { release(this) }
                }
                // No AUTO_CREATE: only bind a service the selected player already runs.
                // WAIVE_PRIORITY avoids keeping another app alive on prism's behalf.
                if (runCatching {
                        context.bindService(Intent(intent).setComponent(component), connection, Context.BIND_WAIVE_PRIORITY)
                    }.getOrDefault(false)) connections += connection
            }
        }
    }

    private fun release(connection: ServiceConnection) {
        if (connections.remove(connection)) runCatching { context.unbindService(connection) }
    }

    fun clear() {
        connections.toList().forEach(::release)
        playerPackage = null
    }
}
