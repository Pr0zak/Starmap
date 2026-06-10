package com.starmap.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.BuildConfig
import com.starmap.app.sky.SkyViewModel
import com.starmap.app.update.ApkUpdater
import com.starmap.app.update.DiagPrefs
import com.starmap.app.update.LogUploader
import com.starmap.app.update.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AboutScreen(viewModel: SkyViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checking by viewModel.checkingUpdate
    val result by viewModel.updateResult

    fun openUrl(url: String) {
        if (url.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    val repoUrl = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}"

    DetailScaffold(title = "About & updates", onBack = onBack) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("Starmap", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                fontSize = 14.sp,
            )
            Text(
                "A point-and-identify star map. Hold your phone up to the sky and Starmap " +
                    "shows the stars, constellations, Sun and Moon in the direction you are facing.",
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Software updates", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    when (val r = result) {
                        is UpdateChecker.Result.Available ->
                            UpdateAvailableSection(viewModel, r.release) { openUrl(it) }
                        is UpdateChecker.Result.UpToDate ->
                            Text("You're on the latest version.", fontSize = 14.sp)
                        is UpdateChecker.Result.Failed ->
                            Text("Couldn't check: ${r.message}", fontSize = 13.sp)
                        null -> Text("Tap below to check GitHub for a newer release.", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.height(28.dp))
                    } else {
                        OutlinedButton(onClick = { viewModel.checkForUpdates() }) {
                            Text("Check for updates")
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Diagnostics", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "Optional: paste a GitHub token with the 'gist' scope to upload reports " +
                            "as a secret Gist. Stored only on this device; leave blank to use an " +
                            "anonymous paste link instead.",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    var token by remember { mutableStateOf(DiagPrefs.getToken(context)) }
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; DiagPrefs.setToken(context, it) },
                        label = { Text("GitHub token (gist scope)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val clipboard = LocalClipboardManager.current
                    var diagStatus by remember { mutableStateOf<String?>(null) }
                    var diagLink by remember { mutableStateOf<String?>(null) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            diagLink = null
                            diagStatus = "Uploading…"
                            val crash = File(context.filesDir, "last_crash.txt")
                            val text = if (crash.exists()) {
                                crash.readText()
                            } else {
                                "Starmap diagnostics — no crash on record.\n" +
                                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                            }
                            LogUploader.upload(context, text)
                                .onSuccess {
                                    diagLink = it
                                    clipboard.setText(AnnotatedString(it))
                                    diagStatus = "Uploaded — link copied to clipboard."
                                }
                                .onFailure { diagStatus = "Failed: ${it.message}"; diagLink = null }
                        }
                    }) { Text("Upload diagnostics") }
                    diagStatus?.let {
                        Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    diagLink?.let { link ->
                        SelectionContainer {
                            Text(
                                link,
                                fontSize = 12.sp,
                                color = Color(0xFF4DA3FF),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                                Text("Copy link")
                            }
                            TextButton(onClick = { openUrl(link) }) { Text("Open") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Source & releases", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            OutlinedButton(onClick = { openUrl(repoUrl) }, modifier = Modifier.padding(top = 6.dp)) {
                Text("View on GitHub")
            }

            Spacer(Modifier.height(20.dp))
            Text("Data & licenses", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "Star data: HYG database v4.1 (astronexus), CC BY-SA 4.0.\n" +
                    "Constellation figures: d3-celestial (Olaf Frohn), BSD-2-Clause.\n" +
                    "Sun/Moon: Schlyter's low-precision ephemeris.",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Download-and-install flow for an available release (in-place self-update). */
@Composable
private fun UpdateAvailableSection(
    viewModel: SkyViewModel,
    release: UpdateChecker.Release,
    openUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val download by viewModel.updateDownload

    Column {
        Text("Version ${release.versionName} is available!", fontSize = 15.sp)
        if (release.notes.isNotBlank()) {
            Text(release.notes.take(400), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))

        when (val d = download) {
            is ApkUpdater.State.Idle -> {
                if (release.apkUrl != null) {
                    Button(onClick = { viewModel.downloadUpdate(release.apkUrl) }) {
                        Text("Download & install")
                    }
                } else {
                    // No APK attached to the release — fall back to the release page.
                    Button(onClick = { openUrl(release.htmlUrl) }) { Text("Open release page") }
                }
            }

            is ApkUpdater.State.Downloading -> {
                LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Downloading ${(d.fraction * 100).toInt()}%…",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            is ApkUpdater.State.ReadyToInstall -> {
                Text(
                    "Downloaded. Installing updates over the top keeps your settings — " +
                        "if prompted, allow Starmap to install apps.",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (ApkUpdater.canInstall(context)) {
                        ApkUpdater.install(context, d.file)
                    } else {
                        context.startActivity(ApkUpdater.installPermissionIntent(context))
                    }
                }) { Text("Install now") }
            }

            is ApkUpdater.State.Failed -> {
                Text("Update download failed: ${d.message}", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    viewModel.resetUpdateDownload()
                    release.apkUrl?.let { viewModel.downloadUpdate(it) }
                }) { Text("Retry") }
                TextButton(onClick = { openUrl(release.apkUrl ?: release.htmlUrl) }) {
                    Text("Open in browser instead")
                }
            }
        }
    }
}
