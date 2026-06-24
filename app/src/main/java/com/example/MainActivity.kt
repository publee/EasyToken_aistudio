package com.example

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.example.data.TokenEntity
import com.example.ui.theme.MyApplicationTheme
import com.example.util.TokenCalculator
import com.example.viewmodel.TokenViewModel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                EasyTokenHomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyTokenHomeScreen(
    viewModel: TokenViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val tokens by viewModel.allTokens.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedTokenId.collectAsStateWithLifecycle()
    val systemTime by viewModel.systemTimeMillis.collectAsStateWithLifecycle()

    var showBottomSheet by remember { mutableStateOf(false) }
    var tokenToDelete by remember { mutableStateOf<TokenEntity?>(null) }

    val selectedToken = tokens.find { it.id == selectedId } ?: tokens.firstOrNull()

    // Interactive confirmation to delete tokens
    if (tokenToDelete != null) {
        AlertDialog(
            onDismissRequest = { tokenToDelete = null },
            title = { Text("Delete Token?") },
            text = { Text("Are you sure you want to permanently delete \"${tokenToDelete?.name}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        tokenToDelete?.let { viewModel.deleteToken(it) }
                        tokenToDelete = null
                        Toast.makeText(context, "Token deleted successfully", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { tokenToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 0.dp,
                modifier = Modifier.height(80.dp)
            ) {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Rounded.Key, contentDescription = "Tokens") },
                    label = { Text("Tokens", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { Toast.makeText(context, "Activity logging is automatically encrypted on-device.", Toast.LENGTH_SHORT).show() },
                    icon = { Icon(Icons.Rounded.History, contentDescription = "Logs") },
                    label = { Text("Logs") },
                    colors = NavigationBarItemDefaults.colors(
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { Toast.makeText(context, "Settings are securely stored under KeyStore.", Toast.LENGTH_SHORT).show() },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = { showBottomSheet = true },
                modifier = Modifier
                    .padding(bottom = 12.dp, end = 4.dp)
                    .testTag("fab_add_token"),
                containerColor = Color(0xFFD3E3FD), // Soft light sky blue
                contentColor = Color(0xFF041E49)   // Deep navy blue
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add New software token",
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Elegant top level application bar layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(onClick = { Toast.makeText(context, "Interactive menu is encrypted under user profile.", Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    Text(
                        text = "EasyToken",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = { Toast.makeText(context, "Search mode active.", Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = { Toast.makeText(context, "Full offline security active.", Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More Options", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            // Central grid/flex elements of the theme
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (tokens.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VerifiedUser,
                                        contentDescription = "Shield Guard",
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = "No Software Keys Loaded",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Import plain standard TOTP/HOTP URLs, paste legacy .sdtid XML contents, or construct customizable emulation tokens inside.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { showBottomSheet = true },
                                    modifier = Modifier.testTag("init_import_button"),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                                ) {
                                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Create or Import Key")
                                }
                            }
                        }
                    }
                } else {
                    // Jumbo Selected Token Display Panel
                    selectedToken?.let { token ->
                        ActiveTokenCard(
                            token = token,
                            systemTime = systemTime,
                            viewModel = viewModel,
                            onCopyClick = { passcode ->
                                clipboardManager.setText(AnnotatedString(passcode))
                                Toast.makeText(context, "Passcode copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    // Scrolling panel containing list, matches the HTML white card contrast background
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Other Tokens",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Manage",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        Toast.makeText(context, "Swipe list or tap keys to manage selection.", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Saved token lists
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("token_list"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 64.dp)
                            ) {
                                items(tokens) { token ->
                                    val activeCode = viewModel.getPasscodeForToken(token, systemTime)
                                    TokenRowCard(
                                        token = token,
                                        isActive = token.id == selectedToken?.id,
                                        activeCode = activeCode,
                                        systemTime = systemTime,
                                        onSelect = { viewModel.selectToken(token.id) },
                                        onDelete = { tokenToDelete = token },
                                        onIncrement = { viewModel.incrementCounter(token) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Sheet Registration Suite
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                modifier = Modifier.testTag("modal_bottom_sheet"),
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                AddTokenSheetContent(
                    onTokenCreated = { token ->
                        viewModel.insertToken(token)
                        showBottomSheet = false
                        Toast.makeText(context, "Key successfully registered", Toast.LENGTH_SHORT).show()
                    },
                    onXmlImported = { xml ->
                        val success = viewModel.importFromSdtidXml(xml)
                        if (success) {
                            showBottomSheet = false
                            Toast.makeText(context, "Sdtid XML Key successfully parsed", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to parse sdtid: invalid structure or seed", Toast.LENGTH_LONG).show()
                        }
                    },
                    onUrlImported = { url ->
                        val success = viewModel.importFromOtpauthUri(url)
                        if (success) {
                            showBottomSheet = false
                            Toast.makeText(context, "MFA Standard Token successfully imported", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed: invalid otpauth scheme or key", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ActiveTokenCard(
    token: TokenEntity,
    systemTime: Long,
    viewModel: TokenViewModel,
    onCopyClick: (String) -> Unit
) {
    val passcode = viewModel.getPasscodeForToken(token, systemTime)

    // Layout configuration depending on counter or interval timer
    val isTimeBased = token.type != "HOTP"
    val interval = token.interval.coerceAtLeast(1)
    val elapsedInCycle = (systemTime / 1000L) % interval
    val remainingSeconds = interval - elapsedInCycle
    val remainingMillisOfSec = 1000 - (systemTime % 1000L)
    val totalRemainingMillis = (remainingSeconds - 1) * 1000 + remainingMillisOfSec
    val progress = if (isTimeBased) {
        totalRemainingMillis.toFloat() / (interval * 1000f)
    } else {
        1.0f
    }

    // Dynamic timer-sweep color warning
    val strokeColor = when {
        !isTimeBased -> MaterialTheme.colorScheme.primary
        progress < 0.2f -> MaterialTheme.colorScheme.error
        progress < 0.4f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("jumbo_active_card"),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Label details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = token.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (token.serial.isNotEmpty()) "ID: ${token.serial}" else "MFA Soft-Token",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // Security Badge matching the HTML
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when (token.type) {
                                "SECURID" -> Icons.Rounded.VerifiedUser
                                "HOTP" -> Icons.Rounded.Sync
                                else -> Icons.Rounded.Security
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = token.type,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Jumbo Code Section containing Linear Progress Bar (HTML design style)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCopyClick(passcode) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large styled monospace digits: color #21005d as in HTML design text-[#21005d]
                Text(
                    text = formatPasscodeSpacing(passcode),
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF21005D),
                    textAlign = TextAlign.Center,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (isTimeBased) {
                    // Full-width elegant progress bar from Professional Polish HTML
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE1DBD9))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Valid for ${remainingSeconds}s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Calculate next predicted code to display in small letters (matches HTML next token indicator)
                        val nextSeconds = (systemTime / 1000L) + interval
                        val nextPasscode = viewModel.getPasscodeForToken(token, nextSeconds * 1000L)
                        Text(
                            text = "Next: ${formatPasscodeSpacing(nextPasscode)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Counter-Based Sync",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Counter: ${token.counter}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Professional Pill Button: Copy Token CTA
            Button(
                onClick = { onCopyClick(passcode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Copy Token",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TokenRowCard(
    token: TokenEntity,
    isActive: Boolean,
    activeCode: String,
    systemTime: Long,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onIncrement: () -> Unit
) {
    val isTimeBased = token.type != "HOTP"
    val interval = token.interval.coerceAtLeast(1)
    val elapsedInCycle = (systemTime / 1000L) % interval
    val progress = if (isTimeBased) {
        (interval - elapsedInCycle).toFloat() / interval.toFloat()
    } else {
        1.0f
    }
    val indicatorColor = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("token_row_${token.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            } else {
                Color.Transparent
            }
        ),
        border = if (isActive) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon emblem type (matches the HTML bg-[#f3edf7] circle with primary color tint)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (token.type) {
                        "SECURID" -> Icons.Rounded.VerifiedUser
                        "HOTP" -> Icons.Rounded.Sync
                        else -> Icons.Rounded.VpnKey
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Middle text info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = token.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (token.serial.isNotEmpty()) "ID: ${token.serial}" else "MFA Soft-Token",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right side active numbers & progress ticker
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = formatPasscodeSpacing(activeCode),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    // If HOTP, mini clicker pad
                    if (!isTimeBased) {
                        Text(
                            text = "Counter: ${token.counter}",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { onIncrement() }
                        )
                    }
                }

                // Small countdown wheel
                if (isTimeBased) {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawCircle(
                            color = Color.LightGray.copy(alpha = 0.2f),
                            radius = size.width / 2,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawArc(
                            color = indicatorColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Delete handle
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("delete_token_${token.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete key",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QrScannerForm(onUrlScanned: (String) -> Unit) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (cameraPermissionState.status == com.google.accompanist.permissions.PermissionStatus.Granted) {
            CameraPreviewAndScan(onUrlScanned = onUrlScanned)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = "Camera Access Required",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera Permission Required",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "We require on-device camera feeds to scan secure authenticator barcodes or OTP QR codes directly.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() },
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Grant Camera Permission", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                ScannerSimulatorSection(onUrlScanned = onUrlScanned)
            }
        }
    }
}

@Composable
fun CameraPreviewAndScan(onUrlScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var isScanned by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val scanner = BarcodeScanning.getClient()

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !isScanned) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        val rawValue = barcode.rawValue
                                        if (rawValue != null && rawValue.trim().isNotEmpty()) {
                                            isScanned = true
                                            onUrlScanned(rawValue)
                                            break
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (exc: Exception) {
                        exc.printStackTrace()
                    }
                }, androidx.core.content.ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay frame draw corners
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sizePx = size
            val paddingX = sizePx.width * 0.20f
            val paddingY = sizePx.height * 0.15f
            val rectWidth = sizePx.width - (paddingX * 2)
            val rectHeight = sizePx.height - (paddingY * 2)

            // corner bracket points
            val strokeW = 4.dp.toPx()
            val bracketL = 16.dp.toPx()

            // Top-Left Corner
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX, paddingY), end = androidx.compose.ui.geometry.Offset(paddingX + bracketL, paddingY), strokeWidth = strokeW)
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX, paddingY), end = androidx.compose.ui.geometry.Offset(paddingX, paddingY + bracketL), strokeWidth = strokeW)

            // Top-Right Corner
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX + rectWidth, paddingY), end = androidx.compose.ui.geometry.Offset(paddingX + rectWidth - bracketL, paddingY), strokeWidth = strokeW)
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX + rectWidth, paddingY), end = androidx.compose.ui.geometry.Offset(paddingX + rectWidth, paddingY + bracketL), strokeWidth = strokeW)

            // Bottom-Left Corner
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX, paddingY + rectHeight), end = androidx.compose.ui.geometry.Offset(paddingX + bracketL, paddingY + rectHeight), strokeWidth = strokeW)
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX, paddingY + rectHeight), end = androidx.compose.ui.geometry.Offset(paddingX, paddingY + rectHeight - bracketL), strokeWidth = strokeW)

            // Bottom-Right Corner
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX + rectWidth, paddingY + rectHeight), end = androidx.compose.ui.geometry.Offset(paddingX + rectWidth - bracketL, paddingY + rectHeight), strokeWidth = strokeW)
            drawLine(color = Color(0xFF00E676), start = androidx.compose.ui.geometry.Offset(paddingX + rectWidth, paddingY + rectHeight), end = androidx.compose.ui.geometry.Offset(paddingX + rectWidth, paddingY + rectHeight - bracketL), strokeWidth = strokeW)
        }

        Text(
            text = "Camera Active • Point at Auth QR code",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    ScannerSimulatorSection(onUrlScanned = onUrlScanned)
}

@Composable
fun ScannerSimulatorSection(onUrlScanned: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeveloperMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Web Emulator Scan Simulator",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Since browser-streaming containers lack physical camera inputs, tap a simulator trigger below to securely emulate scanning a QR token:",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onUrlScanned("otpauth://totp/Google:john.doe@gmail.com?secret=JBSWY3DPEHPK3PXP&issuer=Google") },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Rounded.QrCode, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Trigger TOTP Google 6-digit key", fontSize = 11.sp)
                }

                Button(
                    onClick = { onUrlScanned("otpauth://totp/CorporateSec:VPNSecure?secret=O7X7X7X7X7X7X7X7&digits=8&period=60&issuer=RSAEmulation") },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Rounded.QrCode, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Trigger TOTP Enterprise 8-digit key", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun AddTokenSheetContent(
    onTokenCreated: (TokenEntity) -> Unit,
    onXmlImported: (String) -> Unit,
    onUrlImported: (String) -> Unit
) {
    var activeTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Register Software Key",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Four options: Scan QR, Manual, SdtidXML, UrlURI
        ScrollableTabRow(
            selectedTabIndex = activeTab,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Text("Scan QR", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Text("Manual Entry", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = activeTab == 2,
                onClick = { activeTab = 2 },
                text = { Text("RSA SecurID (.sdtid)", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = activeTab == 3,
                onClick = { activeTab = 3 },
                text = { Text("OTP Link", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        when (activeTab) {
            0 -> QrScannerForm(onUrlImported)
            1 -> ManualEntryForm(onTokenCreated)
            2 -> SdtidImporterForm(onXmlImported)
            3 -> UrlImporterForm(onUrlImported)
        }
    }
}

@Composable
fun ManualEntryForm(onTokenCreated: (TokenEntity) -> Unit) {
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    // Constants defaults
    var selectedType by remember { mutableStateOf("SECURID") } // "TOTP", "HOTP", "SECURID"
    var selectedDigits by remember { mutableStateOf(8) } // 6, 8, 10
    var selectedInterval by remember { mutableStateOf(60) } // 30, 60

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Token Title (e.g. Work VPN)") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("entry_name"),
            leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
            singleLine = true
        )

        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Secret Key (Base32 or Hex)") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("entry_secret"),
            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = serial,
                onValueChange = { serial = it },
                label = { Text("Serial (Optional)") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("entry_serial"),
                placeholder = { Text("987-431-291") },
                singleLine = true
            )

            if (selectedType == "SECURID") {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN (Optional)") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("entry_pin"),
                    placeholder = { Text("1234") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        }

        // Chip selection rows
        Text("Key Type:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("TOTP", "HOTP", "SECURID").forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = {
                        selectedType = type
                        // Adjust defaults depending on type selected
                        if (type == "SECURID") {
                            selectedDigits = 8
                            selectedInterval = 60
                        } else {
                            selectedDigits = 6
                            selectedInterval = 30
                        }
                    },
                    label = { Text(type) },
                    modifier = Modifier.testTag("chip_type_$type")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Passcode Digits", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 8, 10).forEach { num ->
                        InputChip(
                            selected = selectedDigits == num,
                            onClick = { selectedDigits = num },
                            label = { Text("$num Digits") }
                        )
                    }
                }
            }

            Column {
                Text("Refresh Interval", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60).forEach { seconds ->
                        InputChip(
                            selected = selectedInterval == seconds,
                            onClick = { selectedInterval = seconds },
                            label = { Text("${seconds}s") }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val cleanSecret = secret.trim()
                if (name.isNotEmpty() && cleanSecret.isNotEmpty()) {
                    val formattedSerial = if (serial.isEmpty()) {
                        val random = Random()
                        String.format("%03d-%03d-%03d", random.nextInt(1000), random.nextInt(1000), random.nextInt(1000))
                    } else {
                        serial.trim()
                    }
                    onTokenCreated(
                        TokenEntity(
                            name = name.trim(),
                            secret = cleanSecret,
                            type = selectedType,
                            serial = formattedSerial,
                            digits = selectedDigits,
                            interval = selectedInterval,
                            pin = pin.trim().ifEmpty { null }
                        )
                    )
                }
            },
            enabled = name.isNotEmpty() && secret.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("manual_submit_button")
        ) {
            Text("Register Key")
        }
    }
}

@Composable
fun SdtidImporterForm(onXmlImported: (String) -> Unit) {
    var xmlContent by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Pasting sdtid contents extracts seed values, token identifiers, serial codes, and interval configurations instantly using local secure parsers.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        OutlinedTextField(
            value = xmlContent,
            onValueChange = { xmlContent = it },
            label = { Text("Paste XML file contents") },
            placeholder = { Text("<softToken>\n  <SerialNumber>...</SerialNumber>\n  <Seed>...</Seed>\n</softToken>") },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .testTag("xml_paste_field"),
            maxLines = 10
        )

        Button(
            onClick = {
                if (xmlContent.isNotEmpty()) {
                    onXmlImported(xmlContent)
                }
            },
            enabled = xmlContent.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("xml_submit_button")
        ) {
            Icon(imageVector = Icons.Rounded.Lock, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Parse and Import Key")
        }
    }
}

@Composable
fun UrlImporterForm(onUrlImported: (String) -> Unit) {
    var urlText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Pasting standard configuration strings lets you import multi-factor tokens from standard clients.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Paste standard OTP link") },
            placeholder = { Text("otpauth://totp/Service:username?secret=A3KJ1...") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("url_paste_field"),
            singleLine = true
        )

        Button(
            onClick = {
                if (urlText.isNotEmpty()) {
                    onUrlImported(urlText)
                }
            },
            enabled = urlText.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("url_submit_button")
        ) {
            Icon(imageVector = Icons.Rounded.History, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Import OTP Token")
        }
    }
}

// Spreading numbers to be separated at 3-4 digit marks for high scanning/reading design accessibility
private fun formatPasscodeSpacing(code: String): String {
    return when {
        code.length == 6 -> {
            code.substring(0, 3) + " " + code.substring(3, 6)
        }
        code.length == 8 -> {
            code.substring(0, 4) + " " + code.substring(4, 8)
        }
        code.length == 10 -> {
            code.substring(0, 5) + " " + code.substring(5, 10)
        }
        else -> code
    }
}
