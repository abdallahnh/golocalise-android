package com.golocalise.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.golocalise.sdk.FileCacheAdapter
import com.golocalise.sdk.GoLocaliseClient
import com.golocalise.sdk.GoLocaliseConfiguration
import com.golocalise.sdk.RefreshResult
import com.golocalise.sdk.TranslationEntry
import java.net.URL
import kotlinx.coroutines.launch

public class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { DemoScreen(createClient()) } } }
  }

  private fun createClient(): Result<GoLocaliseClient> = runCatching {
    require(BuildConfig.GOLOCALISE_BASE_URL.isNotBlank()) { "GOLOCALISE_BASE_URL is required" }
    require(BuildConfig.GOLOCALISE_SDK_TOKEN.isNotBlank()) { "GOLOCALISE_SDK_TOKEN is required" }
    require(BuildConfig.GOLOCALISE_PROJECT_ID.isNotBlank()) { "GOLOCALISE_PROJECT_ID is required" }
    GoLocaliseClient(
      GoLocaliseConfiguration(
        baseUrl = URL(BuildConfig.GOLOCALISE_BASE_URL),
        token = BuildConfig.GOLOCALISE_SDK_TOKEN,
        projectId = BuildConfig.GOLOCALISE_PROJECT_ID,
        environment = BuildConfig.GOLOCALISE_ENVIRONMENT,
        locale = BuildConfig.GOLOCALISE_DEFAULT_LOCALE,
        cache = FileCacheAdapter(cacheDir.resolve("golocalise-demo")),
      ),
    )
  }
}

@Composable
private fun DemoScreen(clientResult: Result<GoLocaliseClient>) {
  val client = clientResult.getOrNull()
  val scope = rememberCoroutineScope()
  var entries by remember { mutableStateOf(emptyList<TranslationEntry>()) }
  var locales by remember { mutableStateOf(listOf(BuildConfig.GOLOCALISE_DEFAULT_LOCALE)) }
  var locale by remember { mutableStateOf(BuildConfig.GOLOCALISE_DEFAULT_LOCALE) }
  var namespace by remember { mutableStateOf<String?>(null) }
  var query by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("Loading localization…") }
  var selected by remember { mutableStateOf<TranslationEntry?>(null) }

  fun sync() { entries = client?.translations().orEmpty() }
  fun describe(result: RefreshResult): String = when (result) {
    is RefreshResult.Updated -> "Updated to release ${result.release}"
    is RefreshResult.Unchanged -> "Up to date"
    is RefreshResult.Failed -> "Offline — cached translations preserved"
  }

  LaunchedEffect(client) {
    if (client == null) {
      status = clientResult.exceptionOrNull()?.message ?: "Configuration required"
    } else {
      status = describe(client.initialize())
      sync()
      locales = runCatching { client.supportedLocales() }.getOrDefault(listOf(locale))
    }
  }

  val namespaces = entries.map { it.namespace }.distinct().sorted()
  val filtered = entries.filter {
    (namespace == null || it.namespace == namespace) &&
      (query.isBlank() || "${it.namespace} ${it.key} ${it.value}".contains(query, ignoreCase = true))
  }

  CompositionLocalProvider(LocalLayoutDirection provides if (locale.startsWith("ar")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("GoLocalise Demo", style = MaterialTheme.typography.headlineMedium)
    Text("Switch languages, inspect keys, and refresh real OTA translations.")
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Project: ${BuildConfig.GOLOCALISE_PROJECT_ID.ifBlank { "Not configured" }}")
        Text("Environment: ${BuildConfig.GOLOCALISE_ENVIRONMENT}")
        Text("Release: ${client?.currentRelease?.let { "v$it" } ?: "Not loaded"}")
        Text("Status: $status")
        Button(onClick = {
          if (client != null) scope.launch { status = "Checking for updates…"; status = describe(client.refresh()); sync() }
        }) { Text("Refresh translations") }
      }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      locales.forEach { option ->
        Text(option, color = if (option == locale) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.clickable { if (client != null && option != locale) scope.launch { locale = option; status = describe(client.setLocale(option)); sync() } }.padding(8.dp))
      }
    }
    if (namespaces.size > 1) {
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("All", modifier = Modifier.clickable { namespace = null }.padding(8.dp))
        namespaces.forEach { option -> Text(option, modifier = Modifier.clickable { namespace = option }.padding(8.dp)) }
      }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Search keys or values") }, modifier = Modifier.fillMaxWidth())
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      if (filtered.isEmpty()) item { Text("No translations found", modifier = Modifier.padding(16.dp)) }
      items(filtered, key = { "${it.namespace}:${it.key}" }) { entry -> TranslationRow(entry) { selected = entry } }
    }
  }
  selected?.let { entry ->
    AlertDialog(
      onDismissRequest = { selected = null },
      confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
      title = { Text(entry.key) },
      text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Namespace: ${entry.namespace}")
        Text("Language: $locale")
        Text("Release: ${client?.currentRelease?.let { "v$it" } ?: "Not loaded"}")
        Text(entry.value)
      } },
    )
  }
  }
}

@Composable
private fun TranslationRow(entry: TranslationEntry, onOpen: () -> Unit) {
  Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp)) {
    Text("${entry.namespace}.${entry.key}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    Text(entry.value, style = MaterialTheme.typography.bodyLarge)
  }
}
