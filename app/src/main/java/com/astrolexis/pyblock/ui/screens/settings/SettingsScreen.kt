package com.astrolexis.pyblock.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.store.EntitlementsStore
import com.astrolexis.pyblock.data.store.ThemeStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyPalettes
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame

@Composable
fun SettingsScreen() {
    // Highlight the theme actually in effect (explicit pick, or the chain default).
    val selected = ThemeStore.effectivePaletteId

    // Full-screen LOTTO Official Rules (App Review 5.3.2). Mirrors iOS sheet.
    var showRules by remember { mutableStateOf(false) }
    if (showRules) {
        androidx.activity.compose.BackHandler { showRules = false }
        com.astrolexis.pyblock.ui.screens.academy.OfficialRulesScreen(onBack = { showRules = false })
        return
    }

    // Collapsible category groups (all start collapsed → a tidy menu).
    var expAccount by remember { mutableStateOf(false) }
    var expAppearance by remember { mutableStateOf(false) }
    var expWallet by remember { mutableStateOf(false) }
    var expAbout by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        MarqueeTitle(text = stringResource(R.string.settings_title))
        Spacer(Modifier.height(16.dp))

        SettingsGroup(stringResource(R.string.section_cat_account), expAccount, { expAccount = !expAccount }) {
        com.astrolexis.pyblock.ui.screens.pro.ProSection()
        Spacer(Modifier.height(24.dp))
        LinkedDevicesSection()
        }
        Spacer(Modifier.height(14.dp))

        SettingsGroup(stringResource(R.string.section_cat_appearance), expAppearance, { expAppearance = !expAppearance }) {
        val whale = EntitlementsStore.isWhale
        Column(
            Modifier.fillMaxWidth()
                .moduleFrame(PyTheme.primary)
                .padding(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_theme), style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                if (!whale) Text(stringResource(R.string.settings_premium_whale), style = PyType.mono(10f), color = PyTheme.primaryDim)
            }
            Spacer(Modifier.height(12.dp))
            // 4-column chip grid (mirrors iOS).
            PyPalettes.all.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { p -> Box(Modifier.weight(1f)) { ThemeChip(p, active = p.id == selected, locked = !p.free && !whale) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        val lang by com.astrolexis.pyblock.data.store.LocaleStore.lang.collectAsState()
        Column(
            Modifier.fillMaxWidth()
                .moduleFrame(PyTheme.primary)
                .padding(14.dp),
        ) {
            Text(stringResource(R.string.settings_language), style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_language_help), style = PyType.mono(11f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(12.dp))
            com.astrolexis.pyblock.data.store.LocaleStore.supported.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { l -> Box(Modifier.weight(1f)) { LangChip(l, active = l.code == lang) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Column(
            Modifier.fillMaxWidth()
                .moduleFrame(PyTheme.primary)
                .padding(14.dp),
        ) {
            Text(stringResource(R.string.settings_display), style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_display_help), style = PyType.mono(11f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(10.dp))
            FxToggleRow(stringResource(R.string.settings_arcade_visuals), com.astrolexis.pyblock.data.store.ArcadeStore.visualsEnabled) {
                com.astrolexis.pyblock.data.store.ArcadeStore.setVisuals(it)
            }
            var hapticsOn by remember { mutableStateOf(Haptics.enabled) }
            FxToggleRow(stringResource(R.string.settings_haptics), hapticsOn) { hapticsOn = it; Haptics.setEnabled(it); if (it) Haptics.select() }
            var sfxOn by remember { mutableStateOf(com.astrolexis.pyblock.ui.Sfx.enabled) }
            FxToggleRow(stringResource(R.string.settings_sound), sfxOn) { sfxOn = it; com.astrolexis.pyblock.ui.Sfx.setEnabled(it); if (it) com.astrolexis.pyblock.ui.Sfx.select() }
        }
        }
        Spacer(Modifier.height(14.dp))

        SettingsGroup(stringResource(R.string.section_cat_wallet), expWallet, { expWallet = !expWallet }) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Column(
            Modifier.fillMaxWidth()
                .moduleFrame(PyTheme.primary)
                .padding(14.dp),
        ) {
            Text(stringResource(R.string.settings_chat), style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_chat_pay_help), style = PyType.mono(11f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(10.dp))
            var payOn by remember { mutableStateOf(com.astrolexis.pyblock.data.nostr.Nostr.shareReceiveInChat(ctx)) }
            FxToggleRow(stringResource(R.string.settings_chat_pay), payOn) {
                payOn = it; com.astrolexis.pyblock.data.nostr.Nostr.setShareReceiveInChat(ctx, it); Haptics.select()
            }
            // EXPERIMENTAL — dev builds only. Enabling exposes Collaborative Send,
            // which builds and broadcasts a REAL mainnet transaction. Mirrors iOS #if DEBUG.
            if (com.astrolexis.pyblock.BuildConfig.DEBUG) {
                Spacer(Modifier.height(6.dp))
                FxToggleRow("Collaborative Send (beta)", com.astrolexis.pyblock.data.store.PayJoinFeature.enabled) {
                    com.astrolexis.pyblock.data.store.PayJoinFeature.set(it); Haptics.select()
                }
                Text(
                    "Experimental. Pay a PyBLOCK contact together — you both add a coin to one on-chain transaction, a more private way to pay. Dev builds only.",
                    style = PyType.mono(10f), color = PyTheme.primaryDim,
                )
            }
        }
        }
        Spacer(Modifier.height(14.dp))

        SettingsGroup(stringResource(R.string.section_cat_about), expAbout, { expAbout = !expAbout }) {
        // — SUPPORT — real humans in Telegram + docs. Mirrors iOS supportSection.
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        Column(
            Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp),
        ) {
            Text(stringResource(R.string.section_support), style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.support_humans), style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.8f))
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_open_support_chat),
                style = PyType.mono(14f), color = PyTheme.magenta, letterSpacing = 2.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .background(PyTheme.magenta.copy(alpha = 0.08f))
                    .border(2.dp, PyTheme.magenta, RectangleShape)
                    .clickableNoRipple { Haptics.tap(); uriHandler.openUri("https://t.me/pyblockpool") }
                    .padding(vertical = 10.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("📖 pyblock.xyz", style = PyType.mono(12f), color = PyTheme.cyan,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); uriHandler.openUri("https://pyblock.xyz:8443") })
        }

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.settings_about), style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp),
        ) {
            Text("PyBLØCK", style = PyType.mono(18f), color = PyTheme.primary)
            Text(stringResource(R.string.settings_tagline), style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.8f))
            // LOTTO Official Rules — App Review 5.3.2 (must be reachable in-app at all times).
            Text(stringResource(R.string.settings_lotto_official_rules), style = PyType.mono(13f), color = PyTheme.cyan, letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth().clickableNoRipple { Haptics.tap(); showRules = true }.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            Text("pyblock.xyz", style = PyType.mono(13f), color = PyTheme.cyan)
            Text(stringResource(R.string.settings_version, com.astrolexis.pyblock.BuildConfig.VERSION_NAME, com.astrolexis.pyblock.BuildConfig.VERSION_CODE),
                style = PyType.mono(11f), color = PyTheme.primaryDim)
        }
        }
        Spacer(Modifier.height(24.dp))
        }
    }
}

/** A collapsible Settings category: a tappable arcade-framed header row (title +
 *  chevron) that reveals its child sections when expanded. Children keep their own
 *  module frames — this only owns the header + the show/hide. Mirrors iOS SettingsGroup. */
@Composable
private fun SettingsGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .border(1.dp, PyTheme.primary.copy(alpha = 0.35f), RectangleShape)
                .clickableNoRipple { Haptics.tap(); onToggle() }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = PyType.mono(15f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "▾" else "▸", style = PyType.mono(16f), color = PyTheme.primaryDim)
        }
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** Retro on/off row: label + a lit ◉ ON / dim ○ OFF pill. Keeps the arcade
 *  look instead of a Material Switch; language-neutral state glyphs. */
@Composable
private fun FxToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple { onToggle(!on) }
            .padding(vertical = 8.dp),
    ) {
        Text(label, style = PyType.mono(13f), color = PyTheme.cyan, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(
            if (on) "◉ ON" else "○ OFF",
            style = PyType.mono(13f),
            color = if (on) PyTheme.primary else PyTheme.primaryDim,
            letterSpacing = 1.sp,
        )
    }
}

/** Theme swatch chip: color dot with check/lock + name, boxed when selected.
 *  Mirrors the iOS themeChip grid cell. */
@Composable
private fun ThemeChip(p: com.astrolexis.pyblock.ui.theme.PyPalette, active: Boolean, locked: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (active) p.primary else androidx.compose.ui.graphics.Color.Transparent, RectangleShape)
            .clickableNoRipple { Haptics.select(); if (!locked) ThemeStore.select(p.id) }
            .padding(vertical = 6.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(p.primary.copy(alpha = if (locked) 0.3f else 1f)))
            when {
                locked -> Text("🔒", style = PyType.mono(12f))
                active -> Text("✓", style = PyType.mono(15f), color = androidx.compose.ui.graphics.Color.Black)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(p.name, style = PyType.mono(9f), color = if (locked) PyTheme.primaryDim else PyTheme.cyan, maxLines = 1)
    }
}

/** Language chip: name + check when active, boxed. Mirrors the iOS language grid. */
@Composable
private fun LangChip(l: com.astrolexis.pyblock.data.store.LocaleStore.Lang, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (active) PyTheme.primary else PyTheme.primary.copy(alpha = 0.3f), RectangleShape)
            .clickableNoRipple { Haptics.select(); com.astrolexis.pyblock.data.store.LocaleStore.select(l.code) }
            .padding(vertical = 8.dp),
    ) {
        if (active) {
            Text("✓", style = PyType.mono(11f), color = PyTheme.primary)
            Spacer(Modifier.width(5.dp))
        }
        Text(l.name, style = PyType.mono(12f), color = if (active) PyTheme.primary else PyTheme.cyan, maxLines = 1)
    }
}
