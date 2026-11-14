package com.example.ultrahdr.processing

import java.io.File
import java.io.RandomAccessFile

/**
 * Android's EXIF writer stores exposure time, f-number and digital zoom to four decimal places,
 * so 1/1593 s comes out as 1/1667 s and anything faster than 1/10000 s as zero. After the metadata
 * is copied, the exact fractions from the original are written back over those entries. They keep
 * their size, so nothing else in the file moves.
 */
internal object ExifRationals {

    private const val EXIF_IFD_POINTER = 0x8769
    private val TAGS = intArrayOf(
        0x829A, // ExposureTime
        0x829D, // FNumber
        0xA404  // DigitalZoomRatio
    )
    private const val RATIONAL = 5

    /** Where one rational value sits in a file: the byte position of its numerator. */
    private class Slot(val position: Int, val bigEndian: Boolean)

    /** Copies the exact values from [source] into [target]. Returns how many were changed. */
    fun copyExact(source: ByteArray, target: File): Int = try {
        val wanted = slots(source).mapValues { (_, slot) ->
            readU32(source, slot.position, slot.bigEndian) to readU32(source, slot.position + 4, slot.bigEndian)
        }
        if (wanted.isEmpty()) {
            0
        } else {
            val head = readHead(target)
            var changed = 0
            RandomAccessFile(target, "rw").use { file ->
                for ((tag, slot) in slots(head)) {
                    val (numerator, denominator) = wanted[tag] ?: continue
                    if (denominator == 0L) continue
                    val current = readU32(head, slot.position, slot.bigEndian) to
                        readU32(head, slot.position + 4, slot.bigEndian)
                    if (current == numerator to denominator) continue
                    file.seek(slot.position.toLong())
                    file.write(u32(numerator, slot.bigEndian))
                    file.write(u32(denominator, slot.bigEndian))
                    changed++
                }
            }
            changed
        }
    } catch (_: Throwable) {
        0
    }

    /** The EXIF block sits in the first segments; 1 MB covers any real file. */
    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        input.readNBytes(minOf(file.length(), 1L shl 20).toInt())
    }

    /** The single-value rational entries of [TAGS] in the EXIF block of a JPEG. */
    private fun slots(jpeg: ByteArray): Map<Int, Slot> {
        val tiff = tiffStart(jpeg) ?: return emptyMap()
        val bigEndian = when {
            jpeg[tiff] == 'M'.code.toByte() && jpeg[tiff + 1] == 'M'.code.toByte() -> true
            jpeg[tiff] == 'I'.code.toByte() && jpeg[tiff + 1] == 'I'.code.toByte() -> false
            else -> return emptyMap()
        }
        fun u16(at: Int) = readU16(jpeg, at, bigEndian)
        fun u32(at: Int) = readU32(jpeg, at, bigEndian)

        val ifd0 = tiff + u32(tiff + 4).toInt()
        val exifIfd = entries(jpeg, ifd0, bigEndian)
            .firstOrNull { u16(it) == EXIF_IFD_POINTER }
            ?.let { tiff + u32(it + 8).toInt() } ?: return emptyMap()
        val found = mutableMapOf<Int, Slot>()
        for (entry in entries(jpeg, exifIfd, bigEndian)) {
            val tag = u16(entry)
            if (tag !in TAGS || u16(entry + 2) != RATIONAL || u32(entry + 4) != 1L) continue
            val position = tiff + u32(entry + 8).toInt()
            if (position < tiff || position + 8 > jpeg.size) continue
            found[tag] = Slot(position, bigEndian)
        }
        return found
    }

    /** Byte positions of each 12-byte entry in the IFD at [ifd]. */
    private fun entries(jpeg: ByteArray, ifd: Int, bigEndian: Boolean): List<Int> {
        if (ifd < 0 || ifd + 2 > jpeg.size) return emptyList()
        val count = readU16(jpeg, ifd, bigEndian)
        return (0 until count).map { ifd + 2 + it * 12 }.filter { it + 12 <= jpeg.size }
    }

    /** Position of the TIFF header inside the APP1 "Exif" segment, or null when there is none. */
    private fun tiffStart(jpeg: ByteArray): Int? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null
            val length = readU16(jpeg, i + 2, true)
            if (length < 2) return null
            if (marker == 0xE1 && i + 10 + 8 <= jpeg.size &&
                String(jpeg, i + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000"
            ) {
                return i + 10
            }
            i += 2 + length
        }
        return null
    }

    private fun readU16(b: ByteArray, at: Int, bigEndian: Boolean): Int {
        val first = b[at].toInt() and 0xFF
        val second = b[at + 1].toInt() and 0xFF
        return if (bigEndian) (first shl 8) or second else (second shl 8) or first
    }

    private fun readU32(b: ByteArray, at: Int, bigEndian: Boolean): Long {
        var value = 0L
        for (k in 0 until 4) {
            val byte = (b[at + if (bigEndian) k else 3 - k].toLong() and 0xFF)
            value = (value shl 8) or byte
        }
        return value
    }

    private fun u32(value: Long, bigEndian: Boolean): ByteArray {
        val bytes = ByteArray(4) { k -> (value shr (8 * (3 - k))).toByte() }
        return if (bigEndian) bytes else bytes.reversedArray()
    }
}
