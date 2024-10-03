package io.github.a13e300.ksuwebui

import android.util.Log
import com.topjohnwu.superuser.Shell


private const val TAG = "KsuCli"

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return if (globalMnt) {
            builder.build("su", "-mm")
        } else {
            builder.build("su")
        }
}
