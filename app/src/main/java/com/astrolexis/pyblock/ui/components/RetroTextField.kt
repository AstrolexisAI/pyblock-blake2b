package com.astrolexis.pyblock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

@Composable
fun RetroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    color: Color = PyTheme.primary,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PyType.mono(13f).copy(color = color),
        cursorBrush = SolidColor(color),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .background(PyTheme.primarySoft)
            .border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
            .padding(10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, style = PyType.mono(13f), color = PyTheme.primaryDim)
            }
            inner()
        },
    )
}
