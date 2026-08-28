package app.mpvnova.player

internal class HevcBitReader(private val data: ByteArray) {
    private var bitOffset = 0

    fun read(count: Int): Int {
        require(count in 0..MAX_READ_BITS && bitOffset + count <= data.size * BITS_PER_BYTE)
        var result = 0
        repeat(count) {
            val byte = data[bitOffset / BITS_PER_BYTE].toInt() and BYTE_MASK
            result = (result shl 1) or ((byte ushr (LAST_BIT_INDEX - bitOffset % BITS_PER_BYTE)) and 1)
            bitOffset++
        }
        return result
    }

    fun skip(count: Int) {
        require(count >= 0 && bitOffset + count <= data.size * BITS_PER_BYTE)
        bitOffset += count
    }

    fun readUnsignedExpGolomb(): Int {
        var leadingZeros = 0
        while (read(1) == 0) {
            leadingZeros++
            require(leadingZeros < MAX_READ_BITS)
        }
        return if (leadingZeros == 0) 0 else (1 shl leadingZeros) - 1 + read(leadingZeros)
    }

    private companion object {
        const val MAX_READ_BITS = 31
        const val BITS_PER_BYTE = 8
        const val LAST_BIT_INDEX = 7
        const val BYTE_MASK = 0xff
    }
}
