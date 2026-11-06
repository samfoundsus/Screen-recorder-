package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppThemeMode

private val DarkColorScheme =
  darkColorScheme(
    primary = PixelBluePrimaryDark,
    onPrimary = PixelBlueOnPrimaryDark,
    primaryContainer = PixelBluePrimaryContainerDark,
    onPrimaryContainer = PixelBlueOnPrimaryContainerDark,
    secondary = PixelSecondaryDark,
    onSecondary = PixelOnSecondaryDark,
    secondaryContainer = PixelSecondaryContainerDark,
    onSecondaryContainer = PixelOnSecondaryContainerDark,
    tertiary = PixelCoralTertiaryDark,
    onTertiary = PixelOnCoralTertiaryDark,
    tertiaryContainer = PixelCoralTertiaryContainerDark,
    onTertiaryContainer = PixelOnCoralTertiaryContainerDark,
    background = PixelBackgroundDark,
    surface = PixelSurfaceDark,
    surfaceContainer = PixelSurfaceContainerDark,
    surfaceContainerHigh = PixelSurfaceContainerHighDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = PixelBluePrimaryLight,
    onPrimary = PixelBlueOnPrimaryLight,
    primaryContainer = PixelBluePrimaryContainerLight,
    onPrimaryContainer = PixelBlueOnPrimaryContainerLight,
    secondary = PixelSecondaryLight,
    onSecondary = PixelOnSecondaryLight,
    secondaryContainer = PixelSecondaryContainerLight,
    onSecondaryContainer = PixelOnSecondaryContainerLight,
    tertiary = PixelCoralTertiaryLight,
    onTertiary = PixelOnCoralTertiaryLight,
    tertiaryContainer = PixelCoralTertiaryContainerLight,
    onTertiaryContainer = PixelOnCoralTertiaryContainerLight,
    background = PixelBackgroundLight,
    surface = PixelSurfaceLight,
    surfaceContainer = PixelSurfaceContainerLight,
    surfaceContainerHigh = PixelSurfaceContainerHighLight,
  )

val PixelShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
  )

@Composable
fun MyApplicationTheme(
  themeMode: AppThemeMode = AppThemeMode.SYSTEM,
  dynamicColor: Boolean = true,
  pureBlackDark: Boolean = false,
  content: @Composable () -> Unit,
) {
  val systemDark = isSystemInDarkTheme()
  val darkTheme =
    when (themeMode) {
      AppThemeMode.SYSTEM -> systemDark
      AppThemeMode.LIGHT -> false
      AppThemeMode.DARK -> true
    }

  val baseColorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) {
          val dynamicDark = dynamicDarkColorScheme(context)
          // Use the dynamic tinted dark surface container by default so dark mode feels distinctly Material You tinted
          dynamicDark.copy(
            background = dynamicDark.surfaceContainerLowest,
            surface = dynamicDark.surfaceContainerLow,
          )
        } else {
          dynamicLightColorScheme(context)
        }
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  val colorScheme =
    if (darkTheme && pureBlackDark) {
      baseColorScheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
      )
    } else {
      baseColorScheme
    }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = PixelShapes,
    content = content,
  )
}
