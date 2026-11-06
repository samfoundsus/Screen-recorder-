package com.example.domain.repository

import com.example.domain.model.DeviceCapabilities
import com.example.domain.model.OptionProbeState
import kotlinx.coroutines.flow.StateFlow

interface CapabilitiesProvider {
  val resolutionProbeStates: StateFlow<Map<String, OptionProbeState>>
  val fpsProbeStates: StateFlow<Map<Int, OptionProbeState>>

  fun getCapabilities(): DeviceCapabilities

  fun getDisplayRefreshRateHz(): Int

  fun probeResolutionsForFps(fps: Int, orientation: String = "Auto")

  fun probeFpsForResolution(resolution: String, orientation: String = "Auto")

  suspend fun retestDeviceSupport()
}
