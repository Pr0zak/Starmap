package com.starmap.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.sky.SearchTarget
import com.starmap.app.sky.SkyViewModel

@Composable
fun SearchScreen(viewModel: SkyViewModel, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { viewModel.search(query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    DetailScaffold(title = "Search the sky", onBack = onDone) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Star, planet, constellation…") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focus),
        )

        when {
            query.isBlank() -> Hint("Try “Orion”, “Jupiter”, “Betelgeuse”, “Andromeda”, “ISS”…")
            results.isEmpty() -> Hint("No matches for “$query”")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results) { r ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectSearchTarget(r.target)
                                onDone()
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(r.display, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            r.kind + (if (r.target is SearchTarget.StarT) " · star" else ""),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(16.dp),
    )
}
