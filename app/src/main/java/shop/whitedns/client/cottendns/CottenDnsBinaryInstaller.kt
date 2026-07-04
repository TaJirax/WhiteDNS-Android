package shop.whitedns.client.cottendns

import android.content.Context
import java.io.File

class CottenDnsBinaryInstaller(
    private val context: Context,
) {

    fun installExecutable(): File {
        val executable = File(context.applicationInfo.nativeLibraryDir, NativeLibraryName)
        if (!executable.exists()) {
            throw IllegalStateException(
                "CottenDns native executable not found: ${executable.absolutePath}",
            )
        }
        if (!executable.canExecute()) {
            throw IllegalStateException(
                "CottenDns native executable is not executable: ${executable.absolutePath}",
            )
        }
        return executable
    }

    companion object {
        private const val NativeLibraryName = "libcottendns_client.so"
    }
}
