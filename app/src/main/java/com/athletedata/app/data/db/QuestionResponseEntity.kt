package com.athletedata.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.app.data.model.QuestionResponse

@Entity(
    tableName = "question_responses",
    foreignKeys = [
        ForeignKey(
            entity = QuestionDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("question_id"),
        Index(value = ["question_id", "date"], unique = true),
    ],
)
data class QuestionResponseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "question_id")
    val questionId: Long,
    val date: String,
    val value: String,
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,
)

fun QuestionResponseEntity.toModel() = QuestionResponse(
    id = id,
    questionId = questionId,
    date = date,
    value = value,
    recordedAt = recordedAt,
)
