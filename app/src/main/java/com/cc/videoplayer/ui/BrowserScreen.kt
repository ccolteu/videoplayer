package com.cc.videoplayer.ui

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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
  var showAbout by rememberSaveable { mutableStateOf(false) }

  val folderLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val tree = result.data?.data ?: return@rememberLauncherForActivityResult
      vm.grant(tree)
    }
  val chooseFolder = { folderLauncher.launch(StorageBrowser.chooseFolderIntent(context)) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, vm) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  BackHandler(enabled = showAbout) { showAbout = false }
  BackHandler(enabled = insideFolder && !showAbout) { vm.up() }

  if (showAbout) {
    AboutScreen(onBack = { showAbout = false })
    return
  }

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
      onAbout = { showAbout = true },
      onRefresh = vm::refresh,
    )
    if (loading) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      if (!accessGranted) {
        AccessPrompt(onChoose = chooseFolder)
      } else {
        when (val current = place) {
          Place.Roots ->
            RootList(
              roots = roots,
              loading = loading,
              error = error,
              onRoot = { root -> vm.openDirectory(root.path, root.label) },
              onAdd = chooseFolder,
              onRemove = vm::revoke,
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
private fun AccessPrompt(onChoose: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      "Open the folder that holds your videos, such as TV on the SD card. Android will not grant the card itself. Tap Use this folder, then tap a video here to play it.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.size(20.dp))
    Button(onClick = onChoose) { Text("Choose a folder") }
  }
}

@Composable
private fun BrowserTopBar(
  title: String,
  subtitle: String?,
  canGoUp: Boolean,
  onBack: () -> Unit,
  onAbout: () -> Unit,
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
    IconButton(onClick = onAbout) {
      Icon(Icons.Filled.Info, contentDescription = "About")
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
  onAdd: () -> Unit,
  onRemove: (String) -> Unit,
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
        TextButton(onClick = { onRemove(root.path) }) { Text("Remove") }
        Icon(
          Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
    item {
      TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text("Add a folder")
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
