package com.cc.videoplayer.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Matches org.videolan.android:libvlc-all in gradle/libs.versions.toml. */
private const val LibVlcVersion = "3.7.1"

private const val LibVlcSource = "https://code.videolan.org/videolan/libvlcjni"

@Composable
fun AboutScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  val license =
    remember {
      context.assets.open("licenses/lgpl-2.1.txt").bufferedReader().use { it.readText().trim() }
    }
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
      Text(
        "About",
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    SelectionContainer {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
      ) {
        Text(
          "Video Player plays video with LibVLC $LibVlcVersion, published by VideoLAN.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
          "LibVLC is free software covered by the GNU Lesser General Public License, version 2.1. This app loads it at runtime as a separate native library, libvlc.so.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
          "The source for this version is published by VideoLAN.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(
          onClick = {
            try {
              uriHandler.openUri(LibVlcSource)
            } catch (_: ActivityNotFoundException) {
            }
          },
        ) {
          Text(LibVlcSource)
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
          "GNU Lesser General Public License, version 2.1",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
          license,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
