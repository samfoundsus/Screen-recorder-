package com.example.util

import android.os.Build

object DeviceUtils {

  /**
   * Detects whether the app is executing inside an Android emulator.
   * Matches fingerprints, hardware, product, and model identifiers prescribed for Android SDK emulators.
   */
  fun isEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val product = Build.PRODUCT.lowercase()

    return fingerprint.startsWith("generic") ||
      fingerprint.contains("emulator") ||
      model.contains("sdk") ||
      model.contains("emulator") ||
      hardware == "goldfish" ||
      hardware == "ranchu" ||
      hardware.contains("cutterfish") ||
      hardware.contains("cuttlefish") ||
      product.contains("sdk")
  }
}
