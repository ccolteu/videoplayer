package com.cc.videoplayer.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

object StorageBrowser {
  fun hasAccess(context: Context): Boolean = DocumentLibrary.hasGrant(context)

  fun initialFolder(context: Context): Uri? = DocumentLibrary.initialLocation(context)

  fun chooseFolderIntent(context: Context): Intent = DocumentLibrary.treeIntent(initialFolder(context))

  fun persist(context: Context, tree: Uri) = DocumentLibrary.persist(context, tree)

  fun release(context: Context, tree: Uri) = DocumentLibrary.release(context, tree)

  fun roots(context: Context): List<StorageRoot> {
    return DocumentLibrary.grants(context).map { entry ->
      val documentId = runCatching { DocumentsContract.getTreeDocumentId(entry.uri) }.getOrDefault("")
      StorageRoot(
        id = entry.uri.toString(),
        label = entry.name,
        removable = !documentId.startsWith("primary:"),
        path = entry.uri.toString(),
      )
    }
  }

  fun children(context: Context, location: String): List<FolderEntry> {
    return DocumentLibrary.children(context, location).map { entry ->
      FolderEntry(
        path = entry.uri.toString(),
        name = entry.name,
        directory = entry.directory,
        sizeBytes = entry.sizeBytes,
      )
    }
  }
}
