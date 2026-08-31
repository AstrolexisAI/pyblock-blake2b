package com.astrolexis.pyblock.ui.screens.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame

/** Persistence for the community-rules acceptance (store-policy UGC gate). */
object ChatTerms {
    fun accepted(ctx: Context): Boolean =
        ctx.getSharedPreferences("pyblock_chat", Context.MODE_PRIVATE).getBoolean("terms_accepted", false)

    fun accept(ctx: Context) =
        ctx.getSharedPreferences("pyblock_chat", Context.MODE_PRIVATE).edit().putBoolean("terms_accepted", true).apply()
}

/** Community-guidelines / EULA acceptance shown before a user can use the chat.
 *  Explicit zero-tolerance clause + report/block explanation, as required for
 *  user-generated content. Mirrors iOS ChatTermsGate. */
@Composable
fun ChatTermsGate(onAgree: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MarqueeTitle(text = stringResource(R.string.chatterms_title))
            Text(stringResource(R.string.chatterms_intro),
                style = PyType.mono(13f), color = PyTheme.cyan)

            Rule(stringResource(R.string.chatterms_rule_zero_tolerance_title),
                stringResource(R.string.chatterms_rule_zero_tolerance_body))
            Rule(stringResource(R.string.chatterms_rule_report_title),
                stringResource(R.string.chatterms_rule_report_body))
            Rule(stringResource(R.string.chatterms_rule_block_title),
                stringResource(R.string.chatterms_rule_block_body))
            Rule(stringResource(R.string.chatterms_rule_identity_title),
                stringResource(R.string.chatterms_rule_identity_body))

            Text(stringResource(R.string.chatterms_agree_button), style = PyType.mono(15f), color = PyTheme.bg, letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(PyTheme.primary)
                    .clickableNoRipple { Haptics.thock(); ChatTerms.accept(ctx); onAgree() }.padding(vertical = 13.dp))
            Text(stringResource(R.string.chatterms_agree_disclaimer),
                style = PyType.mono(9f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Rule(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = PyType.mono(13f), color = PyTheme.primary)
        Text(body, style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.85f))
    }
}
