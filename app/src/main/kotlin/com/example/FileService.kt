package com.example

import android.content.Context
import android.util.Log
import com.example.DocumentsProvider.Companion.toDocumentUri
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap

sealed class Node(val path: String) {
    val name: String get() = path.substringAfterLast("/")
}

class File(path: String, val data: ByteArray) : Node(path) {
    val modified = System.currentTimeMillis()
    val size: Int get() = data.size
}

class Folder(path: String) : Node(path)

class FileService(private val context: Context) {

    companion object {
        const val ROOT = "/"
        fun parent(path: String) = path.substringBeforeLast("/")
        fun join(basePath: String, leaf: String): String = if (basePath == ROOT) "/$leaf" else "$basePath/$leaf"
    }

    private val tag: String = javaClass.simpleName
    private val map: MutableMap<String, Node> = Collections.synchronizedMap(LinkedHashMap())

    private fun filter(filter: (Int, Node) -> Boolean): Collection<Node> {
        return synchronized(map){
            map.values.filterIndexed(filter)
        }
    }

    init {
        reset()
    }

    fun getPath(path: String): Node {
        return map[path] ?: throw IllegalStateException("not found")
    }

    fun getChildren(path: String): Collection<Node> {
        val depth = depth(path)
        return filter { _, node ->
            depth(node.path) - 1 == depth
        }
    }

    private fun depth(path: String) = if (path == ROOT) 1 else path.split("/").size

    fun download(path: String, outputStream: OutputStream) {
        return when (val node = getPath(path)) {
            is Folder -> throw IllegalStateException("cannot download folder")
            is File -> outputStream.write(node.data)
        }
    }

    fun upload(path: String, inputStream: InputStream) {
        update(path, File(path, inputStream.readBytes()))
    }

    fun create(path: String, folder: Boolean) {
        update(path, if (folder) Folder(path) else File(path, ByteArray(0)))
    }

    fun delete(path: String) {
        map.remove(path)
        notify(path)
    }

    fun copy(from: String, to: String): String {
        getPath(from).let { update(to, it) }
        return to
    }

    fun move(from: String, to: String): String {
        map.remove(from).let {
            if (it != null) {
                update(to, it)
                notify(from)
            } else {
                throw IllegalStateException("not found")
            }
        }
        return to
    }

    private fun update(path: String, node: Node, notify: Boolean = true) {
        val fileOrFolder: Node = if (path == node.path) node else when (node) {
            is File -> File(path, node.data)
            is Folder -> Folder(path)
        }
        map[path] = fileOrFolder
        if (notify) {
            Log.i(tag, "$path = $fileOrFolder")
            notify(path)
        }
    }

    fun recentFiles(): Collection<Node> {
        return filter { index, _ -> index < 5 }
    }

    fun search(query: String, rootId: String): Collection<Node> {
        return filter { _, node ->
            node.path.startsWith(rootId) && node.path.contains(query)
        }
    }

    private fun notify(path: String) {
        val notifyUri = toDocumentUri(path)
        val parentUri = toDocumentUri(parent(path))
        context.contentResolver.notifyChange(notifyUri, null)
        context.contentResolver.notifyChange(parentUri, null)
        Log.i(tag, "notified change: $notifyUri / $parentUri")
    }

    fun reset() {
        val keysCopy = ArrayList(map.keys)
        map.clear()
        map[ROOT] = Folder(ROOT)
        keysCopy.forEach(this@FileService::notify)
        context.contentResolver.notifyChange(DocumentsProvider.rootsUri, null)
    }

    fun generateContent() {
        var path = "/${UUID.randomUUID()}"
        create(path, true)
        val random = Random()
        repeat(3000) {
            val folder = random.nextDouble() < 0.1
            val extension = if (folder) "" else ".txt"
            val childPath = "${path}/${UUID.randomUUID()}?${extension}"
            val node = if (folder) Folder(path) else {
                val byteArray = ByteArray(32)
                random.nextBytes(byteArray)
                File(path, byteArray)
            }
            update(childPath, node, false)
            if (folder) {
                path = childPath
            }
        }
        Log.i(tag,"generated content")
    }

}