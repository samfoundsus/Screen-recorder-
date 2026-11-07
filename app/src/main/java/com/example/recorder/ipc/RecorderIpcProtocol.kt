package com.example.recorder.ipc

/**
 * Message codes and bundle keys for IPC between the main UI process / Quick Settings tile
 * and the `:recorder` service process via [android.os.Messenger].
 */
object RecorderIpcProtocol {
  // Client -> RecorderService
  const val MSG_REGISTER_CLIENT = 1001
  const val MSG_UNREGISTER_CLIENT = 1002
  const val MSG_START_RECORDING = 1003
  const val MSG_PAUSE_RECORDING = 1004
  const val MSG_RESUME_RECORDING = 1005
  const val MSG_STOP_RECORDING = 1006
  const val MSG_SHOW_CONTROLS = 1007
  const val MSG_HIDE_CONTROLS = 1008
  const val MSG_SET_APP_FOREGROUND = 1009
  const val MSG_GET_MEMORY_STATS = 1010
  const val MSG_UPDATE_SETTINGS = 1011

  // RecorderService -> Client
  const val MSG_STATE_UPDATE = 2001
  const val MSG_TIMER_TICK = 2002
  const val MSG_CONTROLS_POSITION_CHANGED = 2003
  const val MSG_MEMORY_STATS_RESPONSE = 2004

  // Bundle keys
  const val KEY_SNAPSHOT = "key_snapshot"
  const val KEY_RESULT_CODE = "key_result_code"
  const val KEY_RESULT_DATA = "key_result_data"
  const val KEY_RESTORE_CONTROLS = "key_restore_controls"
  const val KEY_STATE_NAME = "key_state_name"
  const val KEY_ELAPSED_MS = "key_elapsed_ms"
  const val KEY_COUNTDOWN_SECONDS = "key_countdown_seconds"
  const val KEY_ERROR_MESSAGE = "key_error_message"
  const val KEY_SAVED_URI = "key_saved_uri"
  const val KEY_CONTROLS_VISIBLE = "key_controls_visible"
  const val KEY_PORTRAIT_X_FRAC = "key_portrait_x_frac"
  const val KEY_PORTRAIT_Y_FRAC = "key_portrait_y_frac"
  const val KEY_LANDSCAPE_X_FRAC = "key_landscape_x_frac"
  const val KEY_LANDSCAPE_Y_FRAC = "key_landscape_y_frac"
  const val KEY_RECORDER_PSS_KB = "key_recorder_pss_kb"
  const val KEY_PEAK_PSS_KB = "key_peak_pss_kb"
  const val KEY_NATIVE_HEAP_SIZE_KB = "key_native_heap_size_kb"
  const val KEY_NATIVE_HEAP_ALLOC_KB = "key_native_heap_alloc_kb"
  const val KEY_JAVA_HEAP_USED_KB = "key_java_heap_used_kb"
  const val KEY_ACTIVE_CODECS = "key_active_codecs"
  const val KEY_ACTIVE_THREADS = "key_active_threads"
}
