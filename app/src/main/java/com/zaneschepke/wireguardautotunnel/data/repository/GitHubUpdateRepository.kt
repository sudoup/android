package com.zaneschepke.wireguardautotunnel.data.repository

import android.content.Context
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.data.mapper.GitHubReleaseMapper
import com.zaneschepke.wireguardautotunnel.data.network.GitHubApi
import com.zaneschepke.wireguardautotunnel.domain.model.AppUpdate
import com.zaneschepke.wireguardautotunnel.domain.repository.UpdateRepository
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.NumberUtils
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber

class GitHubUpdateRepository(
    private val gitHubApi: GitHubApi,
    private val httpClient: HttpClient,
    private val githubOwner: String,
    private val githubRepo: String,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : UpdateRepository {

    override suspend fun checkForUpdate(currentVersion: String): Result<AppUpdate?> =
        withContext(ioDispatcher) {
            Timber.i("Checking for update (current=$currentVersion)")
            val isNightly = BuildConfig.VERSION_NAME.contains("nightly", ignoreCase = true)
            val release =
                if (isNightly) {
                    gitHubApi.getNightlyRelease(githubOwner, githubRepo).onFailure(Timber::e)
                } else {
                    gitHubApi.getLatestRelease(githubOwner, githubRepo).onFailure(Timber::e)
                }
            release.map { release ->
                val universalApkAsset =
                    release.assets.find { asset ->
                        val prefix = "wgtunnel-${Constants.STANDALONE_FLAVOR}-v"
                        val apkSuffix = ".apk"
                        asset.name.startsWith(prefix) &&
                            asset.name.endsWith(apkSuffix) &&
                            !asset.name.endsWith("-arm64$apkSuffix") &&
                            !asset.name.endsWith("-armv7$apkSuffix") &&
                            !asset.name.endsWith("-x86$apkSuffix") &&
                            !asset.name.endsWith("-x64$apkSuffix")
                    }
                val newVersion =
                    universalApkAsset
                        ?.name
                        ?.removePrefix("wgtunnel-${Constants.STANDALONE_FLAVOR}-v")
                        ?.removeSuffix(".apk") ?: return@map null

                Timber.i("Latest version: $newVersion, current version: $currentVersion")
                val updateAvailable =
                    if (isNightly) {
                        newVersion != currentVersion
                    } else {
                        NumberUtils.compareVersions(newVersion, currentVersion) > 0
                    }

                if (updateAvailable) {
                    GitHubReleaseMapper.toAppUpdate(
                        release.copy(assets = listOf(universalApkAsset)),
                        newVersion,
                    )
                } else {
                    null
                }
            }
        }

    override suspend fun downloadApk(
        apkUrl: String,
        fileName: String,
        onProgress: suspend (Float) -> Unit,
    ): Result<File> =
        withContext(ioDispatcher) {
            try {
                val downloadDir =
                    context.getExternalFilesDir(null)
                        ?: throw IOException("External files directory unavailable")

                // Remove previous APKs so installers don't pick a stale file
                downloadDir.listFiles()?.forEach { file ->
                    if (file.extension.equals("apk", ignoreCase = true)) file.delete()
                }

                val apkFile = File(downloadDir, fileName)
                if (apkFile.exists()) apkFile.delete()

                httpClient.prepareGet(apkUrl).execute { response: HttpResponse ->
                    if (!response.status.isSuccess()) {
                        throw IOException("Download failed with HTTP ${response.status}")
                    }

                    val channel: ByteReadChannel = response.bodyAsChannel()
                    val totalBytes: Long = response.contentLength() ?: -1L
                    var bytesCopied = 0L

                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer)
                            if (bytesRead <= 0) break
                            output.write(buffer, 0, bytesRead)
                            bytesCopied += bytesRead

                            if (totalBytes > 0) {
                                onProgress((bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f))
                            } else {
                                // Unknown length, keep UI in loading state without a
                                // stuck bar by reporting a some progress.
                                onProgress((bytesCopied % (5L * 1024 * 1024)) / (5f * 1024 * 1024))
                            }
                        }
                        output.flush()
                    }

                    if (bytesCopied <= 0L || !apkFile.exists() || apkFile.length() <= 0L) {
                        apkFile.delete()
                        throw IOException("Downloaded APK is empty")
                    }

                    onProgress(1f)
                    Timber.i("Downloaded APK ${apkFile.name} ($bytesCopied bytes)")
                    Result.success(apkFile)
                }
            } catch (e: Exception) {
                Timber.e(e, "APK download failed")
                Result.failure(e)
            }
        }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
