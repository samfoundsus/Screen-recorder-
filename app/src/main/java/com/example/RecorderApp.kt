package com.example

import android.app.Application
import android.os.Build

/**
 * Custom Application class that detects the running process name.
 * When running in the separate recording process (`:recorder`), skips heavy UI initialization,
 * DI graphs, analytics, image loaders, and library observers to preserve low memory footprint.
 */
class RecorderApp : Application() {

  override fun onCreate() {
    super.onCreate()
    val processName =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getProcessName()
      } else {
        ""
      }
    if (processName.endsWith(":recorder")) {
      // In the recorder process: skip heavy UI / DI / image loaders / library observers
      return
    }

    // Main UI process initialization (handled per-activity on demand)
  }
}
