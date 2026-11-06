package com.example.data.capabilities

import com.example.domain.model.DeviceCapabilities
import com.example.domain.repository.CapabilitiesProvider

class FakeCapabilitiesProvider : CapabilitiesProvider {
  override fun getCapabilities(): DeviceCapabilities {
    return DeviceCapabilities(
      supportedResolutions =
        listOf(
          "Native (1344×2992)",
          "1440p (QHD)",
          "1080p (FHD)",
          "720p (HD)",
          "480p (SD)",
        ),
      supportedFps = listOf(24, 30, 60, 90, 120),
      supportedBitratesMbps = listOf(4, 8, 12, 16, 24, 32, 48),
      supportedCodecs = listOf("HEVC (H.265)", "AVC (H.264)", "AV1 (Hardware)"),
      supportedContainers = listOf("MP4", "WebM", "MKV"),
      supportedOrientations = listOf("Auto", "Portrait", "Landscape"),
      supportedAudioSources =
        listOf(
          "Device audio + Mic",
          "Device audio only",
          "Microphone only",
          "Unprocessed Mic",
          "Mute (No audio)",
        ),
      supportedAudioChannels = listOf("Stereo", "Mono"),
      supportsHardwareNoiseSuppression = true,
      deviceModelSummary = "Google Pixel 9 Pro • Tensor G4",
      hardwareEncoderSummary = "c2.exynos.hevc.encoder • 10-bit SDR",
    )
  }
}
