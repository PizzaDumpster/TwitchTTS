package com.example.twitchtts

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
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
import kotlinx.coroutines.delay
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

    // Dialog states
    var showSpeechRateDialog by remember { mutableStateOf(false) }
    var showVoiceSelectionDialog by remember { mutableStateOf(false) }

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
                    var temporarySpeechRate by remember { mutableStateOf(speechRate) }
                    
                    Text(
                        "${String.format(Locale.US, "%.1f", temporarySpeechRate)}x",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Slider(
                        value = temporarySpeechRate,
                        onValueChange = { temporarySpeechRate = it },
                        onValueChangeFinished = { 
                            viewModel.setSpeechRate(temporarySpeechRate) 
                        },
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
    
    // Voice selection dialog
    if (showVoiceSelectionDialog) {
        val availableVoices = viewModel.availableVoices
        val selectedVoice by viewModel.selectedVoice
        
        AlertDialog(
            onDismissRequest = { showVoiceSelectionDialog = false },
            title = { 
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Voice")
                    Text(
                        "(${availableVoices.size} voices)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                if (availableVoices.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Loading available voices...")
                        }
                    }
                } else {
                    // Create a state for the lazy list to enable scrolling
                    val voiceListState = rememberLazyListState()
                    // Add search functionality
                    var searchQuery by remember { mutableStateOf("") }
                    
                    // Filter voices based on search query
                    val filteredVoices = if (searchQuery.isEmpty()) {
                        availableVoices
                    } else {
                        availableVoices.filter { 
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.locale.displayName.contains(searchQuery, ignoreCase = true)
                        }
                    }
                    
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Search box
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            placeholder = { Text("Search voices...") },
                            singleLine = true,
                            leadingIcon = { 
                                Icon(
                                    Icons.Default.Mic, 
                                    contentDescription = "Search"
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = "Clear search"
                                        )
                                    }
                                }
                            }
                        )
                        
                        // Display result count when searching
                        if (searchQuery.isNotEmpty()) {
                            Text(
                                text = "${filteredVoices.size} matching voices",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        
                        LazyColumn(
                            state = voiceListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp, max = 350.dp) // Adjust height to accommodate search box
                        ) {
                        items(
                            items = filteredVoices,
                            key = { voice -> voice.id }
                        ) { voice ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        // Use coroutine to avoid UI thread blocking
                                        coroutineScope.launch {
                                            try {
                                                viewModel.setVoice(voice)
                                                delay(500) // Wait longer for voice to be applied
                                                viewModel.speakTestPhrase()
                                            } catch(e: Exception) {
                                                Log.e("TwitchTTS", "Error selecting voice", e)
                                            }
                                        }
                                    }
                                    .padding(8.dp)
                            ) {
                                RadioButton(
                                    selected = voice.id == selectedVoice?.id,
                                    onClick = { 
                                        coroutineScope.launch {
                                            try {
                                                // Ensure we don't already have this voice selected to avoid unnecessary changes
                                                if (voice.id != selectedVoice?.id) {
                                                    viewModel.setVoice(voice)
                                                }
                                            } catch (e: Exception) {
                                                Log.e("TwitchTTS", "Error selecting voice", e)
                                            }
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = voice.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = voice.locale.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    } // Close the Column
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Test selected voice button
                    OutlinedButton(
                        onClick = { 
                            selectedVoice?.let { 
                                coroutineScope.launch {
                                    try {
                                        // Make sure there's a small delay before testing
                                        delay(150)
                                        viewModel.speakTestPhrase()
                                    } catch (e: Exception) {
                                        Log.e("TwitchTTS", "Error testing voice", e)
                                    }
                                }
                            }
                        },
                        enabled = selectedVoice != null
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Test Voice",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Test")
                    }
                    
                    // Done button
                    Button(onClick = { showVoiceSelectionDialog = false }) {
                        Text("Done")
                    }
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
                    
                    // Voice selection button
                    if (isTtsEnabled) {
                        IconButton(onClick = { showVoiceSelectionDialog = true }) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Select voice"
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