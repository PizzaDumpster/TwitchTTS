package com.example.twitchtts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.UUID
import kotlin.random.Random

/**
 * A lightweight Twitch chat client using IRC protocol
 */
class TwitchChatService {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false
    
    private val TAG = "TwitchChatService"
    private val SERVER = "irc.chat.twitch.tv"
    private val PORT = 6667
    
    /**
     * Connect to Twitch chat for a specific channel
     * @param channelName The channel to join
     * @param onMessage Callback for received messages
     */
    suspend fun connectToChat(channelName: String, onMessage: (ChatMessage) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Disconnect if already connected
                disconnect()
                
                // Generate a random username for anonymous connection
                val randomNick = "justinfan${Random.nextInt(10000, 99999)}"
                
                // Connect to Twitch IRC server
                socket = Socket(SERVER, PORT)
                writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream()))
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                
                // Register with the server (anonymous)
                sendCommand("PASS oauth:anonymous")
                sendCommand("NICK $randomNick")
                
                // Request capabilities for getting user colors and badges
                sendCommand("CAP REQ :twitch.tv/tags")
                sendCommand("CAP REQ :twitch.tv/commands")
                
                // Join the channel
                sendCommand("JOIN #${channelName.lowercase()}")
                
                // Mark as running
                isRunning = true
                
                // Start reading messages in a loop
                while (isRunning && socket?.isConnected == true) {
                    val line = reader?.readLine()
                    
                    if (line == null) {
                        Log.d(TAG, "End of stream")
                        break
                    }
                    
                    // Handle server PING to avoid disconnection
                    if (line.startsWith("PING")) {
                        sendCommand("PONG ${line.substring(5)}")
                        continue
                    }
                    
                    // Parse chat messages
                    parseChatMessage(line)?.let { message ->
                        onMessage(message)
                    }
                }
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to Twitch chat", e)
                disconnect()
                false
            }
        }
    }
    
    /**
     * Disconnect from Twitch chat
     */
    fun disconnect() {
        try {
            isRunning = false
            writer?.close()
            reader?.close()
            socket?.close()
            
            socket = null
            writer = null
            reader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
    
    /**
     * Send a raw command to the IRC server
     */
    private fun sendCommand(command: String) {
        try {
            writer?.write("$command\r\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command", e)
        }
    }
    
    /**
     * Parse a raw IRC message into a ChatMessage object
     */
    private fun parseChatMessage(raw: String): ChatMessage? {
        try {
            // Skip server messages
            if (!raw.contains("PRIVMSG")) {
                return null
            }
            
            // Extract user color from tags
            val colorMatch = "color=([^;]+)".toRegex().find(raw)
            val color = colorMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: "#FFFFFF"
            
            // Extract username and message
            val userNameMatch = ":(\\w+)!".toRegex().find(raw)
            val username = userNameMatch?.groupValues?.get(1) ?: "anonymous"
            
            val messageMatch = "PRIVMSG #[^:]+:(.+)$".toRegex().find(raw)
            val message = messageMatch?.groupValues?.get(1) ?: ""
            
            return ChatMessage(
                id = UUID.randomUUID().toString(),
                username = username,
                message = message,
                color = color,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $raw", e)
            return null
        }
    }
}