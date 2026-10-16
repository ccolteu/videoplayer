package com.cc.videoplayer.browse

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
  private val stack = MutableStateFlow<List<Place>>(listOf(Place.Roots))
  val place: StateFlow<Place> =
    stack
      .map { it.last() }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Place.Roots)

  private val _roots = MutableStateFlow<List<StorageRoot>>(emptyList())
  val roots: StateFlow<List<StorageRoot>> = _roots.asStateFlow()

  private val _entries = MutableStateFlow<List<FolderEntry>>(emptyList())
  val entries: StateFlow<List<FolderEntry>> = _entries.asStateFlow()

  private val _loading = MutableStateFlow(false)
  val loading: StateFlow<Boolean> = _loading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private val _accessGranted = MutableStateFlow(StorageBrowser.hasAccess(application))
  val accessGranted: StateFlow<Boolean> = _accessGranted.asStateFlow()

  private var loadJob: Job? = null
  private var loadGeneration = 0

  init {
    refresh()
  }

  fun refresh() {
    val generation = ++loadGeneration
    loadJob?.cancel()
    loadJob =
      viewModelScope.launch {
        _loading.value = true
        _error.value = null
        val granted = StorageBrowser.hasAccess(getApplication())
        _accessGranted.value = granted
        if (!granted) {
          _roots.value = emptyList()
          _entries.value = emptyList()
          _loading.value = false
          return@launch
        }
        try {
          when (val current = stack.value.last()) {
            Place.Roots -> {
              _roots.value = withContext(Dispatchers.IO) { StorageBrowser.roots(getApplication()) }
            }
            is Place.Directory -> {
              _entries.value = withContext(Dispatchers.IO) { StorageBrowser.children(getApplication(), current.path) }
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (generation == loadGeneration) {
            _error.value = e.message ?: "Can't read this folder."
          }
        } finally {
          if (generation == loadGeneration) _loading.value = false
        }
      }
  }

  fun up() {
    if (stack.value.size <= 1) return
    _entries.value = emptyList()
    _error.value = null
    stack.value = stack.value.dropLast(1)
    refresh()
  }

  fun grant(tree: Uri) {
    StorageBrowser.persist(getApplication(), tree)
    _accessGranted.value = true
    refresh()
  }

  fun revoke(tree: String) {
    StorageBrowser.release(getApplication(), Uri.parse(tree))
    stack.value = listOf(Place.Roots)
    refresh()
  }

  fun openDirectory(path: String, title: String) {
    _entries.value = emptyList()
    _error.value = null
    stack.value = stack.value + Place.Directory(path, title)
    refresh()
  }
}
