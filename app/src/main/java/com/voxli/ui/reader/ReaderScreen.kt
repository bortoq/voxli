package com.voxli.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voxli.reader.engine.Page
import com.voxli.reader.engine.PageLine

/**
 * Reader screen with 5 non-overlapping tap zones.
 *
 * Reference: roadmap §7.1 – §7.3 (tap zone geometry and actions).
 */

// Mode enum
enum class ReaderMode { READING, TTS, SETTINGS }

// Settings step for the cycle
enum class SettingsStep {
    BG_COLOR, TEXT_COLOR, FONT_SIZE, FONT_FACE, DONE
}

@Composable
fun ReaderScreen(
    currentPage: Page?,
    currentPageIndex: Int,
    totalPages: Int,
    readerMode: ReaderMode,
    settingsStep: SettingsStep,
    isTtsPlaying: Boolean,
    ttsSpeed: Float,
    onTapZone1: () -> Unit,     // top = back to library
    onTapZone2: () -> Unit,     // left = prev page / TTS speed down
    onTapZone3: () -> Unit,     // center = TTS toggle
    onTapZone4: () -> Unit,     // right = next page / TTS speed up
    onTapZone5: () -> Unit,     // bottom = volume down (TTS) / reserved
    onProgressBarTap: () -> Unit,  // settings cycle
    // Settings actions
    onSettingsLeft: () -> Unit,
    onSettingsRight: () -> Unit,
    onSettingsUp: () -> Unit,
    onSettingsDown: () -> Unit,
    bgColor: Color = Color.White,
    textColor: Color = Color.Black,
    fontSize: Int = 16,
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        val totalHeight = maxHeight
        val totalWidth = maxWidth

        // -- Progress bar (bottom) --
        val progressBarHeight = if (readerMode == ReaderMode.SETTINGS) 24.dp else 8.dp

        // -- Content area (between top zone and progress bar) --
        val topZoneHeight = totalHeight * 0.10f
        val bottomZoneHeight = totalHeight * 0.10f
        val contentHeight = totalHeight - topZoneHeight - bottomZoneHeight - progressBarHeight
        val contentWidth = totalWidth

        // Left/right zone widths
        val sideZoneWidth = contentWidth * 0.30f
        val centerZoneWidth = contentWidth * 0.40f

        // ---- Top zone (Zone 1) ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topZoneHeight)
                .align(Alignment.TopCenter)
                .clickable { onTapZone1() },
            contentAlignment = Alignment.Center,
        ) {
            if (readerMode == ReaderMode.READING) {
                Text("↑ Библиотека", color = textColor.copy(alpha = 0.3f), fontSize = MaterialTheme.typography.bodySmall.fontSize)
            }
        }

        // ---- Content area with 3 horizontal zones ----
        Box(
            modifier = Modifier
                .width(contentWidth)
                .height(contentHeight)
                .align(Alignment.CenterStart)
                .offset(y = topZoneHeight),
        ) {
            if (readerMode == ReaderMode.SETTINGS && currentPage == null) {
                // Show settings UI
                SettingsContent(
                    settingsStep = settingsStep,
                    onLeft = onSettingsLeft,
                    onRight = onSettingsRight,
                    onUp = onSettingsUp,
                    onDown = onSettingsDown,
                    textColor = textColor,
                )
            } else {
                // Page content — scrollable
                PageContent(
                    page = currentPage,
                    textColor = textColor,
                    fontSize = fontSize,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = false) { /* handled by zones */ },
                )
            }

            // ---- Zone 2: Left (◄) ----
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(sideZoneWidth)
                    .align(Alignment.CenterStart)
                    .pointerInput(readerMode) {
                        detectTapGestures { onTapZone2() }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (readerMode == ReaderMode.READING) {
                    Text("◄", color = textColor.copy(alpha = 0.15f), modifier = Modifier.padding(start = 8.dp))
                }
            }

            // ---- Zone 3: Center ----
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(centerZoneWidth)
                    .align(Alignment.Center)
                    .pointerInput(readerMode) {
                        detectTapGestures { onTapZone3() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (readerMode == ReaderMode.TTS && isTtsPlaying) {
                    Text("⏸", color = textColor, fontSize = MaterialTheme.typography.titleLarge.fontSize)
                } else if (readerMode == ReaderMode.TTS) {
                    Text("▶", color = textColor, fontSize = MaterialTheme.typography.titleLarge.fontSize)
                }
            }

            // ---- Zone 4: Right (►) ----
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(sideZoneWidth)
                    .align(Alignment.CenterEnd)
                    .pointerInput(readerMode) {
                        detectTapGestures { onTapZone4() }
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (readerMode == ReaderMode.READING) {
                    Text("►", color = textColor.copy(alpha = 0.15f), modifier = Modifier.padding(end = 8.dp))
                }
            }
        }

        // ---- Bottom zone (Zone 5) ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomZoneHeight)
                .align(Alignment.BottomCenter)
                .clickable { onTapZone5() },
            contentAlignment = Alignment.Center,
        ) {
            if (readerMode == ReaderMode.TTS) {
                Text("🔉 −", color = textColor.copy(alpha = 0.5f))
            }
        }

        // ---- Progress bar ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(progressBarHeight)
                .align(Alignment.BottomCenter)
                .offset(y = (-bottomZoneHeight).let { /* just below zone 5 */ })
                .background(textColor.copy(alpha = 0.15f))
                .clickable { onProgressBarTap() },
            contentAlignment = Alignment.CenterStart,
        ) {
            val progress = if (totalPages > 0) currentPageIndex.toFloat() / totalPages else 0f
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(textColor.copy(alpha = 0.4f))
            )
            if (readerMode == ReaderMode.SETTINGS) {
                Text(
                    text = settingsStepLabel(settingsStep),
                    color = textColor,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = "$currentPageIndex / $totalPages",
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun PageContent(
    page: Page?,
    textColor: Color,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(scrollState),
    ) {
        if (page == null) {
            Text(
                "Загрузка...",
                color = textColor.copy(alpha = 0.5f),
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            )
        } else {
            for (line in page.lines) {
                val style = TextStyle(
                    color = textColor,
                    fontSize = with(density) { fontSize.toDp().toSp() },
                    fontWeight = if (line.isBold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    fontStyle = if (line.isItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                )

                // Indent headers
                val indent = if (line.isHeader) Modifier.padding(top = 8.dp, bottom = 4.dp) else Modifier

                Text(
                    text = line.text,
                    style = style,
                    modifier = indent,
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(
    settingsStep: SettingsStep,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    textColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = settingsStepLabel(settingsStep),
            color = textColor,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "◄ ► для настройки, ▲ ▼ для значения",
            color = textColor.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun settingsStepLabel(step: SettingsStep): String = when (step) {
    SettingsStep.BG_COLOR -> "Фон: ◄►цвет ▲▼ярк"
    SettingsStep.TEXT_COLOR -> "Текст: ◄►цвет ▲▼ярк"
    SettingsStep.FONT_SIZE -> "Шрифт: ◄►гарн ▲▼разм"
    SettingsStep.FONT_FACE -> "Гарнитура: ◄►выбор"
    SettingsStep.DONE -> "➤ Чтение"
}
