package org.onionroute.lite.ui.screens

import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onionroute.lite.service.AppInfo
import org.onionroute.lite.service.AppSelectionManager
import org.onionroute.lite.service.RoutingMode
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.theme.*

@Composable
fun AppSelectionScreen(appState: OnionRouteAppState) {
    val context = LocalContext.current
    val manager = remember { AppSelectionManager(context) }

    var routingMode by remember { mutableStateOf(manager.getRoutingMode()) }
    var searchQuery by remember { mutableStateOf("") }
    // Start with empty list + isLoading=true. getInstalledApps() loads
    // icons and queries PackageManager for every installed app — on a
    // phone with 100+ apps this takes 1-3 seconds and would freeze the
    // UI if done on the main thread. Load asynchronously on Dispatchers.IO.
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Per-app routed state, kept in a MutableStateMap so toggling an app
    // triggers recomposition WITHOUT having to reload the entire app list
    // (which would block the UI thread on PackageManager queries).
    // Initial values are loaded on demand (lazy) when each row first renders.
    val routedStates = remember { mutableStateMapOf<String, Boolean>() }

    // Initial load + reload when routing mode changes (selection state
    // differs between ALL and SELECTED_ONLY modes)
    LaunchedEffect(routingMode) {
        isLoading = true
        routedStates.clear()
        // Run the PackageManager queries + icon loads off the main thread.
        // Default Dispatchers.IO is appropriate — this is exactly the kind
        // of blocking I/O it's designed for.
        installedApps = withContext(Dispatchers.IO) {
            manager.getInstalledApps()
        }
        // Pre-populate routed state for every app in one shot. This is
        // fast because isAppTorRouted just reads from SharedPreferences.
        for (app in installedApps) {
            routedStates[app.packageName] = manager.isAppTorRouted(app.packageName)
        }
        isLoading = false
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val (userApps, systemApps) = remember(filteredApps) {
        filteredApps.partition { !it.isSystemApp }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OnionRouteColors.Black)
    ) {
        // ── Top Bar ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = OnionRouteColors.TextSecondary,
                fontSize = 24.sp,
                fontFamily = SyneFontFamily,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { appState.navigateBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "APP ROUTING",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            // ── Mode Selector ──────────────────────────────────
            ModeSelector(
                currentMode = routingMode,
                onModeChange = { mode ->
                    routingMode = mode
                    manager.setMode(mode)
                    // The VPN builder snapshots allowed/disallowed lists at
                    // startVpn() time — a running session keeps the OLD rules
                    // until it is restarted. Tell the user instead of leaving
                    // them wondering why nothing changed.
                    Toast.makeText(
                        context,
                        "Routing mode saved. Toggle VPN off/on to apply.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Search Bar ─────────────────────────────────────
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── App List ───────────────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Loading state — show a single placeholder row while
                // PackageManager queries + icon loads run on Dispatchers.IO.
                if (isLoading && installedApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading apps…",
                                style = OnionRouteTypography.BodyMedium.copy(
                                    color = OnionRouteColors.TextTertiary
                                )
                            )
                        }
                    }
                }

                // User apps
                if (userApps.isNotEmpty()) {
                    item {
                        Text(
                            text = "APPLICATIONS",
                            style = OnionRouteTypography.SectionHeader,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(userApps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isRouted = routedStates[app.packageName] ?: false,
                            onToggle = { newRouted ->
                                // Persist to SharedPreferences AND update the
                                // reactive map. Both are safe on the main thread.
                                manager.setAppTorRouted(app.packageName, newRouted)
                                routedStates[app.packageName] = newRouted
                            }
                        )
                    }
                }

                // System apps separator
                if (systemApps.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "SYSTEM APPLICATIONS",
                            style = OnionRouteTypography.SectionHeader,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(systemApps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isRouted = routedStates[app.packageName] ?: false,
                            onToggle = { newRouted ->
                                manager.setAppTorRouted(app.packageName, newRouted)
                                routedStates[app.packageName] = newRouted
                            }
                        )
                    }
                }

                // Bottom padding for scroll
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}

// ── Mode Selector ──────────────────────────────────────────────────

@Composable
private fun ModeSelector(
    currentMode: RoutingMode,
    onModeChange: (RoutingMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OnionRouteColors.Surface2)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeChip(
            label = "ALL APPS",
            isSelected = currentMode == RoutingMode.ALL,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(RoutingMode.ALL) }
        )
        ModeChip(
            label = "SELECTED ONLY",
            isSelected = currentMode == RoutingMode.SELECTED_ONLY,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(RoutingMode.SELECTED_ONLY) }
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) OnionRouteColors.JadeGlow else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (isSelected) OnionRouteColors.Jade.copy(alpha = 0.4f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = OnionRouteTypography.LabelSmall.copy(
                fontSize = 12.sp,
                color = if (isSelected) OnionRouteColors.Jade else OnionRouteColors.TextSecondary
            )
        )
    }
}

// ── Search Bar ─────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                text = "Search apps…",
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextTertiary)
            )
        },
        singleLine = true,
        textStyle = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextPrimary),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = OnionRouteColors.Surface2,
            unfocusedContainerColor = OnionRouteColors.Surface2,
            cursorColor = OnionRouteColors.Jade,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { /* dismiss keyboard */ })
    )
}

// ── App Row ────────────────────────────────────────────────────────

@Composable
private fun AppRow(
    app: AppInfo,
    isRouted: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isRouted) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2)
            .border(
                width = 1.dp,
                color = if (isRouted) OnionRouteColors.Jade.copy(alpha = 0.4f) else OnionRouteColors.BorderSubtle,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle(!isRouted) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon or placeholder
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(OnionRouteColors.Surface3),
            contentAlignment = Alignment.Center
        ) {
            if (app.icon != null) {
                AppIcon(drawable = app.icon)
            } else {
                Text(
                    text = app.appName.take(1).uppercase(),
                    style = OnionRouteTypography.BodyMedium.copy(
                        color = OnionRouteColors.TextTertiary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // App name + package
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = OnionRouteTypography.BodyMedium.copy(
                    color = if (isRouted) OnionRouteColors.Jade else OnionRouteColors.TextPrimary
                ),
                maxLines = 1
            )
            Text(
                text = app.packageName,
                style = OnionRouteTypography.LabelSmall.copy(
                    color = OnionRouteColors.TextTertiary
                ),
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Toggle indicator dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (isRouted) OnionRouteColors.Jade else OnionRouteColors.TextTertiary)
        )
    }
}

@Composable
private fun AppIcon(drawable: Drawable) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = android.view.ViewGroup.LayoutParams(
                    (36 * resources.displayMetrics.density).toInt(),
                    (36 * resources.displayMetrics.density).toInt()
                )
            }
        },
        modifier = Modifier.size(36.dp)
    )
}
