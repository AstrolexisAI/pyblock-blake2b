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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield

/** Official rules for the PyBLØCK LOTTO block-reward draw ("sweepstakes"), required by App
 *  Review Guideline 5.3.2 to be available in-app at all times — including the explicit
 *  statement that Apple is not a sponsor. Mirrors iOS OfficialRulesView. */
@Composable
fun OfficialRulesScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
                Spacer(Modifier.weight(1f))
                MarqueeTitle(text = stringResource(R.string.rules_title))
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(22f), color = Color.Transparent)   // balance the row
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.rules_no_purchase), style = PyType.mono(11f), color = PyTheme.yellow)
            Spacer(Modifier.height(16.dp))

            rule(R.string.rules_s1_title, R.string.rules_s1_body)
            rule(R.string.rules_s2_title, R.string.rules_s2_body)
            rule(R.string.rules_s3_title, R.string.rules_s3_body)
            rule(R.string.rules_s4_title, R.string.rules_s4_body)
            rule(R.string.rules_s5_title, R.string.rules_s5_body)
            rule(R.string.rules_s6_title, R.string.rules_s6_body)
            rule(R.string.rules_s7_title, R.string.rules_s7_body)
            rule(R.string.rules_s8_title, R.string.rules_s8_body)

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.rules_last_updated), style = PyType.mono(10f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun rule(titleRes: Int, bodyRes: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(titleRes), style = PyType.mono(13f), color = PyTheme.cyan)
        Spacer(Modifier.height(5.dp))
        Text(stringResource(bodyRes), style = PyType.mono(12f), color = PyTheme.primaryDim)
    }
}
