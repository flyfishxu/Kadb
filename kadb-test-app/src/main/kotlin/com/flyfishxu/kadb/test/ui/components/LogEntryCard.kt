package com.flyfishxu.kadb.test.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.flyfishxu.kadb.test.model.LogEntry

@Composable
fun LogEntryCard(log: LogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                log.isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                log.level == "SUCCESS" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                log.level == "WARN" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    log.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when {
                        log.isError -> MaterialTheme.colorScheme.error
                        log.level == "SUCCESS" -> MaterialTheme.colorScheme.primary
                        log.level == "WARN" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    }
                ) {
                    Text(
                        log.level,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            
            Text(
                log.message,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
} 