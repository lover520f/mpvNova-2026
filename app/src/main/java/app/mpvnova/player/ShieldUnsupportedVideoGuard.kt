package app.mpvnova.player

import android.media.MediaExtractor
import android.net.Uri
import android.util.Log
import android.view.View
import java.io.File
import java.io.IOException

internal fun MPVActivity.showUnsupportedShieldVideoWarningIfNeeded(
    filepath: String,
    onContinue: () -> Unit,
): Boolean {
    val unsupported = if (isNvidiaShieldDevice()) probeUnsupportedShieldVideo(filepath) else null
    unsupported?.let {
        showUnsupportedShieldVideoWarning(it) {
            bypassUnsupportedShieldCheckForNextLoad = true
            onContinue()
        }
    }
    return unsupported != null
}

internal fun MPVActivity.prepareLoadedShieldCompatibilityCheck(filepath: String) {
    eventUiHandler.removeCallbacks(shieldCompatibilityCheckTimeout)
    val bypassCheck = bypassUnsupportedShieldCheckForNextLoad
    if (bypassCheck) {
        bypassUnsupportedShieldCheckForNextLoad = false
    }
    shieldCompatibilityCheckRequested = !bypassCheck &&
        isNvidiaShieldDevice() &&
        isNetworkStreamPath(filepath)
    shieldCompatibilityCheckPending = false
    if (shieldCompatibilityCheckRequested) {
        shieldCompatibilityResumeAfterCheck = player.paused != true
        player.paused = true
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check armed for network video")
    } else {
        shieldCompatibilityResumeAfterCheck = false
    }
}

internal fun MPVActivity.beginLoadedShieldCompatibilityCheck() {
    shieldCompatibilityCheckPending = shieldCompatibilityCheckRequested
    shieldCompatibilityCheckRequested = false
    if (shieldCompatibilityCheckPending) {
        player.paused = true
        eventUiHandler.removeCallbacks(shieldCompatibilityCheckTimeout)
        eventUiHandler.postDelayed(
            shieldCompatibilityCheckTimeout,
            SHIELD_COMPATIBILITY_CHECK_TIMEOUT_MS,
        )
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check waiting for decoded video parameters")
    }
}

internal fun MPVActivity.finishLoadedShieldCompatibilityCheckForNonHevc() {
    if (!shieldCompatibilityCheckPending) return
    if (!player.hasSelectedVideoTrackForShieldCheck()) {
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check passed without a selected video track")
        completeLoadedShieldCompatibilityCheck()
        return
    }
    val codec = player.selectedVideoCodecForShieldCheck()
    if (codec.isNotBlank() && codec != HEVC_CODEC && codec != H265_CODEC) {
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check passed without HEVC decode: codec=$codec")
        completeLoadedShieldCompatibilityCheck()
    }
}

internal fun MPVActivity.handleLoadedShieldVideoReconfig() {
    if (shieldCompatibilityCheckPending) {
        val format = player.loadedShieldVideoFormat()
        when {
            format == null -> {
                Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check is still waiting for video-dec-params")
            }
            format.unsupported == null -> {
                Log.v(
                    MPV_ACTIVITY_TAG,
                    "Shield compatibility check: codec=${format.codec} " +
                        "pixelFormat=${format.pixelFormat}",
                )
                completeLoadedShieldCompatibilityCheck()
            }
            else -> {
                Log.v(
                    MPV_ACTIVITY_TAG,
                    "Shield compatibility check blocked: codec=${format.codec} " +
                        "pixelFormat=${format.pixelFormat}",
                )
                shieldCompatibilityCheckPending = false
                showUnsupportedShieldVideoWarning(format.unsupported) {
                    completeLoadedShieldCompatibilityCheck()
                }
            }
        }
    }
}

internal fun MPVActivity.completeLoadedShieldCompatibilityCheck() {
    eventUiHandler.removeCallbacks(shieldCompatibilityCheckTimeout)
    shieldCompatibilityCheckRequested = false
    shieldCompatibilityCheckPending = false
    val shouldResume = shieldCompatibilityResumeAfterCheck
    shieldCompatibilityResumeAfterCheck = false
    if (shouldResume) player.paused = false
}

private fun MPVActivity.showUnsupportedShieldVideoWarning(
    unsupported: UnsupportedShieldVideo,
    onContinue: () -> Unit,
) {
    eventUiHandler.removeCallbacks(shieldCompatibilityCheckTimeout)
    unsupportedShieldContinueAction = onContinue
    streamOpenLoading = false
    streamCacheLoading = false
    eventUiHandler.post {
        hideStreamLoadingOverlayImmediately()
        binding.unsupportedShieldVideoFormat.text = getString(
            R.string.shield_unsupported_video_format,
            unsupported.bitDepth,
            unsupported.chromaName,
        )
        binding.unsupportedShieldVideoOverlay.visibility = View.VISIBLE
        binding.unsupportedShieldVideoOverlay.bringToFront()
        binding.unsupportedShieldBackBtn.requestFocus()
    }
}

internal fun MPVActivity.continueUnsupportedShieldPlayback() {
    val continueAction = unsupportedShieldContinueAction ?: return
    unsupportedShieldContinueAction = null
    binding.unsupportedShieldVideoOverlay.visibility = View.GONE
    continueAction()
}

internal fun MPVActivity.exitUnsupportedShieldWarning() {
    eventUiHandler.removeCallbacks(shieldCompatibilityCheckTimeout)
    unsupportedShieldContinueAction = null
    shieldCompatibilityCheckRequested = false
    shieldCompatibilityCheckPending = false
    shieldCompatibilityResumeAfterCheck = false
    finishWithResult(RESULT_CANCELED)
}

private fun MPVActivity.probeUnsupportedShieldVideo(filepath: String): UnsupportedShieldVideo? {
    val extractor = MediaExtractor()
    return try {
        if (!extractor.setLocalDataSource(this, filepath)) return null
        (0 until extractor.trackCount)
            .asSequence()
            .map(extractor::getTrackFormat)
            .firstNotNullOfOrNull(::unsupportedHevcFormat)
    } catch (error: IOException) {
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check could not inspect the video", error)
        null
    } catch (error: IllegalArgumentException) {
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check could not inspect the video", error)
        null
    } catch (error: IllegalStateException) {
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check could not inspect the video", error)
        null
    } catch (error: SecurityException) {
        Log.v(MPV_ACTIVITY_TAG, "Shield compatibility check could not inspect the video", error)
        null
    } finally {
        extractor.release()
    }
}

private fun MediaExtractor.setLocalDataSource(activity: MPVActivity, filepath: String): Boolean {
    val localFile = filepath.toCanonicalLocalFile()?.takeIf(File::canRead)
    val uri = runCatching { Uri.parse(filepath) }.getOrNull()
    return when {
        localFile != null -> {
            setDataSource(localFile.absolutePath)
            true
        }
        uri?.scheme == "content" || uri?.scheme == "file" -> {
            setDataSource(activity, uri, emptyMap())
            true
        }
        else -> false
    }
}

internal const val SHIELD_COMPATIBILITY_CHECK_TIMEOUT_MS = 10_000L
