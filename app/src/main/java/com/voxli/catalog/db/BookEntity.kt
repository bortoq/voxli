package com.voxli.catalog.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: Long,
    val title: String = "",
    val author: String = "",
    val annotation: String = "",
    val genre: String = "",
    val series: String = "",
    @ColumnInfo(name = "series_num") val seriesNum: Int = 0,
    val lang: String = "",
    val rating: Double = 0.0,
    @ColumnInfo(name = "votes_count") val votesCount: Int = 0,
    @ColumnInfo(name = "has_fb2") val hasFb2: Boolean = false,
    @ColumnInfo(name = "has_epub") val hasEpub: Boolean = false,
    @ColumnInfo(name = "has_audio") val hasAudio: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String = "",
)
