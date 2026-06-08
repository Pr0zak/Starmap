package com.starmap.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.catalog.CatalogManager
import com.starmap.app.sky.SkyViewModel

@Composable
fun DownloadsScreen(viewModel: SkyViewModel, onBack: () -> Unit) {
    DetailScaffold(title = "Offline downloads", onBack = onBack) {
        val state by viewModel.downloadState

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "The naked-eye sky (8,920 stars to magnitude 6.5) and all constellation " +
                    "figures are built into the app, so Starmap works fully offline out of the box.",
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Extended star catalog", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "41,487 stars down to magnitude 8.0 — fainter stars for a richer sky. " +
                            "About 1.1 MB, stored on your device.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(12.dp))

                    when (val s = state) {
                        is CatalogManager.DownloadState.InProgress -> {
                            LinearProgressIndicator(
                                progress = { s.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Downloading… ${(s.fraction * 100).toInt()}%",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        is CatalogManager.DownloadState.Done -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Downloaded (${"%.1f".format(s.sizeBytes / 1_048_576.0)} MB)",
                                    modifier = Modifier.weight(1f),
                                    fontSize = 14.sp,
                                )
                                OutlinedButton(onClick = { viewModel.deleteExtendedCatalog() }) {
                                    Text("Delete")
                                }
                            }
                            Text(
                                "Enable \"Use extended catalog\" in Settings to load it.",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        is CatalogManager.DownloadState.Failed -> {
                            Text("Download failed: ${s.message}", fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { viewModel.downloadExtendedCatalog() }) { Text("Retry") }
                        }

                        CatalogManager.DownloadState.Idle -> {
                            Button(onClick = { viewModel.downloadExtendedCatalog() }) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
    }
}
