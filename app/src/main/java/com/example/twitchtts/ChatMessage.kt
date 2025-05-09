package com.example.twitchtts

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val color: String = "#FFFFFF" // Default color for usernames
)