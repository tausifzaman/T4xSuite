package dev.t4xsuite.ui

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.t4xsuite.core.Capture
import dev.t4xsuite.core.HarException
import dev.t4xsuite.core.HarReader
import dev.t4xsuite.core.markdown
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class ReviewSession {
    enum class Job { IMPORT, EXPORT }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var generation = 0
    var observer: (() -> Unit)? = null
    var capture: Capture? = null
        private set
    var demo = false
        private set
    var job: Job? = null
        private set
    var message: String? = null
        private set

    fun loadDemo(assets: AssetManager) = import(true) { assets.open("demo.har") }
    fun loadFile(resolver: ContentResolver, uri: Uri) = import(false) { resolver.openInputStream(uri) }

    private fun import(isDemo: Boolean, open: () -> InputStream?) = run(Job.IMPORT) {
        try {
            val parsed = HarReader.read(open() ?: throw IOException())
            Result(parsed, isDemo)
        } catch (e: HarException) {
            Result(info = e.problem.userMessage)
        } catch (_: IOException) {
            Result(info = "Couldn't read a valid HAR. The existing session was kept.")
        } catch (_: RuntimeException) {
            Result(info = "Couldn't read a valid HAR. The existing session was kept.")
        }
    }

    fun export(resolver: ContentResolver, uri: Uri) {
        val snapshot = capture ?: return
        run(Job.EXPORT) {
            try {
                val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException()
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(markdown(snapshot)) }
                Result(info = "Markdown report saved.")
            } catch (_: IOException) {
                Result(info = "Couldn't save the report. Check the selected destination.")
            } catch (_: RuntimeException) {
                Result(info = "Couldn't save the report. Check the selected destination.")
            }
        }
    }

    fun clear() {
        if (job != null) return
        capture = null
        demo = false
        message = "Session cleared. The original file was not changed."
        observer?.invoke()
    }

    fun cancelImport() {
        if (job != Job.IMPORT) return
        generation++
        task?.cancel(true)
        task = null
        job = null
        message = "Import canceled. The existing session was kept."
        observer?.invoke()
    }

    fun takeMessage(): String? = message.also { message = null }

    fun close() {
        generation++
        task?.cancel(true)
        worker.shutdownNow()
        capture = null
    }

    private fun run(next: Job, action: () -> Result) {
        if (job != null) return
        val token = ++generation
        job = next
        task = worker.submit {
            val result = action()
            main.post {
                if (token == generation) {
                    result.capture?.let { capture = it; demo = result.isDemo }
                    message = result.info
                    job = null
                    task = null
                    observer?.invoke()
                }
            }
        }
        observer?.invoke()
    }

    private data class Result(val capture: Capture? = null, val isDemo: Boolean = false, val info: String? = null)
}
