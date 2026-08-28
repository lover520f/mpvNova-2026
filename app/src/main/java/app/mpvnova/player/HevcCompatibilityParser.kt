package app.mpvnova.player

import android.media.MediaFormat

private data class HevcConfiguration(
    val chromaFormat: Int,
    val bitDepth: Int,
)

internal fun unsupportedHevcFormat(format: MediaFormat): UnsupportedShieldVideo? {
    val configuration = if (format.getString(MediaFormat.KEY_MIME) == HEVC_MIME) {
        format.csdBytes()?.readHevcConfiguration()
    } else {
        null
    }
    return configuration
        ?.takeIf { it.chromaFormat != HEVC_CHROMA_420 || it.bitDepth > HEVC_MAX_SHIELD_BIT_DEPTH }
        ?.let { UnsupportedShieldVideo(it.bitDepth, it.chromaFormat.chromaName()) }
}

private fun MediaFormat.csdBytes(): ByteArray? {
    val buffer = if (containsKey(CSD_0)) runCatching { getByteBuffer(CSD_0) }.getOrNull() else null
    return buffer?.duplicate()?.run {
        position(0)
        ByteArray(remaining()).also(::get)
    }
}

private fun ByteArray.readHevcConfiguration(): HevcConfiguration? =
    if (size >= HEVC_CONFIG_HEADER_SIZE && this[0].toInt() == HEVC_CONFIG_VERSION) {
        HevcConfiguration(
            chromaFormat = this[HEVC_CONFIG_CHROMA_OFFSET].toInt() and HEVC_CHROMA_MASK,
            bitDepth = HEVC_BASE_BIT_DEPTH +
                (this[HEVC_CONFIG_LUMA_DEPTH_OFFSET].toInt() and HEVC_DEPTH_MASK),
        )
    } else {
        readAnnexBHevcConfiguration()
    }

private fun ByteArray.readAnnexBHevcConfiguration(): HevcConfiguration? {
    var configuration: HevcConfiguration? = null
    var offset = findHevcStartCode(0)
    while (offset >= 0 && configuration == null) {
        val startCodeLength = if (this[offset + START_CODE_TYPE_OFFSET].toInt() == START_CODE_SHORT_MARKER) {
            START_CODE_SHORT_LENGTH
        } else {
            START_CODE_LONG_LENGTH
        }
        val nalStart = offset + startCodeLength
        val nextStart = findHevcStartCode(nalStart)
        val nalEnd = if (nextStart >= 0) nextStart else size
        val hasNalHeader = nalStart + HEVC_NAL_HEADER_SIZE <= nalEnd
        val nalType = if (hasNalHeader) {
            (this[nalStart].toInt() ushr HEVC_NAL_TYPE_SHIFT) and HEVC_NAL_TYPE_MASK
        } else {
            -1
        }
        if (nalType == HEVC_SPS_NAL_TYPE) {
            configuration = copyOfRange(nalStart + HEVC_NAL_HEADER_SIZE, nalEnd)
                .withoutHevcEmulationPreventionBytes()
                .readHevcSpsConfiguration()
        }
        offset = nextStart
    }
    return configuration
}

private fun ByteArray.readHevcSpsConfiguration(): HevcConfiguration? = runCatching {
    val bits = HevcBitReader(this)
    bits.skip(HEVC_SPS_VIDEO_PARAMETER_SET_BITS)
    val maxSubLayers = bits.read(HEVC_SPS_MAX_SUB_LAYERS_BITS)
    bits.skip(HEVC_SPS_TEMPORAL_NESTING_BITS)
    bits.skipProfileTierLevel(maxSubLayers)
    bits.readUnsignedExpGolomb()
    val chromaFormat = bits.readUnsignedExpGolomb()
    if (chromaFormat == HEVC_CHROMA_444) bits.skip(HEVC_SPS_SEPARATE_COLOR_PLANE_BITS)
    bits.readUnsignedExpGolomb()
    bits.readUnsignedExpGolomb()
    if (bits.read(HEVC_SPS_CONFORMANCE_WINDOW_FLAG_BITS) == 1) {
        repeat(HEVC_SPS_CONFORMANCE_WINDOW_FIELDS) { bits.readUnsignedExpGolomb() }
    }
    HevcConfiguration(chromaFormat, HEVC_BASE_BIT_DEPTH + bits.readUnsignedExpGolomb())
}.getOrNull()

private fun HevcBitReader.skipProfileTierLevel(maxSubLayers: Int) {
    skip(HEVC_GENERAL_PROFILE_TIER_LEVEL_BITS)
    val profilePresent = BooleanArray(maxSubLayers)
    val levelPresent = BooleanArray(maxSubLayers)
    for (index in 0 until maxSubLayers) {
        profilePresent[index] = read(FLAG_BIT_COUNT) == 1
        levelPresent[index] = read(FLAG_BIT_COUNT) == 1
    }
    if (maxSubLayers > 0) {
        repeat(HEVC_MAX_SUB_LAYERS - maxSubLayers) { skip(HEVC_RESERVED_SUB_LAYER_BITS) }
    }
    for (index in 0 until maxSubLayers) {
        if (profilePresent[index]) skip(HEVC_SUB_LAYER_PROFILE_BITS)
        if (levelPresent[index]) skip(HEVC_SUB_LAYER_LEVEL_BITS)
    }
}

private fun ByteArray.findHevcStartCode(fromIndex: Int): Int {
    var match = -1
    var index = fromIndex
    while (index < size - START_CODE_MAX_LOOKAHEAD && match < 0) {
        val firstTwoZero = this[index].toInt() == 0 && this[index + 1].toInt() == 0
        val shortCode = firstTwoZero && this[index + START_CODE_TYPE_OFFSET].toInt() == START_CODE_SHORT_MARKER
        val longCode = firstTwoZero &&
            this[index + START_CODE_TYPE_OFFSET].toInt() == 0 &&
            this[index + START_CODE_LONG_MARKER_OFFSET].toInt() == START_CODE_SHORT_MARKER
        if (shortCode || longCode) match = index
        index++
    }
    return match
}

private fun ByteArray.withoutHevcEmulationPreventionBytes(): ByteArray {
    val output = ByteArray(size)
    var outputIndex = 0
    var zeroCount = 0
    for (value in this) {
        val unsigned = value.toInt() and BYTE_MASK
        if (zeroCount >= EMULATION_PREVENTION_ZERO_COUNT && unsigned == EMULATION_PREVENTION_BYTE) {
            zeroCount = 0
        } else {
            output[outputIndex++] = value
            zeroCount = if (unsigned == 0) zeroCount + 1 else 0
        }
    }
    return output.copyOf(outputIndex)
}

private fun Int.chromaName(): String = when (this) {
    HEVC_CHROMA_MONOCHROME -> "monochrome"
    HEVC_CHROMA_420 -> "4:2:0"
    HEVC_CHROMA_422 -> "4:2:2"
    HEVC_CHROMA_444 -> "4:4:4"
    else -> "unsupported chroma"
}

private const val HEVC_MIME = "video/hevc"
private const val CSD_0 = "csd-0"
private const val HEVC_CONFIG_VERSION = 1
private const val HEVC_CONFIG_HEADER_SIZE = 19
private const val HEVC_CONFIG_CHROMA_OFFSET = 16
private const val HEVC_CONFIG_LUMA_DEPTH_OFFSET = 17
private const val HEVC_CHROMA_MASK = 0x03
private const val HEVC_DEPTH_MASK = 0x07
private const val HEVC_BASE_BIT_DEPTH = 8
private const val HEVC_MAX_SHIELD_BIT_DEPTH = 10
private const val HEVC_CHROMA_MONOCHROME = 0
private const val HEVC_CHROMA_420 = 1
private const val HEVC_CHROMA_422 = 2
private const val HEVC_CHROMA_444 = 3
private const val HEVC_NAL_HEADER_SIZE = 2
private const val HEVC_NAL_TYPE_SHIFT = 1
private const val HEVC_NAL_TYPE_MASK = 0x3f
private const val HEVC_SPS_NAL_TYPE = 33
private const val HEVC_SPS_VIDEO_PARAMETER_SET_BITS = 4
private const val HEVC_SPS_MAX_SUB_LAYERS_BITS = 3
private const val HEVC_SPS_TEMPORAL_NESTING_BITS = 1
private const val HEVC_SPS_SEPARATE_COLOR_PLANE_BITS = 1
private const val HEVC_SPS_CONFORMANCE_WINDOW_FLAG_BITS = 1
private const val HEVC_SPS_CONFORMANCE_WINDOW_FIELDS = 4
private const val HEVC_GENERAL_PROFILE_TIER_LEVEL_BITS = 96
private const val HEVC_SUB_LAYER_PROFILE_BITS = 88
private const val HEVC_SUB_LAYER_LEVEL_BITS = 8
private const val HEVC_MAX_SUB_LAYERS = 8
private const val HEVC_RESERVED_SUB_LAYER_BITS = 2
private const val FLAG_BIT_COUNT = 1
private const val START_CODE_TYPE_OFFSET = 2
private const val START_CODE_LONG_MARKER_OFFSET = 3
private const val START_CODE_SHORT_MARKER = 1
private const val START_CODE_SHORT_LENGTH = 3
private const val START_CODE_LONG_LENGTH = 4
private const val START_CODE_MAX_LOOKAHEAD = 3
private const val EMULATION_PREVENTION_ZERO_COUNT = 2
private const val EMULATION_PREVENTION_BYTE = 3
private const val BYTE_MASK = 0xff
