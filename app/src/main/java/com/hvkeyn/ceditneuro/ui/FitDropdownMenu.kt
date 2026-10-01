package com.hvkeyn.ceditneuro.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** A menu that stays on screen and scrolls when the list is long. */
@Composable
fun FitDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val cap = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .heightIn(max = cap)
            .widthIn(max = 320.dp),
        content = content,
    )
}
