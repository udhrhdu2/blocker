/*
 * Copyright 2025 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.merxury.blocker.feature.generalrules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.merxury.blocker.core.data.respository.app.AppRepository
import com.merxury.blocker.core.data.respository.generalrule.GeneralRuleRepository
import com.merxury.blocker.core.data.respository.userdata.AppPropertiesRepository
import com.merxury.blocker.core.dispatchers.BlockerDispatchers.IO
import com.merxury.blocker.core.dispatchers.Dispatcher
import com.merxury.blocker.core.domain.InitializeRuleStorageUseCase
import com.merxury.blocker.core.domain.SearchGeneralRuleUseCase
import com.merxury.blocker.core.domain.UpdateRuleMatchedAppUseCase
import com.merxury.blocker.core.domain.model.InitializeState
import com.merxury.blocker.core.model.data.GeneralRule
import com.merxury.blocker.core.result.Result
import com.merxury.blocker.core.ui.data.UiMessage
import com.merxury.blocker.core.ui.data.toErrorMessage
import com.merxury.blocker.feature.generalrules.GeneralRuleUiState.Error
import com.merxury.blocker.feature.generalrules.GeneralRuleUiState.Loading
import com.merxury.blocker.feature.generalrules.GeneralRuleUiState.Success
import com.merxury.blocker.feature.generalrules.navigation.GeneralRuleRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class GeneralRulesViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val appPropertiesRepository: AppPropertiesRepository,
    private val generalRuleRepository: GeneralRuleRepository,
    private val initGeneralRuleUseCase: InitializeRuleStorageUseCase,
    private val searchRule: SearchGeneralRuleUseCase,
    private val updateRule: UpdateRuleMatchedAppUseCase,
    private val savedStateHandle: SavedStateHandle,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow<GeneralRuleUiState>(Loading)
    val uiState: StateFlow<GeneralRuleUiState> = _uiState.asStateFlow()
    private val _errorState = MutableStateFlow<UiMessage?>(null)
    val errorState = _errorState.asStateFlow()
    private val loadExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Error occurred while loading general rules")
        _errorState.value = throwable.toErrorMessage()
        _uiState.value = (Error(throwable.toErrorMessage()))
    }
    private var loadRuleJob: Job? = null
    private val generalRuleRoute: GeneralRuleRoute = savedStateHandle.toRoute()

    // Key used to save and retrieve the currently selected topic id from saved state.
    private val selectedRuleIdKey = "selectedRuleIdKey"
    private val selectedRuleId: StateFlow<String?> = savedStateHandle.getStateFlow(
        key = selectedRuleIdKey,
        initialValue = generalRuleRoute.initialRuleId,
    )

    init {
        loadData()
    }

    fun dismissAlert() = viewModelScope.launch {
        _errorState.emit(null)
    }

    fun onRuleClick(ruleId: String?) {
        savedStateHandle[selectedRuleIdKey] = ruleId
        loadSelectedRule()
    }

    private fun loadSelectedRule() {
        _uiState.update {
            if (it is Success) {
                it.copy(selectedRuleId = selectedRuleId.value)
            } else {
                it
            }
        }
    }

    private fun loadData() {
        loadRuleJob?.cancel()
        loadRuleJob = viewModelScope.launch(loadExceptionHandler) {
            if (!shouldRefreshList()) {
                Timber.d("No need to refresh the list")
                showGeneralRuleList(skipLoading = true)
                return@launch
            }
            _uiState.emit(Loading)
            initGeneralRuleUseCase()
                .catch { _uiState.emit(Error(it.toErrorMessage())) }
                .takeWhile { state -> state != InitializeState.Done }
                .collect { state ->
                    Timber.d("Initialize general rule: $state")
                }
            Timber.v("Get general rules from local storage")
            updateGeneralRule()
            showGeneralRuleList()
        }
    }

    private suspend fun showGeneralRuleList(skipLoading: Boolean = false) {
        searchRule()
            .catch { _uiState.emit(Error(it.toErrorMessage())) }
            .distinctUntilChanged()
            .collect { rules ->
                val matchedRules = rules.filter { it.matchedAppCount > 0 }
                val unmatchedRules = rules.filter { it.matchedAppCount == 0 }
                _uiState.update { state ->
                    val newState = if (state is Success) {
                        state.copy(matchedRules = matchedRules, unmatchedRules = unmatchedRules)
                    } else {
                        Success(matchedRules = matchedRules, unmatchedRules = unmatchedRules)
                    }
                    if (!skipLoading) {
                        newState
                    } else {
                        newState.copy(matchProgress = 1F)
                    }
                }
            }
    }

    private fun updateGeneralRule() = viewModelScope.launch {
        // Get general from network first
        generalRuleRepository.updateGeneralRule()
            .flowOn(ioDispatcher)
            .collect { result ->
                when (result) {
                    is Result.Success -> updateMatchedAppInfo()
                    is Result.Error -> _errorState.emit(result.exception.toErrorMessage())
                    else -> {
                        // Do nothing
                    }
                }
            }
    }

    private fun updateMatchedAppInfo() = viewModelScope.launch {
        // Get matched app info from local
        val ruleList = generalRuleRepository.getGeneralRules()
            .first()
        if (ruleList.isEmpty()) {
            return@launch
        }
        var matchedApps = 0F
        ruleList.forEach { rule ->
            // No need to handle result
            updateRule(rule).firstOrNull()
            matchedApps += 1
            _uiState.update {
                if (it is Success) {
                    it.copy(matchProgress = matchedApps / ruleList.size)
                } else {
                    it
                }
            }
        }
        saveHash()
    }

    private suspend fun shouldRefreshList(): Boolean {
        val appProperties = appPropertiesRepository.appProperties.first()
        val lastOpenedAppListHash = appProperties.lastOpenedAppListHash
        val lastOpenedRuleHash = appProperties.lastOpenedRuleHash
        if (lastOpenedAppListHash.isEmpty() || lastOpenedRuleHash.isEmpty()) {
            Timber.d("User opened this screen for the first time, should refresh the list")
            return true
        }
        val currentAppListHash = getCurrentAppListHash()
        val currentRuleHash = getCurrentRuleHash()
        val appListChanged = currentAppListHash != lastOpenedAppListHash
        val ruleChanged = currentRuleHash != lastOpenedRuleHash
        val shouldReloadList = appListChanged || ruleChanged
        Timber.d(
            "App list changed: $appListChanged, rule changed: $ruleChanged, " +
                "should reload the list: $shouldReloadList",
        )
        return shouldReloadList
    }

    private suspend fun getCurrentAppListHash(): String = appRepository.getApplicationList()
        .first()
        .hashCode()
        .toString()

    private suspend fun getCurrentRuleHash(): String = generalRuleRepository.getRuleHash()
        .first()

    private suspend fun saveHash() {
        val appListHash = getCurrentAppListHash()
        val ruleHash = getCurrentRuleHash()
        Timber.d("Save app list hash: $appListHash, rule hash: $ruleHash")
        appPropertiesRepository.updateLastOpenedAppListHash(appListHash)
        appPropertiesRepository.updateLastOpenedRuleHash(ruleHash)
    }
}

sealed interface GeneralRuleUiState {
    data object Loading : GeneralRuleUiState
    data class Success(
        val matchedRules: List<GeneralRule>,
        val unmatchedRules: List<GeneralRule>,
        val matchProgress: Float = 0F,
        val selectedRuleId: String? = null,
    ) : GeneralRuleUiState

    data class Error(val error: UiMessage) : GeneralRuleUiState
}
