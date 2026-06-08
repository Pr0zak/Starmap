package com.starmap.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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

            Spacer(Modifier.height(24.dp))
            SatelliteDownloads(viewModel)
        }
    }
}

@Composable
private fun SatelliteDownloads(viewModel: SkyViewModel) {
    val issBusy by viewModel.issBusy
    val starlinkBusy by viewModel.starlinkBusy
    val starlinkProgress by viewModel.starlinkProgress
    val message by viewModel.satMessage
    val mgr = viewModel.satelliteManager

    Text("Satellite tracking", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    Text(
        "Orbital elements come from Celestrak. They drift after a few days, so refresh " +
            "now and then. After downloading, enable each layer in Settings.",
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Space Station (ISS)", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                if (mgr.isIssDownloaded) "Downloaded — enable it in Settings." else "Tiny download (one satellite).",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (issBusy) {
                CircularProgressIndicator(modifier = Modifier.height(26.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.downloadIss() }) {
                        Text(if (mgr.isIssDownloaded) "Refresh" else "Download")
                    }
                    if (mgr.isIssDownloaded) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.deleteIss() }) { Text("Delete") }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Starlink", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                if (mgr.isStarlinkDownloaded) {
                    "Downloaded (${"%.1f".format(mgr.starlinkSizeBytes / 1_048_576.0)} MB) — enable in Settings."
                } else {
                    "The whole Starlink fleet — about 1 MB, thousands of satellites."
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (starlinkBusy) {
                LinearProgressIndicator(progress = { starlinkProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Downloading ${(starlinkProgress * 100).toInt()}%…",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.downloadStarlink() }) {
                        Text(if (mgr.isStarlinkDownloaded) "Refresh" else "Download")
                    }
                    if (mgr.isStarlinkDownloaded) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.deleteStarlink() }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (message != null) {
        Text(message!!, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}
