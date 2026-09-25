package com.example.focus.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 自动备份：把整份数据写进用户挑选的目录（SAF 树 URI）。
 *
 * 为什么用 SAF 而不是写公共目录：
 * - 不需要任何存储权限（targetSdk 34 下 MANAGE_EXTERNAL_STORAGE 基本申请不下来）；
 * - 目录由用户指定，卸载重装后只要重新授权同一目录即可续上；
 * - 配合 takePersistableUriPermission，重启后依然可写。
 *
 * 落盘策略：文件名带日期（一事备份_auto_2026-09-25.json），
 * 同一天重复备份覆盖同一文件，只保留最近 KEEP_COUNT 份。
 */
class AutoBackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
) {

    companion object {
        /** 自动备份保留份数（按日期滚动） */
        private const val KEEP_COUNT = 7
        private const val FILE_PREFIX = "一事备份_auto_"
        private const val FILE_SUFFIX = ".json"
        private const val MIME_JSON = "application/json"
    }

    /**
     * 执行一次自动备份。
     * 未设置目录、目录不可访问、写入失败都返回 false（调用方负责记录状态）。
     */
    suspend fun backupNow(): Boolean = withContext(Dispatchers.IO) {
        val treeUri = settingsStore.settings.first().autoBackupDir
            ?.let { Uri.parse(it) }
            ?: return@withContext false
        val json = BackupManager(db, settingsStore).exportData()
        val name = "$FILE_PREFIX${LocalDate.now()}$FILE_SUFFIX"
        runCatching {
            writeDocument(treeUri, name, json.toByteArray(Charsets.UTF_8))
            prune(treeUri, keep = KEEP_COUNT)
        }.isSuccess
    }

    /** 取所选目录的显示名，供设置页展示；失败返回 null */
    suspend fun directoryName(treeUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val docUri = documentUri(treeUri)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    /** 目录里的文件是否还写得动（权限是否仍然有效） */
    suspend fun canWrite(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val granted = context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isWritePermission
            }
            granted && queryChildren(treeUri) != null
        }.getOrDefault(false)
    }

    // ===== SAF 读写 =====

    /** 树 URI → 该目录自身的文档 URI */
    private fun documentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun childUri(treeUri: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    /** 列目录内容：文件名 → 文档 URI；查询失败返回 null（区别于「空目录」） */
    private fun queryChildren(treeUri: Uri): List<Pair<String, Uri>>? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val result = mutableListOf<Pair<String, Uri>>()
        context.contentResolver.query(childUri(treeUri), projection, null, null, null)
            ?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    result += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            } ?: return null
        return result
    }

    /** 写一个文件（同名先删，实现「同一天覆盖」） */
    private fun writeDocument(treeUri: Uri, name: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        queryChildren(treeUri)
            ?.firstOrNull { it.first == name }
            ?.let { (_, uri) -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }

        val created = DocumentsContract.createDocument(
            resolver, documentUri(treeUri), MIME_JSON, name,
        ) ?: error("无法在所选目录里创建文件")

        resolver.openOutputStream(created)?.use { it.write(bytes) }
            ?: error("无法写入备份文件")
    }

    /** 只保留最近 keep 份自动备份（文件名带 ISO 日期，字典序即时序） */
    private fun prune(treeUri: Uri, keep: Int) {
        val resolver = context.contentResolver
        val mine = queryChildren(treeUri)
            ?.filter { it.first.startsWith(FILE_PREFIX) && it.first.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.first }
            ?: return
        mine.drop(keep).forEach { (_, uri) ->
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }
}
