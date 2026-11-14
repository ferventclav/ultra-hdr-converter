package com.example.ultrahdr.processing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Repairs JPEG Multi-Picture Format (CIPA DC-007 / MPF) offsets and entry sizes after
 * [androidx.exifinterface.media.ExifInterface.saveAttributes] inserts or resizes the APP1 EXIF
 * segment in an Android Ultra HDR JPEG.
 *
 * ExifInterface preserves trailing secondary images (the GainMap JPEG) and APP2 MPF segments
 * verbatim, so inserting/replacing EXIF at offset 2 leaves MPEntry[0].IndividualImageSize stale
 * and can misalign strict decoders (e.g. Instagram / libultrahdr).
 */
internal object JpegMpfFixer {

    private val MPF_SIGNATURE = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte(), 0)
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)

    private const val TAG_MP_ENTRY = 0xB002

    data class MpfFixReport(
        val patchedMpf: Boolean,
        val primarySize: Int,
        val gainmapStart: Int,
        val gainmapSize: Int
    )

    fun fixMpfAndContainer(jpegFile: File): MpfFixReport {
        val bytes = jpegFile.readBytes()
        val result = fixMpfBytes(bytes)
        if (result.second.patchedMpf) {
            jpegFile.writeBytes(result.first)
        }
        return result.second
    }

    /**
     * Parses [bytes], finds Primary JPEG boundaries and any appended Secondary GainMap JPEG,
     * and patches APP2 MPF MPEntry[0] and MPEntry[1] sizes/offsets in-place on a copy.
     */
    fun fixMpfBytes(bytes: ByteArray): Pair<ByteArray, MpfFixReport> {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            return bytes to MpfFixReport(false, 0, -1, 0)
        }

        val primaryRange = findJpegImageRange(bytes, 0)
            ?: return bytes to MpfFixReport(false, 0, -1, 0)
        val primaryEnd = primaryRange.second // exclusive index right after Primary FF D9

        // Locate Secondary JPEG (GainMap) if present right after Primary EOI
        var gainmapStart = -1
        var gainmapEnd = -1
        if (primaryEnd + 2 <= bytes.size &&
            bytes[primaryEnd] == 0xFF.toByte() &&
            bytes[primaryEnd + 1] == 0xD8.toByte()
        ) {
            val gmRange = findJpegImageRange(bytes, primaryEnd)
            if (gmRange != null) {
                gainmapStart = gmRange.first
                gainmapEnd = gmRange.second
            }
        }
        val gainmapSize = if (gainmapStart >= 0) gainmapEnd - gainmapStart else 0

        val out = bytes.copyOf()
        var patchedMpf = false

        // Walk Primary JPEG markers before SOS to patch APP2 MPF and verify XMP Item:Length
        var pos = 2
        while (pos + 2 <= primaryEnd) {
            if (out[pos] != 0xFF.toByte()) break
            while (pos + 1 < primaryEnd && out[pos + 1] == 0xFF.toByte()) {
                pos++
            }
            if (pos + 1 >= primaryEnd) break
            val marker = out[pos + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break // EOI or SOS ends header markers
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) {
                pos += 2
                continue
            }
            if (pos + 4 > primaryEnd) break
            val segLen = ((out[pos + 2].toInt() and 0xFF) shl 8) or (out[pos + 3].toInt() and 0xFF)
            if (segLen < 2 || pos + 2 + segLen > primaryEnd) break

            val payloadStart = pos + 4
            val payloadLen = segLen - 2

            if (marker == 0xE2 && payloadLen > 12 && matchesPrefix(out, payloadStart, MPF_SIGNATURE)) {
                val mpfTiffStart = payloadStart + MPF_SIGNATURE.size
                val mpfTiffEnd = payloadStart + payloadLen
                if (patchMpfTiff(out, mpfTiffStart, mpfTiffEnd, primaryEnd, gainmapStart, gainmapSize)) {
                    patchedMpf = true
                }
            } else if (marker == 0xE1 && gainmapSize > 0 &&
                payloadLen > XMP_HEADER.size &&
                matchesPrefix(out, payloadStart, XMP_HEADER)
            ) {
                if (patchGainMapXmpLengthIfNeeded(out, payloadStart + XMP_HEADER.size, payloadStart + payloadLen, gainmapSize)) {
                    patchedMpf = true
                }
            }

            pos += 2 + segLen
        }

        return out to MpfFixReport(
            patchedMpf = patchedMpf,
            primarySize = primaryEnd,
            gainmapStart = gainmapStart,
            gainmapSize = gainmapSize
        )
    }

    /**
     * Walks a complete JPEG image starting at [startOffset] (`FF D8`) through its entropy-coded
     * scan(s) until its `FF D9` EOI marker, returning `[startOffset, eoiEndExclusive]`.
     */
    internal fun findJpegImageRange(bytes: ByteArray, startOffset: Int): Pair<Int, Int>? {
        if (startOffset < 0 || startOffset + 4 > bytes.size) return null
        if (bytes[startOffset] != 0xFF.toByte() || bytes[startOffset + 1] != 0xD8.toByte()) return null

        var pos = startOffset + 2
        while (pos + 1 < bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) {
                pos++
                continue
            }
            while (pos + 1 < bytes.size && bytes[pos + 1] == 0xFF.toByte()) {
                pos++
            }
            if (pos + 1 >= bytes.size) return null
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == 0x00) {
                pos += 2
                continue
            }
            if (marker == 0xD9) {
                return startOffset to (pos + 2)
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                pos += 2
                continue
            }

            if (pos + 4 > bytes.size) return null
            val segLen = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (segLen < 2 || pos + 2 + segLen > bytes.size) return null

            pos += 2 + segLen

            if (marker == 0xDA) {
                // Scan entropy-coded data until a non-stuffed, non-RST marker
                while (pos + 1 < bytes.size) {
                    if (bytes[pos] != 0xFF.toByte()) {
                        pos++
                        continue
                    }
                    var nextIdx = pos + 1
                    while (nextIdx < bytes.size && bytes[nextIdx] == 0xFF.toByte()) {
                        nextIdx++
                    }
                    if (nextIdx >= bytes.size) return null
                    val nextMarker = bytes[nextIdx].toInt() and 0xFF
                    if (nextMarker == 0x00 || nextMarker in 0xD0..0xD7) {
                        pos = nextIdx + 1
                        continue
                    }
                    // Found an actual marker ending the entropy scan
                    pos = nextIdx - 1
                    break
                }
            }
        }
        return null
    }

    private fun patchMpfTiff(
        buf: ByteArray,
        tiffStart: Int,
        tiffEnd: Int,
        primarySize: Int,
        gainmapStart: Int,
        gainmapSize: Int
    ): Boolean {
        if (tiffStart + 8 > tiffEnd) return false

        val order = when {
            buf[tiffStart] == 0x4D.toByte() && buf[tiffStart + 1] == 0x4D.toByte() &&
                buf[tiffStart + 2] == 0x00.toByte() && buf[tiffStart + 3] == 0x2A.toByte() -> ByteOrder.BIG_ENDIAN
            buf[tiffStart] == 0x49.toByte() && buf[tiffStart + 1] == 0x49.toByte() &&
                buf[tiffStart + 2] == 0x2A.toByte() && buf[tiffStart + 3] == 0x00.toByte() -> ByteOrder.LITTLE_ENDIAN
            else -> return false
        }

        val bb = ByteBuffer.wrap(buf, tiffStart, tiffEnd - tiffStart).slice().order(order)
        val ifdOffset = bb.getInt(4)
        if (ifdOffset < 8 || ifdOffset + 2 > bb.limit()) return false

        val entryCount = bb.getShort(ifdOffset).toInt() and 0xFFFF
        var patched = false

        for (i in 0 until entryCount) {
            val entryRel = ifdOffset + 2 + i * 12
            if (entryRel + 12 > tiffEnd - tiffStart) break
            val tag = bb.getShort(entryRel).toInt() and 0xFFFF
            if (tag == TAG_MP_ENTRY) {
                val byteCount = bb.getInt(entryRel + 4)
                val valueOffset = bb.getInt(entryRel + 8)
                val mpEntryCount = byteCount / 16
                if (mpEntryCount < 1 || valueOffset < 8 || valueOffset + mpEntryCount * 16 > tiffEnd - tiffStart) {
                    return false
                }

                // MPEntry[0]: Primary image -> IndividualImageSize at +4, IndividualImageDataOffset = 0 at +8
                bb.putInt(valueOffset + 4, primarySize)
                bb.putInt(valueOffset + 8, 0)
                patched = true

                // MPEntry[1]: Secondary GainMap image -> size and offset relative to MPF TIFF header
                if (mpEntryCount >= 2 && gainmapStart >= 0 && gainmapSize > 0) {
                    val gmOffsetFromMpfHeader = gainmapStart - tiffStart
                    val entry1Rel = valueOffset + 16
                    bb.putInt(entry1Rel + 4, gainmapSize)
                    bb.putInt(entry1Rel + 8, gmOffsetFromMpfHeader)
                }
                break
            }
        }
        return patched
    }

    /**
     * Checks whether an XMP GainMap item length attribute exists with a mismatched byte count
     * (without changing total segment length if lengths differ, though normal EXIF edits do not
     * alter the gainmap byte size). Preserves exact XMP framing if already matching.
     */
    private fun patchGainMapXmpLengthIfNeeded(
        buf: ByteArray,
        xmpStart: Int,
        xmpEnd: Int,
        actualGainmapSize: Int
    ): Boolean {
        val xmp = String(buf, xmpStart, xmpEnd - xmpStart, StandardCharsets.UTF_8)
        val regex = Regex("""(Item:Semantic="GainMap"[^>]*?Item:Length=")(\d+)(")""")
        val match = regex.find(xmp) ?: return false
        val recorded = match.groupValues[2].toIntOrNull() ?: return false
        if (recorded == actualGainmapSize) return false

        val replacement = actualGainmapSize.toString()
        // Only patch in-place if digit count matches so APP1 segment length remains valid;
        // if digit count grew (unexpected, since ExifInterface doesn't touch GainMap bytes), skip.
        if (replacement.length != match.groupValues[2].length) return false

        val startInXmp = match.groups[2]?.range?.first ?: return false
        val repBytes = replacement.toByteArray(StandardCharsets.US_ASCII)
        val prefixBytes = xmp.substring(0, startInXmp).toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(repBytes, 0, buf, xmpStart + prefixBytes.size, repBytes.size)
        return true
    }

    private fun matchesPrefix(buf: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > buf.size) return false
        for (i in prefix.indices) {
            if (buf[offset + i] != prefix[i]) return false
        }
        return true
    }
}
