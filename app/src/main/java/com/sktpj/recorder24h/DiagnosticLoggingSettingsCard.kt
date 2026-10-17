package com.sktpj.recorder24h

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.util.AppLogger
import com.sktpj.recorder24h.util.DiagnosticLogSettings
import org.json.JSONObject

@Composable
internal fun DiagnosticLoggingSettingsCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DiagnosticLogSettings.isDetailedEnabled(context)) }

    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("診断ログ", style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("詳細診断ログ")
                    Text(
                        if (enabled) "ON" else "OFF",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { next ->
                        if (DiagnosticLogSettings.setDetailedEnabled(context, next)) {
                            enabled = next
                            AppLogger.event(
                                context,
                                "DIAGNOSTIC_LOGGING_CHANGED",
                                JSONObject().put("detailed", next)
                            )
                        }
                    }
                )
            }
        }
    }
}
