package com.hinnka.mycamera.ui.settings

import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hinnka.mycamera.R
import com.hinnka.mycamera.ui.components.SwitchSettingItem
import com.hinnka.mycamera.video.VideoFrameDiagnostics

@Composable
internal fun VideoFrameDiagnosticsSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(VideoFrameDiagnostics.isEnabled(context)) }
    var latestReport by remember { mutableStateOf(VideoFrameDiagnostics.latestReport(context)) }

    LaunchedEffect(Unit) {
        latestReport = VideoFrameDiagnostics.latestReport(context)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_video_diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_video_diagnostics_description),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.68f),
        )
        Spacer(modifier = Modifier.height(8.dp))

        SwitchSettingItem(
            title = stringResource(R.string.settings_video_diagnostics_toggle_title),
            description = stringResource(R.string.settings_video_diagnostics_toggle_description),
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                VideoFrameDiagnostics.setEnabled(context, checked)
            },
        )

        HorizontalDivider(
            color = Color.White.copy(alpha = 0.1f),
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_video_diagnostics_report_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val report = latestReport?.takeIf { it.isFile }
                Text(
                    text = report?.let {
                        stringResource(
                            R.string.settings_video_diagnostics_report_time,
                            DateFormat.format("yyyy-MM-dd HH:mm", it.lastModified()).toString(),
                        )
                    } ?: stringResource(R.string.settings_video_diagnostics_no_report),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.68f),
                )
            }

            TextButton(
                enabled = latestReport?.isFile == true,
                onClick = {
                    val file = latestReport?.takeIf { it.isFile } ?: return@TextButton
                    runCatching {
                        val reportUri = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            file,
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, reportUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                context.getString(R.string.settings_video_diagnostics_share_chooser),
                            )
                        )
                    }.onFailure {
                        Toast.makeText(
                            context,
                            R.string.settings_video_diagnostics_share_error,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ) {
                Text(stringResource(R.string.settings_video_diagnostics_share))
            }
        }
    }
}
