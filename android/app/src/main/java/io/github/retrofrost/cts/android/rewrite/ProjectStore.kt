package io.github.retrofrost.cts.android.rewrite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import io.github.retrofrost.cts.android.export.DeviceExportPolicy
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.persistence.ProjectJson
import java.io.File
import java.io.InputStream
import java.util.UUID

internal class ProjectStore(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.filesDir, "rewrite-project.json"))
    private var savedRevision = -1L
    var restoreWarning: String? = null
        private set
    var saveWarning: String? = null
        private set

    fun emptyProject(): CtsProject = CtsProject(
        cards = emptyList(),
        export = DeviceExportPolicy.defaultFor(context),
    )

    fun restore(): CtsProject {
        if (!file.baseFile.exists()) return emptyProject()
        return runCatching {
            file.openRead().use { ProjectJson.decode(readTextLimited(it)) }
        }.getOrElse { error ->
            val backup = File(context.filesDir, "recovery-${System.currentTimeMillis()}.json")
            val preserved = runCatching { file.openRead().use { input ->
                backup.outputStream().use { output -> input.copyTo(output) }
            } }.isSuccess
            restoreWarning = if (preserved) "Previous autosave could not be opened; a recovery copy was kept."
            else "Previous autosave could not be opened: ${error.message.orEmpty()}"
            emptyProject()
        }
    }

    @Synchronized fun save(project: CtsProject, revision: Long): Boolean {
        if (revision <= savedRevision) return true
        return try {
            val data = ProjectJson.encode(project).toByteArray(Charsets.UTF_8)
            require(data.size <= MAX_TEXT_BYTES) { "Project exceeds the autosave size limit" }
            val stream = file.startWrite()
            try {
                stream.write(data)
                file.finishWrite(stream)
                savedRevision = revision
                saveWarning = null
                true
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        } catch (e: Exception) {
            saveWarning = e.message ?: "Autosave failed"
            false
        }
    }

    fun readDocument(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use(::readTextLimited)
            ?: error("Unable to read the selected file")

    fun writeDocument(uri: Uri, project: CtsProject) {
        val bytes = ProjectJson.encode(project).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Project exceeds the size limit" }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Unable to save the selected file")
    }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Imported media"

    /** Prefer durable SAF permission; providers without it get a bounded private copy. */
    fun mediaSource(uri: Uri): String {
        val retained = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (retained) return uri.toString()
        val directory = File(context.filesDir, "rewrite-media").apply { mkdirs() }
        val safeExtension = displayName(uri).substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
        val target = File(directory, "${UUID.randomUUID()}.$safeExtension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "Media exceeds the 512 MiB import limit" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unable to read media")
            return target.absolutePath
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    companion object {
        private const val MAX_TEXT_BYTES = 16 * 1024 * 1024
        private fun readTextLimited(input: InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_TEXT_BYTES) { "Document exceeds 16 MiB" }
                out.write(buffer, 0, count)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
