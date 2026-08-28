package app.mpvnova.player

internal data class LoadedShieldVideoFormat(
    val codec: String,
    val pixelFormat: String,
    val unsupported: UnsupportedShieldVideo?,
)

internal fun MPVView.selectedVideoCodecForShieldCheck(): String =
    selectedVideoTrackString("codec").ifBlank {
        mpvGetPropertyString("video-codec") ?: ""
    }.trim().lowercase()

internal fun MPVView.hasSelectedVideoTrackForShieldCheck(): Boolean {
    val count = mpvGetPropertyInt("track-list/count") ?: return false
    return (0 until count).any { index ->
        mpvGetPropertyString("track-list/$index/type") == "video" &&
            mpvGetPropertyBoolean("track-list/$index/selected") == true
    }
}

internal fun MPVView.loadedShieldVideoFormat(): LoadedShieldVideoFormat? {
    val codec = selectedVideoCodecForShieldCheck()
    val pixelFormat = (mpvGetPropertyString("video-dec-params/pixelformat") ?: "")
        .trim()
        .lowercase()
    if (codec.isBlank() || pixelFormat.isBlank()) return null
    return LoadedShieldVideoFormat(
        codec = codec,
        pixelFormat = pixelFormat,
        unsupported = if (codec == HEVC_CODEC || codec == H265_CODEC) {
            pixelFormat.unsupportedShieldVideo()
        } else {
            null
        },
    )
}

private fun String.unsupportedShieldVideo(): UnsupportedShieldVideo? =
    shieldVideoBitDepth().let { bitDepth ->
        val chroma = unsupportedShieldChroma()
            ?: "4:2:0".takeIf { bitDepth > MAX_SHIELD_HEVC_BIT_DEPTH }
        chroma?.let { UnsupportedShieldVideo(bitDepth, it) }
    }

private fun String.unsupportedShieldChroma(): String? = when {
    contains(CHROMA_444_MARKER) -> "4:4:4"
    contains(CHROMA_422_MARKER) -> "4:2:2"
    else -> null
}

private fun String.shieldVideoBitDepth(): Int = when {
    contains("p16") || contains("16le") -> BIT_DEPTH_16
    contains("p14") || contains("14le") -> BIT_DEPTH_14
    contains("p12") || contains("12le") -> BIT_DEPTH_12
    contains("p10") || contains("10le") -> BIT_DEPTH_10
    else -> BIT_DEPTH_8
}

internal const val HEVC_CODEC = "hevc"
internal const val H265_CODEC = "h265"
private const val CHROMA_444_MARKER = "444"
private const val CHROMA_422_MARKER = "422"
private const val BIT_DEPTH_8 = 8
private const val BIT_DEPTH_10 = 10
private const val BIT_DEPTH_12 = 12
private const val BIT_DEPTH_14 = 14
private const val BIT_DEPTH_16 = 16
private const val MAX_SHIELD_HEVC_BIT_DEPTH = 10
