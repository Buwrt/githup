package com.githubclient.app.ui.navigation

import androidx.lifecycle.ViewModel
import com.githubclient.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@HiltViewModel
class NavStartViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {
    val isLoggedIn = MutableStateFlow(authRepository.isLoggedIn())
}
