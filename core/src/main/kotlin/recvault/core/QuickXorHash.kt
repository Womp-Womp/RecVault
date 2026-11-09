package recvault.core

import java.util.Base64

/**
 * Microsoft OneDrive QuickXorHash (160-bit), streaming.
 * Faithful port of Microsoft's reference C# implementation.
 * Verified against rclone's published test vectors (see QuickXorHashTest).
 */
class QuickXorHash {
    private val widthInBits = 160
    private val shift = 11
    private val data = LongArray((widthInBits - 1) / 64 + 1) // 3 cells: 64, 64, 32 bits
    private var shiftSoFar = 0
    private var lengthSoFar = 0L

    fun update(array: ByteArray, off: Int = 0, len: Int = array.size) {
        var currentShift = shiftSoFar
        var vectorArrayIndex = currentShift / 64
        var vectorOffset = currentShift % 64
        val iterations = minOf(len, widthInBits)

        for (i in 0 until iterations) {
            val isLastCell = vectorArrayIndex == data.size - 1
            val bitsInVectorCell = if (isLastCell) widthInBits % 64 else 64

            if (vectorOffset <= bitsInVectorCell - 8) {
                var j = off + i
                while (j < len + off) {
                    data[vectorArrayIndex] = data[vectorArrayIndex] xor ((array[j].toLong() and 0xFF) shl vectorOffset)
                    j += widthInBits
                }
            } else {
                val index1 = vectorArrayIndex
                val index2 = if (isLastCell) 0 else vectorArrayIndex + 1
                val low = bitsInVectorCell - vectorOffset
                var xoredByte = 0L
                var j = off + i
                while (j < len + off) {
                    xoredByte = xoredByte xor (array[j].toLong() and 0xFF)
                    j += widthInBits
                }
                data[index1] = data[index1] xor (xoredByte shl vectorOffset)
                data[index2] = data[index2] xor (xoredByte ushr low)
            }

            vectorOffset += shift
            while (vectorOffset >= bitsInVectorCell) {
                vectorArrayIndex = if (isLastCell) 0 else vectorArrayIndex + 1
                vectorOffset -= bitsInVectorCell
            }
        }
        currentShift = ((shiftSoFar + shift.toLong() * (len % widthInBits)) % widthInBits).toInt()
        shiftSoFar = currentShift
        lengthSoFar += len
    }

    fun digest(): ByteArray {
        val rgb = ByteArray((widthInBits - 1) / 8 + 1) // 20
        for (i in 0 until data.size - 1) putLE(data[i], rgb, i * 8, 8)
        putLE(data[data.size - 1], rgb, (data.size - 1) * 8, rgb.size - (data.size - 1) * 8)
        val lengthBytes = ByteArray(8).also { putLE(lengthSoFar, it, 0, 8) }
        for (i in 0 until 8) {
            val k = widthInBits / 8 - 8 + i
            rgb[k] = (rgb[k].toInt() xor lengthBytes[i].toInt()).toByte()
        }
        return rgb
    }

    fun digestBase64(): String = Base64.getEncoder().encodeToString(digest())

    private fun putLE(v: Long, out: ByteArray, at: Int, n: Int) {
        for (b in 0 until n) out[at + b] = ((v ushr (8 * b)) and 0xFF).toByte()
    }
}
