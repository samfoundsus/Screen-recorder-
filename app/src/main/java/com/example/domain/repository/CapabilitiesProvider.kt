package com.example.domain.repository

import com.example.domain.model.DeviceCapabilities

interface CapabilitiesProvider {
  fun getCapabilities(): DeviceCapabilities
}
