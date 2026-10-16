package com.cc.videoplayer.browse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

object StorageBrowser {
  fun hasAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Environment.isExternalStorageManager()
    } else {
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
    }
  }

  fun manageAccessIntent(context: Context): Intent {
    return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
      data = Uri.parse("package:${context.packageName}")
    }
  }

  fun roots(context: Context): List<StorageRoot> {
    val found = LinkedHashMap<String, StorageRoot>()
    fun add(path: String, label: String, removable: Boolean) {
      val directory = File(path)
      if (!directory.isDirectory) return
      val key = directory.absolutePath
      if (key in found) return
      found[key] = StorageRoot(id = key, label = label, removable = removable, path = key)
    }

    Environment.getExternalStorageDirectory()?.absolutePath?.let { path ->
      add(path, "Internal storage", removable = false)
    }

    val manager = context.getSystemService(StorageManager::class.java)
    for (volume in manager.storageVolumes) {
      val directory =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null
      val path = directory?.absolutePath ?: continue
      val description = volume.getDescription(context).trim()
      val label =
        description.ifBlank { if (volume.isRemovable) "SD card" else "Internal storage" }
      add(path, label, removable = volume.isRemovable && !volume.isPrimary)
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      File("/storage").listFiles()?.forEach { directory ->
        if (!directory.isDirectory) return@forEach
        if (directory.name == "emulated" || directory.name == "self") return@forEach
        add(directory.absolutePath, "SD card", removable = true)
      }
    }

    return found.values.sortedWith(compareBy({ it.removable }, { it.label.lowercase(Locale.ROOT) }))
  }

  fun children(path: String): List<FolderEntry> {
    val directory = File(path)
    val listed =
      directory.listFiles() ?: throw IllegalStateException("Can't read this folder.")
    val entries = ArrayList<FolderEntry>(listed.size)
    for (file in listed) {
      val name = file.name
      if (name.startsWith('.')) continue
      if (file.isDirectory) {
        entries.add(FolderEntry(path = file.absolutePath, name = name, directory = true, sizeBytes = 0))
      } else if (isVideoFile(name, null)) {
        entries.add(
          FolderEntry(path = file.absolutePath, name = name, directory = false, sizeBytes = file.length()),
        )
      }
    }
    entries.sortWith(compareBy<FolderEntry> { !it.directory }.thenBy { it.name.lowercase(Locale.ROOT) })
    return entries
  }
}
