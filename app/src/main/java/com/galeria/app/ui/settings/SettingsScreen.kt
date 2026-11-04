package com.galeria.app.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galeria.app.data.AppSettings
import com.galeria.app.ui.components.*
import com.galeria.app.ui.theme.GlassTokens

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onGridColumns: (Int) -> Unit,
    onGroupByDate: (Boolean) -> Unit,
    onAnimations: (Boolean) -> Unit,
    onGlassOpacity: (Float) -> Unit,
    onAppLock: (Boolean) -> Unit,
    onHiddenAlbumLock: (Boolean) -> Unit,
    onAutoLockMins: (Int) -> Unit,
    onShowHiddenAlbum: (Boolean) -> Unit,
    onDeletedProtection: (Boolean) -> Unit,
    onShowFileInfo: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            LumaTopBar(title = "Settings")
        }

        // ── Appearance ─────────────────────────────────────────
        item {
            GlassSection(title = "Appearance") {
                // Grid columns
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.GridView, null, tint = GlassTokens.AccentBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Grid Columns", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextPrimary, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 3, 4).forEach { cols ->
                            val active = settings.gridColumns == cols
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) GlassTokens.AccentBlue else GlassTokens.GlassUltraClear)
                                    .border(1.dp, if (active) GlassTokens.AccentBlue else GlassTokens.BorderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { onGridColumns(cols) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$cols", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (active) GlassTokens.TextPrimary else GlassTokens.TextSecondary)
                            }
                        }
                    }
                }

                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)

                // Glass opacity slider
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Opacity, null, tint = GlassTokens.AccentBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Glass Transparency", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextPrimary, modifier = Modifier.weight(1f))
                        Text("${(settings.glassOpacity * 100).toInt()}%", fontSize = 13.sp, color = GlassTokens.TextSecondary)
                    }
                    Slider(
                        value = settings.glassOpacity,
                        onValueChange = onGlassOpacity,
                        valueRange = 0.05f..0.4f,
                        colors = SliderDefaults.colors(
                            thumbColor = GlassTokens.AccentBlue,
                            activeTrackColor = GlassTokens.AccentBlue
                        )
                    )
                }

                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)

                // Group by date
                GlassSettingsRow(
                    icon = Icons.Filled.CalendarMonth,
                    title = "Group by Date",
                    iconTint = GlassTokens.AccentPurple,
                    trailing = {
                        Switch(
                            checked = settings.groupByDate,
                            onCheckedChange = onGroupByDate,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlassTokens.AccentBlue, checkedTrackColor = GlassTokens.AccentBlue.copy(0.4f))
                        )
                    }
                )

                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)

                // Show file info
                GlassSettingsRow(
                    icon = Icons.Filled.Info,
                    title = "Show File Information",
                    iconTint = GlassTokens.AccentTeal,
                    trailing = {
                        Switch(
                            checked = settings.showFileInfo,
                            onCheckedChange = onShowFileInfo,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlassTokens.AccentBlue, checkedTrackColor = GlassTokens.AccentBlue.copy(0.4f))
                        )
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Animations ─────────────────────────────────────────
        item {
            GlassSection(title = "Animations") {
                GlassSettingsRow(
                    icon = Icons.Filled.Animation,
                    title = "Enable Animations",
                    iconTint = GlassTokens.AccentPurple,
                    trailing = {
                        Switch(
                            checked = settings.animationsEnabled,
                            onCheckedChange = onAnimations,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlassTokens.AccentBlue, checkedTrackColor = GlassTokens.AccentBlue.copy(0.4f))
                        )
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Privacy & Security ─────────────────────────────────
        item {
            GlassSection(title = "Privacy & Security") {
                GlassSettingsRow(
                    icon = Icons.Filled.Lock,
                    title = "App Lock",
                    subtitle = "Require biometric to open LUMA",
                    iconTint = GlassTokens.Danger,
                    trailing = {
                        Switch(
                            checked = settings.appLockEnabled,
                            onCheckedChange = onAppLock,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlassTokens.AccentBlue, checkedTrackColor = GlassTokens.AccentBlue.copy(0.4f))
                        )
                    }
                )
                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                GlassSettingsRow(
                    icon = Icons.Filled.Fingerprint,
                    title = "Hidden Album Lock",
                    subtitle = "Biometric to view hidden photos",
                    iconTint = GlassTokens.AccentPurple,
                    trailing = {
                        Switch(
                            checked = settings.hiddenAlbumLock,
                            onCheckedChange = onHiddenAlbumLock,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlassTokens.AccentBlue, checkedTrackColor = GlassTokens.AccentBlue.copy(0.4f))
                        )
                    }
                )
                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                // Auto lock
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Timer, null, tint = GlassTokens.Warning, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Auto Lock", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextPrimary, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "1m", 5 to "5m", 15 to "15m").forEach { (mins, label) ->
                            val active = settings.autoLockMins == mins
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) GlassTokens.AccentBlue else GlassTokens.GlassUltraClear)
                                    .border(1.dp, if (active) GlassTokens.AccentBlue else GlassTokens.BorderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { onAutoLockMins(mins) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, fontSize = 12.sp, color = if (active) GlassTokens.TextPrimary else GlassTokens.TextSecondary)
                            }
                        }
                    }
                }
                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                GlassSettingsRow(
                    icon = Icons.Filled.VisibilityOff,
                    title = "Show Hidden Album",
                    subtitle = "Display in Albums screen",
                    iconTint = GlassTokens.TextSecondary,
                    trailing = {
                        Switch(
                            checked = settings.showHiddenAlbum,
                            onCheckedChange = onShowHiddenAlbum,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlassTokens.AccentBlue, checkedTrackColor = GlassTokens.AccentBlue.copy(0.4f))
                        )
                    }
                )
                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                GlassSettingsRow(
                    icon = Icons.Filled.DeleteForever,
                    title = "Protect Recently Deleted",
                    subtitle = "Require biometric to view",
                    iconTint = GlassTokens.Danger,
                    trailing = {
                        Switch(
                            checked = settings.deletedProtection,
                            onCheckedChange = onDeletedProtection,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlassTokens.AccentBlue, checkedTrackColor = GlassTokens.AccentBlue.copy(0.4f))
                        )
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── About ──────────────────────────────────────────────
        item {
            GlassSection(title = "About") {
                GlassSettingsRow(
                    icon = Icons.Filled.Apps,
                    title = "LUMA Gallery Pro",
                    subtitle = "Version 1.0.0",
                    iconTint = GlassTokens.AccentBlue,
                    onClick = {}
                )
                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                GlassSettingsRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = "Privacy Information",
                    iconTint = GlassTokens.AccentPurple,
                    onClick = {}
                )
                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                GlassSettingsRow(
                    icon = Icons.Filled.Code,
                    title = "Open Source Licenses",
                    iconTint = GlassTokens.TextSecondary,
                    onClick = {}
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
