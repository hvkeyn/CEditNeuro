package com.hvkeyn.ceditneuro.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Binds the shell user-service Shizuku starts after the user allows this app. */
class ShizukuShell(private val context: Context) {
    private val mutex = Mutex()
    private var service: IShellService? = null
    private var waiter: CompletableDeferred<IShellService>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IShellService.Stub.asInterface(binder)
            service = bound
            waiter?.takeIf { !it.isCompleted }?.complete(bound)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    suspend fun exec(command: String, cwd: String, timeoutSeconds: Int): String {
        val api = bind()
        return withContext(Dispatchers.IO) { api.exec(command, cwd, timeoutSeconds) }
    }

    private suspend fun bind(): IShellService {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return mutex.withLock {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            val pending = CompletableDeferred<IShellService>()
            waiter = pending
            withContext(Dispatchers.Main) {
                Shizuku.bindUserService(args(), connection)
            }
            withTimeout(20_000) { pending.await() }
        }
    }

    private fun args(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .tag("ceditneuro-shell")
            .version(1)
}
