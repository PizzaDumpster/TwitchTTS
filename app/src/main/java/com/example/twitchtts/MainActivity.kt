package com.example.twitchtts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.twitchtts.ui.theme.TwitchTTSTheme
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: TwitchChatViewModel by lazy { TwitchChatViewModel() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Make sure we initialize TTS in the activity context for older Android versions
        viewModel.initTts(applicationContext)
        
        // Check TTS availability - this can help diagnose issues on older Android versions
        viewModel.checkTtsAvailability(applicationContext)
        
        setContent {
            TwitchTTSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TwitchChatScreen(viewModel)
                }
            }
        }
    }
    
    override fun onDestroy() {
        // Make sure TTS is properly cleaned up when the activity is destroyed
        super.onDestroy()
        viewModel.clearTtsQueue()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwitchChatScreen(viewModel: TwitchChatViewModel = viewModel()) {
    val context = LocalContext.current
    val channelName by viewModel.channelName
    val isConnected by viewModel.isConnected
    val isTtsEnabled by viewModel.isTtsEnabled
    val chatMessages = viewModel.chatMessages

    // TTS queue-related states
    val currentQueueSize by viewModel.currentQueueSize
    val speechRate by viewModel.speechRate

    // Speech rate slider dialog state
    var showSpeechRateDialog by remember { mutableStateOf(false) }

    // Create a LazyListState to control scrolling
    val lazyListState = rememberLazyListState()

    // Create a CoroutineScope to launch scroll operations
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll when messages change
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            // Scroll to the first item (newest message) when using reverseLayout=true
            coroutineScope.launch {
                lazyListState.animateScrollToItem(0)
            }
        }
    }

    // No need to initialize TTS here since we're now doing it in the Activity

    // Speech rate dialog
    if (showSpeechRateDialog) {
        AlertDialog(
            onDismissRequest = { showSpeechRateDialog = false },
            title = { Text("Adjust Speech Rate") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${String.format(Locale.US, "%.1f", speechRate)}x",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Slider(
                        value = speechRate,
                        onValueChange = { viewModel.setSpeechRate(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 15,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Slower (0.5x)", fontSize = 12.sp)
                        Text("Faster (2.0x)", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSpeechRateDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TwitchTTS") },
                actions = {
                    // TTS Queue info
                    if (isTtsEnabled && isConnected) {
                        Text(
                            "Queue: $currentQueueSize",
                            modifier = Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Skip current message button
                    if (isTtsEnabled && currentQueueSize > 0) {
                        IconButton(onClick = { viewModel.skipCurrentMessage() }) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Skip current message"
                            )
                        }
                    }

                    // Clear TTS queue button
                    if (isTtsEnabled && currentQueueSize > 0) {
                        IconButton(onClick = { viewModel.clearTtsQueue() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear TTS queue"
                            )
                        }
                    }

                    // Speech rate button
                    if (isTtsEnabled) {
                        IconButton(onClick = { showSpeechRateDialog = true }) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = "Adjust speech rate"
                            )
                        }
                    }

                    // Clear chat button
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear Chat")
                    }

                    // Toggle TTS button
                    IconButton(onClick = { viewModel.toggleTts() }) {
                        Icon(
                            if (isTtsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (isTtsEnabled) "Disable TTS" else "Enable TTS"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Chat messages list with state for auto-scrolling
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = true
            ) {
                items(chatMessages, key = { it.id }) { message ->
                    ChatMessageItem(message)
                }

                if (chatMessages.isEmpty() && !isConnected) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "Enter a channel name below and tap 'View Chat'",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "No login required!",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }

            // Connection controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = channelName,
                    onValueChange = { viewModel.channelName.value = it },
                    label = { Text("Twitch Channel Name") },
                    placeholder = { Text("Enter channel name (e.g., 'xqc')") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isConnected) {
                                viewModel.connectToTwitchChat()
                            }
                        }
                    )
                )

                Button(
                    onClick = {
                        if (isConnected) {
                            viewModel.disconnectFromTwitchChat()
                        } else {
                            viewModel.connectToTwitchChat()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(if (isConnected) "Disconnect" else "View Chat")
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val userColor = try {
        Color(message.color.toColorInt())
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message.username,
                    fontWeight = FontWeight.Bold,
                    color = userColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formatTimestamp(message.timestamp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = message.message,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
    return format.format(date)
}