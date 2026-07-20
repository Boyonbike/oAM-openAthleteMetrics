package com.athletedata.openAthleteMetrics.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.UserProfile
import com.athletedata.openAthleteMetrics.data.repository.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val repo: UserProfileRepository,
) : ViewModel() {

    val profile: StateFlow<UserProfile?> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updateProfile(transform: (UserProfile) -> UserProfile) {
        viewModelScope.launch {
            try {
                val current = repo.get() ?: UserProfile()
                repo.upsert(transform(current))
            } catch (e: Exception) {
                Timber.e(e, "Failed to update profile")
            }
        }
    }
}
