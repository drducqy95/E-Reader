package com.example.data

import android.content.Context
import com.drduc.engine.graph.LegadoDictionaryCandidateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class DictionaryType(val fileName: String) {
    NAMES("Names.txt"),
    VIETPHRASE("VietPhrase.txt"),
    HAN_VIET("ChinesePhienAmWords.txt"),
    PRONOUNS("Pronouns.json"),
    GRAMMAR_RULES("grammar_rules.jsonl")
}

class DictionaryPackageManager(
    context: Context,
    private val readerDao: ReaderDao,
    private val dictionaryStore: LegadoDictionaryCandidateStore,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val dictionaryDir = File(context.filesDir, "translate/vietphrase").apply { mkdirs() }

    suspend fun import(type: DictionaryType, input: InputStream, expectedSha256: String? = null): DictionaryPackage =
        withContext(Dispatchers.IO) {
            val target = File(dictionaryDir, type.fileName)
            val temporary = File(dictionaryDir, "${type.fileName}.tmp").apply { delete() }
            input.buffered().use { source -> temporary.outputStream().buffered().use(source::copyTo) }
            val checksum = sha256(temporary)
            require(expectedSha256.isNullOrBlank() || checksum.equals(expectedSha256, true)) {
                temporary.delete()
                "Dictionary checksum mismatch"
            }
            if (target.exists() && !target.delete()) error("Could not replace ${type.fileName}")
            if (!temporary.renameTo(target)) error("Could not finalize ${type.fileName}")
            dictionaryStore.invalidate()
            DictionaryPackage(
                type = type.name,
                fileName = type.fileName,
                version = checksum.take(16),
                checksum = checksum,
                entryCount = countEntries(target),
                status = "READY"
            ).also { readerDao.putDictionaryPackage(it) }
        }

    suspend fun download(type: DictionaryType, url: String, sha256: String): DictionaryPackage =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            require(request.url.isHttps || request.url.host in DEVELOPMENT_HOSTS) {
                "Dictionary download requires HTTPS"
            }
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Dictionary download failed with HTTP ${response.code}" }
                import(type, checkNotNull(response.body).byteStream(), sha256)
            }
        }

    suspend fun delete(type: DictionaryType) = withContext(Dispatchers.IO) {
        File(dictionaryDir, type.fileName).delete()
        dictionaryStore.invalidate()
        readerDao.deleteDictionaryPackage(type.name)
    }

    suspend fun scanInstalled(): List<DictionaryPackage> = withContext(Dispatchers.IO) {
        DictionaryType.entries.mapNotNull { type ->
            val file = File(dictionaryDir, type.fileName)
            if (!file.isFile) null else {
                val checksum = sha256(file)
                DictionaryPackage(
                    type = type.name,
                    fileName = type.fileName,
                    version = checksum.take(16),
                    checksum = checksum,
                    entryCount = countEntries(file),
                    status = "READY"
                ).also { readerDao.putDictionaryPackage(it) }
            }
        }
    }

    private fun countEntries(file: File): Int =
        if (file.extension.equals("json", true)) 1
        else file.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val DEVELOPMENT_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2")
    }
}
