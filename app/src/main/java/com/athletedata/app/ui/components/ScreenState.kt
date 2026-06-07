package com.athletedata.app.ui.components

sealed class ScreenState<out T> {
    data object Loading : ScreenState<Nothing>()
    data object Empty : ScreenState<Nothing>()
    data class Success<out T>(val data: T) : ScreenState<T>()
    data class Error(val message: String) : ScreenState<Nothing>()
}
