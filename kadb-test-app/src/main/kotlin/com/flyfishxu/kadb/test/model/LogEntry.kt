package com.flyfishxu.kadb.test.model

data class LogEntry(
    val timestamp: String,
    val level: String,
    val message: String,
    val isError: Boolean = false
) 