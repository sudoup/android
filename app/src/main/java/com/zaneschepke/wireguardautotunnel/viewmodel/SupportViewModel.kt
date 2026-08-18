package com.zaneschepke.wireguardautotunnel.viewmodel

import androidx.lifecycle.ViewModel
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.AppUpdate
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.UpdateRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.ui.state.SupportUiState
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.StringValue
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.viewmodel.orbitContainer

class SupportViewModel(
    private val updateRepository: UpdateRepository,
    private val globalEffectRepository: GlobalEffectRepository,
) : OrbitContainerHost<SupportUiState, SupportUiState, Nothing>, ViewModel() {

    override val container = orbitContainer<SupportUiState, Nothing>(SupportUiState())

    fun checkForStandaloneUpdate(startDownloadIfAvailable: Boolean = false) = intent {
        if (!startDownloadIfAvailable) {
            postSideEffect(
                GlobalSideEffect.Snackbar(
                    StringValue.StringResource(R.string.checking_for_update),
                    ToastType.Info,
                )
            )
        }
        reduce { state.copy(isLoading = true, downloadProgress = 0f) }
        updateRepository
            .checkForUpdate(BuildConfig.VERSION_NAME)
            .onSuccess { update ->
                val sanitized = update.sanitized()
                if (sanitized == null) {
                    reduce { state.copy(isLoading = false, appUpdate = null) }
                    if (!startDownloadIfAvailable) {
                        postSideEffect(
                            GlobalSideEffect.Snackbar(
                                StringValue.StringResource(R.string.latest_installed),
                                ToastType.Info,
                            )
                        )
                    }
                } else {
                    reduce { state.copy(appUpdate = sanitized, isLoading = false) }
                    if (startDownloadIfAvailable) {
                        downloadAndInstall()
                    }
                }
            }
            .onFailure {
                reduce { state.copy(isLoading = false) }
                postSideEffect(
                    GlobalSideEffect.Snackbar(
                        StringValue.StringResource(R.string.update_check_failed),
                        ToastType.Error,
                    )
                )
            }
    }

    suspend fun postSideEffect(globalSideEffect: GlobalSideEffect) {
        globalEffectRepository.post(globalSideEffect)
    }

    private fun AppUpdate?.sanitized(): AppUpdate? {
        return this?.copy(releaseNotes = releaseNotes.substringBefore(CHANGELOG_START))
    }

    fun viewReleaseNotes() = intent {
        val version =
            if (BuildConfig.VERSION_NAME.contains("nightly")) {
                "nightly"
            } else {
                state.appUpdate?.version?.removePrefix("v")?.trim().orEmpty()
            }
        val url = "${Constants.BASE_RELEASE_URL}$version".trim()
        postSideEffect(GlobalSideEffect.LaunchUrl(url))
    }

    fun dismissUpdate() = intent {
        reduce { state.copy(appUpdate = null, isLoading = false, downloadProgress = 0f) }
    }

    fun downloadAndInstall() = intent {
        val update = state.appUpdate
        val apkUrl = update?.apkUrl
        val apkFileName = update?.apkFileName
        if (update == null || apkUrl.isNullOrBlank() || apkFileName.isNullOrBlank()) {
            postSideEffect(
                GlobalSideEffect.Snackbar(
                    StringValue.StringResource(R.string.update_download_failed),
                    ToastType.Error,
                )
            )
            return@intent
        }

        reduce { state.copy(isLoading = true, downloadProgress = 0f) }
        updateRepository
            .downloadApk(apkUrl, apkFileName) { progress ->
                intent { reduce { state.copy(downloadProgress = progress) } }
            }
            .onSuccess { file ->
                reduce { state.copy(isLoading = false, downloadProgress = 1f) }
                postSideEffect(GlobalSideEffect.InstallApk(file))
            }
            .onFailure {
                reduce { state.copy(isLoading = false) }
                postSideEffect(
                    GlobalSideEffect.Snackbar(
                        StringValue.StringResource(R.string.update_download_failed),
                        ToastType.Error,
                    )
                )
            }
    }

    companion object {
        private const val CHANGELOG_START =
            "SHA-256 fingerprint for the 4096-bit signing certificate:"
    }
}
