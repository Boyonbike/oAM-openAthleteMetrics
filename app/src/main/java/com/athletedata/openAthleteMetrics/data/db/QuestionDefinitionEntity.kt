package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.QuestionType

@Entity(tableName = "question_definitions")
data class QuestionDefinitionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: String,
    val category: String,
    @ColumnInfo(name = "is_visible")
    val isVisible: Boolean = true,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "is_starred")
    val isStarred: Boolean = false,
)

fun QuestionDefinitionEntity.toModel() = QuestionDefinition(
    id = id,
    name = name,
    type = QuestionType.valueOf(type),
    category = QuestionCategory.valueOf(category),
    isVisible = isVisible,
    sortOrder = sortOrder,
    isStarred = isStarred,
)

fun QuestionDefinition.toEntity() = QuestionDefinitionEntity(
    id = id,
    name = name,
    type = type.name,
    category = category.name,
    isVisible = isVisible,
    sortOrder = sortOrder,
    isStarred = isStarred,
)
