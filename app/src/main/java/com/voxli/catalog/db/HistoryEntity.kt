package com.voxli.catalog.db

import androidx.room.*

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: Long,
    val status: String = "reading",  // reading / listening / finished / dropped
    @ColumnInfo(name = "char_offset") val charOffset: Int = 0,
    val progress: Double = 0.0,  // 0.0–1.0
    @ColumnInfo(name = "playback_pos") val playbackPos: Long = 0,  // ms
    @ColumnInfo(name = "started_at") val startedAt: String = "",
    @ColumnInfo(name = "finished_at") val finishedAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
)
