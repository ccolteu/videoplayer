package com.cc.videoplayer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cc.videoplayer.PlayerActivity
import com.cc.videoplayer.browse.BrowserViewModel
import com.cc.videoplayer.browse.FolderEntry
import com.cc.videoplayer.browse.Place
import com.cc.videoplayer.browse.StorageBrowser
import com.cc.videoplayer.browse.StorageRoot
import com.cc.videoplayer.browse.formatSize

@Composable
fun BrowserScreen(vm: BrowserViewModel = viewModel()) {
  val context = LocalContext.current
  val place by vm.place.collectAsStateWithLifecycle()
  val roots by vm.roots.collectAsStateWithLifecycle()
  val entries by vm.entries.collectAsStateWithLifecycle()
  val loading by vm.loading.collectAsStateWithLifecycle()
  val error by vm.error.collectAsStateWithLifecycle()
  val accessGranted by vm.accessGranted.collectAsStateWithLifecycle()
  val insideFolder = place is Place.Directory

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      vm.refresh()
    }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, vm) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  BackHandler(enabled = insideFolder) { vm.up() }

  val folderTitle = (place as? Place.Directory)?.title ?: "Video Player"
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    BrowserTopBar(
      title = folderTitle,
      subtitle = if (insideFolder) null else "Tap a video to play it here",
      canGoUp = insideFolder,
      onBack = vm::up,
      onRefresh = vm::refresh,
    )
    if (loading) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      if (!accessGranted) {
        AccessPrompt(
          onAllow = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              val intent = StorageBrowser.manageAccessIntent(context)
              try {
                context.startActivity(intent)
              } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
              }
            } else {
              permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
          },
        )
      } else {
        when (val current = place) {
          Place.Roots ->
            RootList(
              roots = roots,
              loading = loading,
              error = error,
              onRoot = { root -> vm.openDirectory(root.path, root.label) },
              onRetry = vm::refresh,
            )
          is Place.Directory ->
            FolderList(
              entries = entries,
              loading = loading,
              error = error,
              onEntry = { entry ->
                if (entry.directory) {
                  vm.openDirectory(entry.path, entry.name)
                } else {
                  context.startActivity(PlayerActivity.intent(context, entry.path, entry.name))
                }
              },
              onRetry = vm::refresh,
            )
        }
      }
    }
  }
}

@Composable
private fun AccessPrompt(onAllow: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      "Allow access to storage so this app can open folders on the SD card and play a video when you tap it.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.size(20.dp))
    Button(onClick = onAllow) { Text("Allow access") }
  }
}

@Composable
private fun BrowserTopBar(
  title: String,
  subtitle: String?,
  canGoUp: Boolean,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (canGoUp) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
    } else {
      Spacer(modifier = Modifier.width(12.dp))
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (subtitle != null) {
        Text(
          subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    IconButton(onClick = onRefresh) {
      Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
    }
  }
}

@Composable
private fun RootList(
  roots: List<StorageRoot>,
  loading: Boolean,
  error: String?,
  onRoot: (StorageRoot) -> Unit,
  onRetry: () -> Unit,
) {
  LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
    if (error != null) {
      item { ErrorRow(error, onRetry) }
    }
    items(roots, key = { it.id }) { root ->
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clickable { onRoot(root) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = if (root.removable) Icons.Filled.SdCard else Icons.Filled.Smartphone,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
          root.label,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Icon(
          Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
    if (!loading && roots.isEmpty() && error == null) {
      item {
        Text(
          "No storage was found.",
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun FolderList(
  entries: List<FolderEntry>,
  loading: Boolean,
  error: String?,
  onEntry: (FolderEntry) -> Unit,
  onRetry: () -> Unit,
) {
  if (!loading && entries.isEmpty()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        error ?: "No videos in this folder.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (error != null) {
        TextButton(onClick = onRetry) { Text("Try again") }
      }
    }
    return
  }
  LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
    if (error != null) {
      item { ErrorRow(error, onRetry) }
    }
    items(entries, key = { it.path }) { entry ->
      val detail = if (entry.directory) "Folder" else formatSize(entry.sizeBytes).ifBlank { "Video" }
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clickable { onEntry(entry) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = if (entry.directory) Icons.Filled.Folder else Icons.Filled.Movie,
          contentDescription = null,
          tint = if (entry.directory) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
  }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onRetry) { Text("Try again") }
  }
}
