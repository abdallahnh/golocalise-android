# GoLocalise Android SDK

The Android/Kotlin library implements GoLocalise OTA protocol v1 for Android API
26+. Add the `:golocalise` module (or its eventual published artifact), then create
one long-lived client:

```kotlin
val client = GoLocaliseClient(
  GoLocaliseConfiguration(
    baseUrl = URL("https://api.golocalise.me"),
    token = "gl_sdk_your_public_read_token",
    projectId = "your-project-id",
    environment = "production",
    locale = "ar",
    cache = FileCacheAdapter(File(context.cacheDir, "golocalise")),
    bundled = AndroidResourcesBundledTranslations(
      context.resources,
      mapOf("common.welcome" to R.string.common_welcome),
    ),
  ),
)

lifecycleScope.launch { client.initialize() }

// Always synchronous: OTA memory → Android string resource → fallback → key.
val title = client.translation("welcome", namespace = "common", fallback = "Welcome")
```

For resource fallback, provide an explicit key-to-resource-ID map as shown. This
keeps resource references type-safe and visible to Android's resource shrinker.
`initialize()`, `refresh()`, and `setLocale()` are suspend functions. Lookup never
performs network or disk I/O.

The client verifies scope, protocol, origin, byte size, and SHA-256 before an
atomic cache replacement. Failures preserve last-known-good OTA content and
bundled resources. The runnable `:sample` module is a configuration-driven Compose
demo with release status, supported-locale switching, search, namespace filtering,
explicit refresh, `LazyColumn` rendering, and RTL support.
