package com.astrolexis.pyblock.ui.screens.academy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.net.ProRepo
import com.astrolexis.pyblock.data.store.EntitlementsStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitcoinAcademyScreen() {
    var open by remember { mutableStateOf<AcademyLesson?>(null) }
    // In-memory cache of premium bodies fetched this session (offline fallback).
    val bodyCache = remember { mutableStateMapOf<Int, String>() }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
    ) {
        MarqueeTitle(text = stringResource(R.string.academy_title))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.academy_subtitle),
            style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(14.dp))
        val whale = EntitlementsStore.isWhale
        Academy.lessons.forEach { lesson ->
            val locked = lesson.premium && !whale
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .moduleFrame(PyTheme.primary.copy(alpha = if (locked) 0.15f else 0.3f))
                    .clickableNoRipple {
                        Haptics.select()
                        if (locked) com.astrolexis.pyblock.ui.screens.pro.PaywallBus.present(
                            com.astrolexis.pyblock.ui.screens.pro.PaywallContext.academy)
                        else open = lesson
                    }
                    .padding(12.dp),
            ) {
                Text(String.format(java.util.Locale.US, "%02d", lesson.id), style = PyType.mono(18f), color = PyTheme.primaryDim)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(lesson.title, style = PyType.mono(15f), color = if (locked) PyTheme.primaryDim else PyTheme.primary)
                    Text(lesson.summary, style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.7f))
                }
                if (lesson.premium) Text(if (locked) "🔒" else "🐋", style = PyType.mono(13f))
                Spacer(Modifier.width(6.dp))
                Text(if (locked) " " else "›", style = PyType.mono(16f), color = PyTheme.primaryDim)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!whale) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.academy_whale_unlock_cta),
                style = PyType.mono(11f), color = PyTheme.magenta,
                modifier = Modifier.clickableNoRipple {
                    Haptics.coin()
                    com.astrolexis.pyblock.ui.screens.pro.PaywallBus.present(
                        com.astrolexis.pyblock.ui.screens.pro.PaywallContext.academy)
                })
        }
    }
    }

    open?.let { lesson ->
        ModalBottomSheet(
            onDismissRequest = { open = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PyTheme.bg,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(lesson.summary.uppercase(), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Text(lesson.title, style = PyType.mono(24f), color = PyTheme.primary)
                Spacer(Modifier.height(14.dp))
                LessonBody(lesson, bodyCache)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Free bodies are bundled; premium bodies are fetched on demand from
 *  lesson.php (server-gated to Whale) and cached for the session. */
@Composable
private fun LessonBody(lesson: AcademyLesson, cache: MutableMap<Int, String>) {
    if (lesson.body.isNotBlank()) {
        Text(lesson.body, style = PyType.mono(15f), color = PyTheme.cyan.copy(alpha = 0.92f))
        return
    }
    val cached = cache[lesson.id]
    if (cached != null) {
        Text(cached, style = PyType.mono(15f), color = PyTheme.cyan.copy(alpha = 0.92f))
        return
    }
    var failed by remember(lesson.id) { mutableStateOf(false) }
    LaunchedEffect(lesson.id) {
        failed = false
        val b = ProRepo.lesson(lesson.id)
        if (b != null) cache[lesson.id] = b else failed = true
    }
    when {
        failed -> Text(stringResource(R.string.academy_load_failed),
            style = PyType.mono(13f), color = PyTheme.danger)
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = PyTheme.primary, modifier = Modifier.height(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.academy_loading_lesson), style = PyType.mono(12f), color = PyTheme.primaryDim)
        }
    }
}
