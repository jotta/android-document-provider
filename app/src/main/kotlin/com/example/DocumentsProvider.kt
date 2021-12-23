package com.example

import android.R
import android.content.Context
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract.*
import android.provider.DocumentsContract.Document.*
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.FileService.Companion.join
import com.example.FileService.Companion.parent
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.lang.UnsupportedOperationException


class DocumentsProvider : android.provider.DocumentsProvider() {

    companion object {

        private const val ROW_OFFICE_DOC_TYPE = "com_microsoft_office_doctype"
        private const val ROW_OFFICE_SERVICE_NAME = "com_microsoft_office_servicename"
        private const val ROW_OFFICE_TOS_AGREEMENT = "com_microsoft_office_termsofuse"
        private const val OFFICE_TOS_VALUE = "I agree to the terms located in http://go.microsoft.com/fwlink/p/?LinkId=528381"
        private const val DOC_TYPE_CONSUMER = "consumer"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            COLUMN_DOCUMENT_ID,
            COLUMN_MIME_TYPE,
            COLUMN_DISPLAY_NAME,
            COLUMN_LAST_MODIFIED,
            COLUMN_FLAGS,
            COLUMN_SIZE
        )

        private const val AUTHORITY = "com.example.provider"

        private const val tag = "Provider"

        fun toDocumentUri(path: String): Uri {
            return buildDocumentUri(AUTHORITY, path)
        }

        val rootsUri: Uri get() = buildRootsUri(AUTHORITY)

        lateinit var instance: DocumentsProvider
    }

    private lateinit var thumbnailFile: File
    private lateinit var sharedPreferences: SharedPreferences
    lateinit var filesService: FileService

    var hacksEnabled
        get() = sharedPreferences.getBoolean("hacks_enabled", false);
        set(value) = sharedPreferences.edit().putBoolean("hacks_enabled", value).apply()

    override fun createDocument(parentDocumentId: String, mimeType: String?, displayName: String): String {
        return operation("createDocument", parentDocumentId, mimeType, displayName) {
            val path = join(parentDocumentId, displayName)
            filesService.create(path, mimeType == MIME_TYPE_DIR)
            path
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        return operation("openDocument", documentId, mode, signal) {
            when (mode) {
                "r" -> read(documentId)
                "w" -> write(documentId)
                else -> {
                    val errorMessage = "unrecognized mode: $mode"
                    Log.w(tag, errorMessage)
                    throw UnsupportedOperationException(errorMessage)
                }
            }
        }
    }

    private fun read(documentId: String): ParcelFileDescriptor {
        if (hacksEnabled) {

            // this is not a hack per se, but would be nice to avoid buffering to local
            // file especially when dealing with large files

            val file = File.createTempFile("download", ".tmp")
            file.outputStream().use {
                filesService.download(documentId, it)
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {

            // https://developer.android.com/guide/topics/providers/create-document-provider
            // documentation recommends this approach, but createReliablePipe ironically creates
            // an unreliable pipe that now and then fails for no apparent reason.
            // (also when synchronously downloading the file before returning the read side on the calling thread)

            val pair = ParcelFileDescriptor.createReliablePipe()
            val read = pair[0]
            val write = pair[1]

            GlobalScope.launch {
                // TODO is it correct to write to and close the pipe like so?
                try {
                    val autoCloseOutputStream = ParcelFileDescriptor.AutoCloseOutputStream(write)
                    filesService.download(documentId, autoCloseOutputStream)
                    autoCloseOutputStream.close() // TODO seems like we cannot .use() since we want to closeWithError() on failure
                    Log.i(tag, "$documentId written successfully")
                } catch (t: Throwable) {
                    Log.w(tag, t)
                    write.closeWithError(t.message)
                }
            }

            return read
        }
    }


    private fun write(documentId: String): ParcelFileDescriptor {
        if (hacksEnabled) {

            // similar to https://developer.android.com/guide/topics/providers/create-document-provider#openDocument
            // but with special treatment for FileDescriptorDetachedExceptions

            val file = File.createTempFile("upload", ".tmp")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE, Handler(context!!.mainLooper)) { error ->
                val detached = error is ParcelFileDescriptor.FileDescriptorDetachedException && hacksEnabled
                if (error == null || detached) {
                    if (detached) {
                        // hack: wait until the write is presumably done before uploading
                        Log.w(tag, "file was detached, waiting for write to finish")
                        do {
                            val length = file.length()
                            runBlocking {
                                delay(1000)
                            }
                        } while (file.length() != length)
                    }
                    file.inputStream().use {
                        filesService.upload(documentId, it)
                    }
                } else {
                    Log.w(tag, error)
                }
                file.delete()
            }
        } else {
            val pair = ParcelFileDescriptor.createReliablePipe()
            val read = pair[0]
            val write = pair[1]

            // the pipe is detached and crashes when read even though the ParcelFileDescriptor.detachFD() documentation states:
            //
            //  You should not detach when the original creator of the descriptor is
            //  expecting a reliable signal through {@link #close()} or
            //

            GlobalScope.launch {
                ParcelFileDescriptor.AutoCloseInputStream(read).use {
                    filesService.upload(documentId, it)
                }
            }

            return write
        }
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        return operation("copyDocument", sourceDocumentId, targetParentDocumentId) {
            filesService.copy(
                sourceDocumentId,
                join(targetParentDocumentId, sourceDocumentId.substringAfterLast("/"))
            )
        }
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String?, targetParentDocumentId: String): String {
        return operation("moveDocument", sourceDocumentId, sourceParentDocumentId, targetParentDocumentId) {
            filesService.move(
                sourceDocumentId,
                join(targetParentDocumentId, sourceDocumentId.substringAfterLast("/"))
            )
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        return operation("renameDocument", documentId, displayName) {
            filesService.move(
                documentId,
                join(parent(documentId), displayName)
            )
        }
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<String>?): Cursor {
        return operation("queryRecentDocuments", rootId, projection) {
            val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
            val result = MatrixCursor(resolvedProjection)
            filesService.recentFiles().forEach {
                insertValue(it, result)
            }
            result
        }
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        return operation("querySearchDocuments", rootId, query, projection) {
            val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
            val result = MatrixCursor(resolvedProjection)
            filesService.search(query, rootId).forEach {
                insertValue(it, result)
            }
            result
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "/")
            add(Root.COLUMN_DOCUMENT_ID, "/")
            add(Root.COLUMN_TITLE, "example")
            add(Root.COLUMN_ICON, android.R.drawable.star_big_off)
            add(Root.COLUMN_SUMMARY, "example summary")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_CREATE)
        }
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, queryArgs: Bundle?): Cursor {
        return operation("queryChildDocuments", parentDocumentId, projection, queryArgs) {
            super.queryChildDocuments(parentDocumentId, projection, queryArgs)
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        return operation("queryChildDocuments", parentDocumentId, projection, sortOrder) {
            val result = createCursor(parentDocumentId, projection)
            filesService.getChildren(parentDocumentId).forEach {
                insertValue(it, result)
            }
            result
        }
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        return operation("queryDocument", documentId, projection) {
            val cursor = createCursor(documentId, projection)
            insertValue(filesService.getPath(documentId), cursor)
        }
    }

    private fun createCursor(documentId: String, projection: Array<String>?): MatrixCursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.setNotificationUri(context!!.contentResolver, toDocumentUri(documentId))
        return cursor
    }

    private fun insertValue(node: Node, cursor: MatrixCursor): MatrixCursor {
        val projection = cursor.columnNames
        val row = arrayOfNulls<Any>(projection.size)

        val isFile = when (node) {
            is com.example.File -> true
            is Folder -> false
        }
        val isFolder = !isFile

        for (i in projection.indices) {
            when (projection[i]) {
                COLUMN_DOCUMENT_ID -> row[i] = node.path
                COLUMN_DISPLAY_NAME -> row[i] = node.name
                COLUMN_FLAGS -> {
                    var flag = FLAG_SUPPORTS_DELETE or FLAG_SUPPORTS_RENAME or FLAG_SUPPORTS_THUMBNAIL
                    if (isFolder) {
                        flag = flag or FLAG_DIR_SUPPORTS_CREATE
                    }
                    if (isFile) {
                        flag = flag or FLAG_SUPPORTS_WRITE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        flag = flag or FLAG_SUPPORTS_MOVE or FLAG_SUPPORTS_COPY or FLAG_SUPPORTS_REMOVE
                    }
                    row[i] = flag
                }
                COLUMN_MIME_TYPE -> {
                    row[i] = when (node) {
                        is com.example.File -> {
                            when (val extension = node.name.substringAfterLast('.')) {
                                // Demonstrate weirdness:
                                // If the document provider messes up the mimetype, a move/copy into the document provider
                                // from the outside fails in the Chrome OS file manager
                                "docx" -> "application/msword"
                                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            }
                        }
                        is Folder -> MIME_TYPE_DIR
                    }
                }
                COLUMN_SIZE -> {
                    if (node is com.example.File) {
                        row[i] = node.size
                    }
                }
                COLUMN_LAST_MODIFIED -> {
                    if (node is com.example.File) {
                        row[i] = node.modified
                    }
                }
                ROW_OFFICE_TOS_AGREEMENT -> row[i] = OFFICE_TOS_VALUE
                ROW_OFFICE_DOC_TYPE -> row[i] = DOC_TYPE_CONSUMER
                ROW_OFFICE_SERVICE_NAME -> row[i] = context!!.packageManager.getApplicationLabel(context!!.applicationInfo)
            }
        }
        Log.i(tag, "values: ${toString(row)}")
        cursor.addRow(row)
        return cursor
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor? {
        return operation("openDocumentThumbnail", documentId, sizeHint, signal) {
            val parcelFileDescriptor = ParcelFileDescriptor.open(thumbnailFile, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(parcelFileDescriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
    }

    override fun deleteDocument(documentId: String) {
        operation("deleteDocument", documentId) {
            removeDocument(documentId, parent(documentId))
        }
    }

    override fun removeDocument(documentId: String, parentDocumentId: String?) {
        operation("removeDocument", documentId, parentDocumentId) {
            filesService.delete(documentId)
        }
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?, queryArgs: Bundle?, signal: CancellationSignal?): Cursor? {
        return operation("queryRecentDocuments", rootId, projection, queryArgs, signal) {
            super.queryRecentDocuments(rootId, projection, queryArgs, signal)
        }
    }

    override fun querySearchDocuments(rootId: String, projection: Array<out String>?, queryArgs: Bundle): Cursor? {
        return operation("querySearchDocuments", rootId, projection, queryArgs) {
            super.querySearchDocuments(rootId, projection, queryArgs)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? {
        return operation("query", uri, projection, selection, selectionArgs, sortOrder, cancellationSignal) {
            super.query(uri, projection, selection, selectionArgs, sortOrder, cancellationSignal)
        }
    }

    override fun canonicalize(uri: Uri): Uri? {
        return operation("canonicalize", uri) {
            super.canonicalize(uri)
        }
    }

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        return operation("getStreamTypes", uri, mimeTypeFilter) {
            super.getStreamTypes(uri, mimeTypeFilter)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return operation("call", method, arg, extras) {
            super.call(method, arg, extras)
        }
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return operation("isChildDocument", parentDocumentId, documentId) {
            super.isChildDocument(parentDocumentId, documentId)
        }
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String?): Path {
        return operation("findDocumentPath", parentDocumentId, childDocumentId) {
            super.findDocumentPath(parentDocumentId, childDocumentId)
        }
    }

    override fun createWebLinkIntent(documentId: String?, options: Bundle?): IntentSender {
        return operation("createWebLinkIntent", documentId, options) {
            super.createWebLinkIntent(documentId, options)
        }
    }

    override fun ejectRoot(rootId: String?) {
        return operation("ejectRoot", rootId) {
            super.ejectRoot(rootId)
        }
    }

    override fun getDocumentMetadata(documentId: String): Bundle? {
        return operation("getDocumentMetadata", documentId) {
            super.getDocumentMetadata(documentId)
        }
    }

    override fun getDocumentType(documentId: String?): String {
        return operation("getDocumentType", documentId) {
            super.getDocumentType(documentId)
        }
    }

    override fun openTypedDocument(documentId: String?, mimeTypeFilter: String?, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor {
        return operation("openTypedDocument", documentId, mimeTypeFilter, opts, signal) {
            super.openTypedDocument(documentId, mimeTypeFilter, opts, signal)
        }
    }

    override fun getDocumentStreamTypes(documentId: String?, mimeTypeFilter: String?): Array<String> {
        return operation("getDocumentStreamTypes", documentId, mimeTypeFilter) {
            super.getDocumentStreamTypes(documentId, mimeTypeFilter)
        }
    }

    override fun onCreate(): Boolean {
        Log.i(tag, "onCreate")
        val context = context!!

        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.star_big_off)
        thumbnailFile = File.createTempFile("thumb", "png").also { file ->
            file.outputStream().use { fileOutputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream);
            }
        }

        sharedPreferences = context.getSharedPreferences("document_provider", Context.MODE_PRIVATE)
        filesService = FileService(context)
        instance = this

        context.contentResolver.notifyChange(rootsUri, null)
        Log.i(tag, "created")
        return true
    }

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        Log.i(tag, "attachInfo")
        super.attachInfo(context, info)
    }

    private fun <T> operation(operationName: String, vararg args: Any?, callback: () -> T): T {
        Log.i(tag, "$operationName(${args.joinToString(transform = this::toString)})")
        return callback()
    }

    private fun toString(any: Any?): String {
        return if (any == null) "null" else when (any) {
            is String -> any
            is Array<*> -> "[${any.joinToString { toString(it) }}]"
            else -> any.toString()
        }
    }

}