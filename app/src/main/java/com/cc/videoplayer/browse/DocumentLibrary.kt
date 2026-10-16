package com.cc.videoplayer.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.util.Locale

internal data class LibraryEntry(
  val uri: Uri,
  val name: String,
  val directory: Boolean,
  val sizeBytes: Long,
)

internal object DocumentLibrary {
  private const val EXTERNAL = "com.android.externalstorage.documents"

  fun hasGrant(context: Context): Boolean {
    return context.contentResolver.persistedUriPermissions.any { it.isReadPermission }
  }

  fun initialLocation(context: Context): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val manager = context.getSystemService(StorageManager::class.java)
    val volume = manager.storageVolumes.firstOrNull { it.isRemovable && !it.uuid.isNullOrBlank() } ?: return null
    return DocumentsContract.buildDocumentUri(EXTERNAL, "${volume.uuid}:")
  }

  fun treeIntent(initial: Uri?): Intent {
    return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
      addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
          Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
      )
      if (initial != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
    }
  }

  fun persist(context: Context, tree: Uri) {
    context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  fun release(context: Context, tree: Uri) {
    runCatching {
      context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  fun grants(context: Context): List<LibraryEntry> {
    return context.contentResolver.persistedUriPermissions
      .filter { it.isReadPermission }
      .map { permission ->
        val uri = permission.uri
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        val name = displayName(context, uri) ?: if (documentId.startsWith("primary:")) "Internal storage" else "SD card"
        LibraryEntry(uri = uri, name = name, directory = true, sizeBytes = 0)
      }
      .sortedBy { it.name.lowercase(Locale.ROOT) }
  }

  fun children(context: Context, location: String): List<LibraryEntry> = list(context, location, videosOnly = true)

  fun subtitleUris(context: Context, videoLocation: String): List<Uri> {
    val base = documentId(Uri.parse(videoLocation)).substringAfterLast('/').substringBeforeLast('.', "")
    if (base.isEmpty()) return emptyList()
    val parent = parentLocation(videoLocation) ?: return emptyList()
    val siblings = list(context, parent, videosOnly = false)
    val found = LinkedHashSet<Uri>()
    siblings.forEach { entry ->
      if (!entry.directory && isSubtitleName(entry.name) && entry.name.startsWith(base, ignoreCase = true)) {
        found.add(entry.uri)
      }
    }
    val subs = siblings.firstOrNull { it.directory && it.name.equals("Subs", ignoreCase = true) }
    if (subs != null) {
      val inside = list(context, subs.uri.toString(), videosOnly = false)
      inside.forEach { entry ->
        if (!entry.directory && isSubtitleName(entry.name) && entry.name.startsWith(base, ignoreCase = true)) {
          found.add(entry.uri)
        }
      }
      val nested = inside.firstOrNull { it.directory && it.name.equals(base, ignoreCase = true) }
      if (nested != null) {
        list(context, nested.uri.toString(), videosOnly = false).forEach { entry ->
          if (!entry.directory && isSubtitleName(entry.name)) found.add(entry.uri)
        }
      }
    }
    return found.sortedBy { it.lastPathSegment?.lowercase(Locale.ROOT).orEmpty() }
  }

  private fun list(context: Context, location: String, videosOnly: Boolean): List<LibraryEntry> {
    val uri = Uri.parse(location)
    val tree = treeUri(uri)
    val parentId = documentId(uri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val projection =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
      )
    val found = ArrayList<LibraryEntry>()
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
      val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
      if (idColumn < 0 || nameColumn < 0) return@use
      while (cursor.moveToNext()) {
        val name = cursor.getString(nameColumn)?.trim().orEmpty()
        if (name.isEmpty() || name.startsWith('.')) continue
        val mime = if (mimeColumn >= 0) cursor.getString(mimeColumn) else null
        val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
        if (!directory && videosOnly && !isVideoFile(name, mime)) continue
        if (!directory && !videosOnly && !isVideoFile(name, mime) && !isSubtitleName(name)) continue
        val childId = cursor.getString(idColumn) ?: continue
        val size = if (!directory && sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
        found.add(
          LibraryEntry(
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId),
            name = name,
            directory = directory,
            sizeBytes = size,
          ),
        )
      }
    } ?: throw IllegalStateException("Can't read this folder.")
    found.sortWith(compareBy<LibraryEntry> { !it.directory }.thenBy { it.name.lowercase(Locale.ROOT) })
    return found
  }

  fun parentLocation(location: String): String? {
    val uri = Uri.parse(location)
    if (uri.scheme != "content") return null
    val parentId = parentDocumentId(documentId(uri)) ?: return null
    return DocumentsContract.buildDocumentUriUsingTree(treeUri(uri), parentId).toString()
  }

  private fun displayName(context: Context, tree: Uri): String? {
    val document = runCatching {
      DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull() ?: return null
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    context.contentResolver.query(document, projection, null, null, null)?.use { cursor ->
      val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      if (column < 0 || !cursor.moveToFirst()) return null
      val name = cursor.getString(column)?.trim().orEmpty()
      if (name.isNotEmpty()) return name
    }
    return null
  }

  private fun treeUri(uri: Uri): Uri {
    val authority = uri.authority ?: throw IllegalArgumentException("Missing document authority")
    return DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(uri))
  }

  private fun documentId(uri: Uri): String {
    val segments = uri.pathSegments
    return if (segments.contains("document")) {
      DocumentsContract.getDocumentId(uri)
    } else {
      DocumentsContract.getTreeDocumentId(uri)
    }
  }
}

internal fun parentDocumentId(documentId: String): String? {
  val slash = documentId.lastIndexOf('/')
  val parent =
    if (slash >= 0) {
      documentId.substring(0, slash)
    } else {
      val colon = documentId.indexOf(':')
      if (colon < 0) return null
      documentId.substring(0, colon + 1)
    }
  if (!parent.contains(':')) return null
  return parent
}
