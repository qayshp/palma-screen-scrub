package dev.palma.screenscrub

import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.TimeUnit

class SurfaceFlingerEpdBridge {
    @Volatile
    private var surfaceFlinger: IBinder? = null

    fun trueGlobalGc(): BooxEpdBridge.ApiResult = transact(
        code = TRANSACTION_REPAINT_EVERYTHING,
        argument = UI_GC_MODE,
        label = "true global GC",
    )

    fun waitForAllUpdates(): BooxEpdBridge.ApiResult = transact(
        code = TRANSACTION_WAIT_ALL_UPDATES,
        argument = null,
        label = "true completion wait",
    )

    private fun transact(code: Int, argument: Int?, label: String): BooxEpdBridge.ApiResult {
        val reflected = runCatching { transactDirect(code, argument, label) }
        reflected.getOrNull()?.let { return BooxEpdBridge.ApiResult.Success(it) }

        val fallback = runCatching { transactWithServiceProcess(code, argument, label) }
        fallback.getOrNull()?.let { return BooxEpdBridge.ApiResult.Success(it) }

        val directError = describe(reflected.exceptionOrNull())
        val processError = describe(fallback.exceptionOrNull())
        return BooxEpdBridge.ApiResult.Unavailable(
            "$label failed; direct=[$directError]; service-process=[$processError]",
        )
    }

    private fun transactDirect(code: Int, argument: Int?, label: String): String {
        val binder = surfaceFlinger ?: findSurfaceFlinger().also { surfaceFlinger = it }
        check(binder.isBinderAlive) { "SurfaceFlinger binder is not alive" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SURFACE_COMPOSER_DESCRIPTOR)
            argument?.let(data::writeInt)
            check(binder.transact(code, data, reply, 0)) { "Binder did not handle transaction" }
            "$label invoked; transport=direct-binder code=0x${code.toString(16)}" +
                (argument?.let { " argument=0x${it.toString(16)}" } ?: "") +
                " replyBytes=${reply.dataAvail()}"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun findSurfaceFlinger(): IBinder {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.isAccessible = true
        return getService.invoke(null, SURFACE_FLINGER_SERVICE) as? IBinder
            ?: error("SurfaceFlinger service was unavailable")
    }

    private fun transactWithServiceProcess(code: Int, argument: Int?, label: String): String {
        val command = mutableListOf(
            "/system/bin/service",
            "call",
            SURFACE_FLINGER_SERVICE,
            code.toString(),
        )
        if (argument != null) command.addAll(listOf("i32", argument.toString()))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("service process timed out after $PROCESS_TIMEOUT_MS ms")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.exitValue() == 0) { "service process exit=${process.exitValue()} output=$output" }
        check(output.contains("Parcel")) { "unexpected service output: $output" }
        return "$label invoked; transport=service-process code=0x${code.toString(16)}" +
            (argument?.let { " argument=0x${it.toString(16)}" } ?: "") +
            " output=${output.replace('\n', ' ')}"
    }

    private fun describe(error: Throwable?): String {
        val cause = error?.cause ?: error
        return if (cause == null) "unknown" else "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private companion object {
        const val SURFACE_FLINGER_SERVICE = "SurfaceFlinger"
        const val SURFACE_COMPOSER_DESCRIPTOR = "android.ui.ISurfaceComposer"
        const val TRANSACTION_WAIT_ALL_UPDATES = 0xff0017
        const val TRANSACTION_REPAINT_EVERYTHING = 0xff0023
        const val UI_GC_MODE = 0x62
        const val PROCESS_TIMEOUT_MS = 5_000L
    }
}
