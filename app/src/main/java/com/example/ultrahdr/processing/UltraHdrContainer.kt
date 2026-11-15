package com.example.ultrahdr.processing

import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Turns a normal JPEG into an Ultra HDR JPEG without re-encoding it.
 *
 * An Ultra HDR file is the ordinary photo, a few small metadata blocks, and a second, small JPEG
 * (the gain map) stored straight after it. So the photo itself never has to be decoded and saved
 * again: its pixels, EXIF, maker notes, colour profile and anything a camera appended after the
 * image stay byte for byte as they were. This object writes exactly that:
 *
 *     [original header] [XMP: "a gain map follows"] [ISO 21496-1 marker] [MPF index]
 *     [rest of the original photo] [gain map JPEG] [whatever followed the original photo]
 *
 * The layout matches what Samsung and Pixel cameras write. Pure Kotlin, so it is tested on a PC.
 */
internal object UltraHdrContainer {

    /** Why a file can't take a gain map this way. */
    enum class Blocker {
        NOT_JPEG,
        UNREADABLE,
        /** It already carries other embedded pictures (an MPF index), which this writer won't rearrange. */
        HAS_EXTRA_IMAGES,
        /** A video is stored behind the photo. Its bookkeeping must not be disturbed. */
        MOTION_PHOTO,
        UNUSUAL_METADATA
    }

    class Layout internal constructor(
        /** Offset where the new metadata blocks go: after the leading JFIF and EXIF blocks. */
        val insertAt: Int,
        /** Offset just past the photo's end-of-image marker. */
        val primaryEnd: Int,
        /** Existing XMP block, or -1 when the photo has none. */
        val xmpStart: Int,
        val xmpEnd: Int
    )

    sealed class Inspection {
        class Ready(val layout: Layout) : Inspection()
        class Blocked(val reason: Blocker) : Inspection()
        object AlreadyHdr : Inspection()
    }

    const val NS_HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/"
    const val NS_CONTAINER = "http://ns.google.com/photos/1.0/container/"
    const val NS_ITEM = "http://ns.google.com/photos/1.0/container/item/"

    /** Small constant added before the boost is applied, so pure black stays stable. */
    const val OFFSET = 1f / 64f

    private val EXIF_SIG = "Exif\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val XMP_SIG = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val MPF_SIG = "MPF\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val ISO_SIG = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val MOTION_TRAILER = "MotionPhoto_Data".toByteArray(StandardCharsets.US_ASCII)

    private const val MAX_PAYLOAD = 65533
    private const val MPF_SEGMENT_SIZE = 90
    private const val FF = 0xFF.toByte()

    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == FF && bytes[1] == 0xD8.toByte()

    /** Reads the file's structure and says whether a gain map can be attached. */
    fun inspect(jpeg: ByteArray): Inspection {
        if (!isJpeg(jpeg)) return Inspection.Blocked(Blocker.NOT_JPEG)

        var pos = 2
        var insertAt = 2
        var leading = true
        var xmpStart = -1
        var xmpEnd = -1
        var hasMpf = false
        var hasIso = false
        var reachedScan = false
        var hasMultipleXmp = false
        // Collect all XMP text for gain map and motion photo detection.
        val allXmpTexts = mutableListOf<String>()

        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != FF) return Inspection.Blocked(Blocker.UNREADABLE)
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                reachedScan = true
                break
            }
            // Before the image data every block has a length. Anything else is not a layout to edit.
            if (marker == 0x00 || marker == 0x01 || marker == 0xFF || marker in 0xD0..0xD9) {
                return Inspection.Blocked(Blocker.UNREADABLE)
            }
            val segLen = u16(jpeg, pos + 2)
            val segEnd = pos + 2 + segLen
            if (segLen < 2 || segEnd > jpeg.size) return Inspection.Blocked(Blocker.UNREADABLE)
            val payload = pos + 4
            val payloadLen = segLen - 2

            val isExif = marker == 0xE1 && startsWith(jpeg, payload, payloadLen, EXIF_SIG)
            if (leading && (marker == 0xE0 || isExif)) insertAt = segEnd else leading = false

            when {
                marker == 0xE1 && startsWith(jpeg, payload, payloadLen, XMP_SIG) -> {
                    val thisText = String(jpeg, payload + XMP_SIG.size, payloadLen - XMP_SIG.size, StandardCharsets.UTF_8)
                    allXmpTexts.add(thisText)
                    if (xmpStart >= 0) {
                        // Multiple XMP blocks (common in Samsung camera files).
                        // Don't abort; keep scanning. We'll check everything after the loop.
                        hasMultipleXmp = true
                    } else {
                        xmpStart = pos
                        xmpEnd = segEnd
                    }
                }
                marker == 0xE2 && startsWith(jpeg, payload, payloadLen, MPF_SIG) -> hasMpf = true
                marker == 0xE2 && startsWith(jpeg, payload, payloadLen, ISO_SIG) -> hasIso = true
            }
            pos = segEnd
        }
        if (!reachedScan) return Inspection.Blocked(Blocker.UNREADABLE)

        // Check ALL XMP blocks for gain map markers.
        if (hasIso) return Inspection.AlreadyHdr
        for (text in allXmpTexts) {
            if (text.contains("hdrgm:Version") || text.contains(NS_HDRGM) ||
                text.contains("http://ns.google.com/photos/1.0/gainmap/") ||
                text.contains("Semantic=\"GainMap\"") ||
                text.contains("urn:iso:std:iso:ts:21496:-1")
            ) {
                return Inspection.AlreadyHdr
            }
        }

        // Check for motion photo markers in ANY XMP block.
        for (text in allXmpTexts) {
            if (text.contains("MotionPhoto") || text.contains("MicroVideo")) {
                return Inspection.Blocked(Blocker.MOTION_PHOTO)
            }
        }

        if (hasMpf) return Inspection.Blocked(Blocker.HAS_EXTRA_IMAGES)

        val primaryEnd = JpegMpfFixer.findJpegImageRange(jpeg, 0)?.second
            ?: return Inspection.Blocked(Blocker.UNREADABLE)
        if (indexOf(jpeg, MOTION_TRAILER, primaryEnd) >= 0) {
            return Inspection.Blocked(Blocker.MOTION_PHOTO)
        }

        // Multiple non-gain-map, non-motion-photo XMP blocks are unusual but not fatal for
        // lossless injection. We only need to merge the first one.
        val xmp = if (xmpStart >= 0) xmpText(jpeg, xmpStart, xmpEnd) else null
        if (xmp != null && mergePrimaryXmp(xmp, gainMapLength = 1) == null) {
            return Inspection.Blocked(Blocker.UNUSUAL_METADATA)
        }
        return Inspection.Ready(Layout(insertAt, primaryEnd, xmpStart, xmpEnd))
    }

    /**
     * Writes [jpeg] with [gainMapJpeg] attached. [ratioMax] is the brightness multiplier where the
     * gain map is white. Returns false, having written nothing useful, if the pieces don't fit.
     */
    fun write(
        jpeg: ByteArray,
        layout: Layout,
        gainMapJpeg: ByteArray,
        ratioMax: Float,
        out: OutputStream
    ): Boolean {
        if (ratioMax <= 1f) return false
        val log2Max = (ln(ratioMax.toDouble()) / ln(2.0)).toFloat()
        val gainMapImage = gainMapImage(gainMapJpeg, log2Max) ?: return false

        val hasXmp = layout.xmpStart >= 0
        val xmp = if (hasXmp) {
            mergePrimaryXmp(xmpText(jpeg, layout.xmpStart, layout.xmpEnd), gainMapImage.size) ?: return false
        } else {
            newPrimaryXmp(gainMapImage.size)
        }
        val xmpSegment = segment(0xE1, XMP_SIG + xmp.toByteArray(StandardCharsets.UTF_8))
            ?: segment(0xE1, XMP_SIG + trimPacketPadding(xmp).toByteArray(StandardCharsets.UTF_8))
            ?: return false
        // Version-only block: tells newer readers that ISO 21496-1 data sits in the gain map image.
        val isoSegment = segment(0xE2, ISO_SIG + ByteArray(4)) ?: return false

        val oldXmpSize = if (hasXmp) layout.xmpEnd - layout.xmpStart else 0
        val newPrimarySize =
            layout.primaryEnd - oldXmpSize + xmpSegment.size + isoSegment.size + MPF_SEGMENT_SIZE
        val mpfStart = layout.insertAt + (if (hasXmp) 0 else xmpSegment.size) + isoSegment.size
        // MPF offsets are counted from the byte-order mark, 8 bytes into the block.
        val mpfBase = mpfStart + 8
        val mpfSegment = mpfSegment(
            primarySize = newPrimarySize,
            gainMapSize = gainMapImage.size,
            gainMapOffset = newPrimarySize - mpfBase
        )

        out.write(jpeg, 0, layout.insertAt)
        if (!hasXmp) out.write(xmpSegment)
        out.write(isoSegment)
        out.write(mpfSegment)
        if (hasXmp) {
            out.write(jpeg, layout.insertAt, layout.xmpStart - layout.insertAt)
            out.write(xmpSegment)
            out.write(jpeg, layout.xmpEnd, layout.primaryEnd - layout.xmpEnd)
        } else {
            out.write(jpeg, layout.insertAt, layout.primaryEnd - layout.insertAt)
        }
        out.write(gainMapImage)
        out.write(jpeg, layout.primaryEnd, jpeg.size - layout.primaryEnd)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Gain map image
    // ---------------------------------------------------------------------------------------

    /** The gain map JPEG with its own metadata: how to turn its pixels into a brightness boost. */
    private fun gainMapImage(gainMapJpeg: ByteArray, log2Max: Float): ByteArray? {
        if (!isJpeg(gainMapJpeg)) return null
        val xmpSegment = segment(
            0xE1, XMP_SIG + gainMapXmp(log2Max).toByteArray(StandardCharsets.UTF_8)
        ) ?: return null
        val isoSegment = segment(0xE2, ISO_SIG + isoMetadata(log2Max)) ?: return null
        val result = ByteArray(gainMapJpeg.size + xmpSegment.size + isoSegment.size)
        var at = 0
        result[at++] = FF
        result[at++] = 0xD8.toByte()
        System.arraycopy(xmpSegment, 0, result, at, xmpSegment.size); at += xmpSegment.size
        System.arraycopy(isoSegment, 0, result, at, isoSegment.size); at += isoSegment.size
        System.arraycopy(gainMapJpeg, 2, result, at, gainMapJpeg.size - 2)
        return result
    }

    internal fun gainMapXmp(log2Max: Float): String =
        gainMapXmpText(String.format(Locale.US, "%.6f", log2Max))

    /** [max] is the boost at full gain, in stops, already written out as text. */
    private fun gainMapXmpText(max: String): String {
        val offset = String.format(Locale.US, "%.6f", OFFSET)
        return """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:hdrgm="$NS_HDRGM"
      hdrgm:Version="1.0"
      hdrgm:GainMapMin="0.000000"
      hdrgm:GainMapMax="$max"
      hdrgm:Gamma="1.000000"
      hdrgm:OffsetSDR="$offset"
      hdrgm:OffsetHDR="$offset"
      hdrgm:HDRCapacityMin="0.000000"
      hdrgm:HDRCapacityMax="$max"
      hdrgm:BaseRenditionIsHDR="False"/>
  </rdf:RDF>
</x:xmpmeta>
"""
    }

    /**
     * The same numbers in the ISO 21496-1 binary form. Field order checked against a Galaxy S25 file:
     * versions, flags, base headroom, HDR headroom, then min, max, gamma and the two offsets,
     * each as a fraction.
     */
    internal fun isoMetadata(log2Max: Float): ByteArray {
        val bb = ByteBuffer.allocate(61).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(0)                     // minimum_version
        bb.putShort(0)                     // writer_version
        bb.put(0x40.toByte())              // one channel; gain applied in the base image's colour space
        bb.putInt(0).putInt(1)             // base_hdr_headroom: the base picture is plain SDR
        putFraction(bb, log2Max)           // alternate_hdr_headroom
        bb.putInt(0).putInt(1)             // gain_map_min
        putFraction(bb, log2Max)           // gain_map_max
        bb.putInt(1).putInt(1)             // gamma
        bb.putInt(1).putInt(64)            // base_offset = 1/64
        bb.putInt(1).putInt(64)            // alternate_offset = 1/64
        return bb.array()
    }

    private fun putFraction(bb: ByteBuffer, value: Float) {
        bb.putInt((value.toDouble() * 1_000_000.0).roundToInt()).putInt(1_000_000)
    }

    // ---------------------------------------------------------------------------------------
    // Blocks added to the photo itself
    // ---------------------------------------------------------------------------------------

    internal fun newPrimaryXmp(gainMapLength: Int): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:hdrgm="$NS_HDRGM"
      xmlns:Container="$NS_CONTAINER"
      xmlns:Item="$NS_ITEM"
      hdrgm:Version="1.0">
      ${containerDirectory(gainMapLength)}
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
"""

    private fun containerDirectory(gainMapLength: Int): String =
        """<Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Semantic="Primary"
              Item:Mime="image/jpeg"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Semantic="GainMap"
              Item:Mime="image/jpeg"
              Item:Length="$gainMapLength"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>"""

    /**
     * Adds the gain map entries to XMP the photo already has (ratings, edits, captions), keeping
     * everything else in it. Returns null when the packet isn't a shape that is safe to edit.
     */
    internal fun mergePrimaryXmp(existing: String, gainMapLength: Int): String? {
        if (existing.contains("<!DOCTYPE") || existing.contains("<!ENTITY")) return null
        if (Regex("""xmlns:(hdrgm|Container|Item)\s*=""").containsMatchIn(existing)) return null
        if (existing.contains("Container:Directory")) return null

        val open = Regex("""<rdf:Description(?=[\s/>])""").find(existing) ?: return null
        // Walk to the end of that start tag, stepping over quoted attribute values.
        var i = open.range.last + 1
        var quote = 0.toChar()
        while (i < existing.length) {
            val c = existing[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                break
            }
            i++
        }
        if (i >= existing.length) return null

        val added = mergedAddition(gainMapLength)
        val selfClosing = existing[i - 1] == '/'
        val merged = if (selfClosing) {
            existing.substring(0, i - 1).trimEnd() + added + DESCRIPTION_END + existing.substring(i + 1)
        } else {
            existing.substring(0, i).trimEnd() + added + existing.substring(i + 1)
        }
        return if (isWellFormed(merged)) merged else null
    }

    private const val DESCRIPTION_END = "</rdf:Description>"

    /** Exactly the text [mergePrimaryXmp] puts into a photo's own XMP, start-tag bracket included. */
    private fun mergedAddition(gainMapLength: Int): String =
        "\n      xmlns:hdrgm=\"$NS_HDRGM\"" +
            "\n      xmlns:Container=\"$NS_CONTAINER\"" +
            "\n      xmlns:Item=\"$NS_ITEM\"" +
            "\n      hdrgm:Version=\"1.0\"" +
            ">" +
            "\n      " + containerDirectory(gainMapLength) + "\n    "

    // ---------------------------------------------------------------------------------------
    // Taking the gain map back out
    // ---------------------------------------------------------------------------------------

    /**
     * Undoes [write]. Returns the photo as it was before this app attached a gain map, or null when
     * the file was not made by [write] (a camera's HDR photo, another app's, or one this app had to
     * save again). Nothing is decoded, so the picture that comes back is the original picture.
     *
     * It is byte for byte the original, with two small exceptions: a photo that had XMP of its own
     * may get that XMP back with its spare padding or a blank before the closing bracket gone, and
     * a date this app wrote into the EXIF afterwards stays in it.
     */
    fun strip(jpeg: ByteArray): ByteArray? {
        if (!isJpeg(jpeg)) return null

        // 1. Find the pair of blocks only this writer puts side by side, the ISO marker and the
        //    index, and every XMP block. Saving a date into the EXIF afterwards can move the XMP
        //    to the front, so it may sit before or after the pair.
        var pos = 2
        var isoStart = -1
        var scanStart = -1
        val xmpBlocks = mutableListOf<IntRange>()
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != FF) return null
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                scanStart = pos
                break
            }
            if (marker == 0x00 || marker == 0x01 || marker == 0xFF || marker in 0xD0..0xD9) return null
            val segLen = u16(jpeg, pos + 2)
            val segEnd = pos + 2 + segLen
            if (segLen < 2 || segEnd > jpeg.size) return null
            if (isoStart < 0 && marker == 0xE2 && segLen - 2 == ISO_SIG.size + 4 &&
                startsWith(jpeg, pos + 4, segLen - 2, ISO_SIG)
            ) {
                isoStart = pos
            }
            if (marker == 0xE1 && startsWith(jpeg, pos + 4, segLen - 2, XMP_SIG)) xmpBlocks += pos until segEnd
            pos = segEnd
        }
        if (isoStart < 0 || scanStart < 0) return null
        val mpfStart = isoStart + 4 + ISO_SIG.size + 4
        val mpfEnd = mpfStart + MPF_SEGMENT_SIZE
        if (mpfEnd > jpeg.size || jpeg[mpfStart] != FF || jpeg[mpfStart + 1] != 0xE2.toByte() ||
            u16(jpeg, mpfStart + 2) != MPF_SEGMENT_SIZE - 2 ||
            !startsWith(jpeg, mpfStart + 4, MPF_SEGMENT_SIZE - 4, MPF_SIG)
        ) {
            return null
        }

        // 2. The index says where the photo ends and how long the gain map is. Check it adds up.
        val primarySize = i32(jpeg, mpfStart + 58 + 4)
        val gainMapSize = i32(jpeg, mpfStart + 74 + 4)
        val gainMapOffset = i32(jpeg, mpfStart + 74 + 8)
        if (primarySize <= mpfEnd || gainMapSize < 8 || gainMapOffset + mpfStart + 8 != primarySize) return null
        if (primarySize.toLong() + gainMapSize > jpeg.size) return null
        if (jpeg[primarySize - 2] != FF || jpeg[primarySize - 1] != 0xD9.toByte()) return null

        // 3. The gain map has to open with this writer's own description, character for character.
        if (jpeg[primarySize] != FF || jpeg[primarySize + 1] != 0xD8.toByte()) return null
        val described = xmpSegmentText(jpeg, primarySize + 2, primarySize + gainMapSize) ?: return null
        val max = Regex("""hdrgm:GainMapMax="([0-9.]+)"""").find(described)?.groupValues?.get(1) ?: return null
        if (described != gainMapXmpText(max)) return null

        if (scanStart > primarySize) return null

        // 4. The XMP this writer added (dropped), or the photo's own XMP it extended (restored).
        var xmpBlock: IntRange? = null
        var restored: ByteArray? = null
        for (block in xmpBlocks) {
            val text = xmpText(jpeg, block.first, block.last + 1)
            if (text == newPrimaryXmp(gainMapSize)) {
                xmpBlock = block
                break
            }
            val added = mergedAddition(gainMapSize)
            val at = text.indexOf(added)
            if (at >= 0) {
                val rest = text.substring(at + added.length)
                val original = if (rest.startsWith(DESCRIPTION_END)) {
                    text.substring(0, at) + "/>" + rest.substring(DESCRIPTION_END.length)
                } else {
                    text.substring(0, at) + ">" + rest
                }
                restored = segment(0xE1, XMP_SIG + original.toByteArray(StandardCharsets.UTF_8)) ?: return null
                xmpBlock = block
                break
            }
        }
        if (xmpBlock == null) return null

        // 5. The header without the three blocks (or with the photo's own XMP put back), then the
        //    picture, then whatever followed the gain map.
        val cuts = listOf(isoStart until mpfStart, mpfStart until mpfEnd, xmpBlock).sortedBy { it.first }
        val out = java.io.ByteArrayOutputStream(jpeg.size - gainMapSize)
        var at = 0
        for (cut in cuts) {
            if (cut.first < at) return null
            out.write(jpeg, at, cut.first - at)
            if (cut == xmpBlock && restored != null) out.write(restored)
            at = cut.last + 1
        }
        out.write(jpeg, at, primarySize - at)
        val afterGainMap = primarySize + gainMapSize
        out.write(jpeg, afterGainMap, jpeg.size - afterGainMap)
        return out.toByteArray()
    }

    /**
     * True when two JPEGs hold the same picture data: the same compressed image from the first scan
     * to the end of the main picture. Their headers (EXIF, XMP, the HDR blocks) may differ.
     */
    fun samePicture(a: ByteArray, b: ByteArray): Boolean {
        val (aStart, aEnd) = pictureRange(a) ?: return false
        val (bStart, bEnd) = pictureRange(b) ?: return false
        if (aEnd - aStart != bEnd - bStart) return false
        for (i in 0 until aEnd - aStart) if (a[aStart + i] != b[bStart + i]) return false
        return true
    }

    private fun pictureRange(jpeg: ByteArray): Pair<Int, Int>? {
        if (!isJpeg(jpeg)) return null
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != FF) return null
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                val end = JpegMpfFixer.findJpegImageRange(jpeg, 0)?.second ?: return null
                return if (end > pos) pos to end else null
            }
            val segLen = u16(jpeg, pos + 2)
            if (segLen < 2) return null
            pos += 2 + segLen
        }
        return null
    }

    /** The text of the XMP block that starts at [segStart], or null when that block is something else. */
    private fun xmpSegmentText(jpeg: ByteArray, segStart: Int, limit: Int): String? {
        if (segStart + 4 > limit || jpeg[segStart] != FF || jpeg[segStart + 1] != 0xE1.toByte()) return null
        val segLen = u16(jpeg, segStart + 2)
        val segEnd = segStart + 2 + segLen
        if (segLen < 2 || segEnd > limit) return null
        if (!startsWith(jpeg, segStart + 4, segLen - 2, XMP_SIG)) return null
        return xmpText(jpeg, segStart, segEnd)
    }

    private fun i32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** XMP packets end in kilobytes of spare spaces. Dropping them makes room for the new entries. */
    private fun trimPacketPadding(xmp: String): String =
        xmp.replace(Regex("""\s{16,}(?=<\?xpacket end)"""), "\n")

    private fun isWellFormed(xml: String): Boolean = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(DefaultHandler())
        builder.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * The multi-picture index: "this file holds two pictures, the second starts here".
     * Two entries, big-endian, the same 90 bytes a camera writes.
     */
    private fun mpfSegment(primarySize: Int, gainMapSize: Int, gainMapOffset: Int): ByteArray {
        val bb = ByteBuffer.allocate(MPF_SEGMENT_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.put(FF).put(0xE2.toByte()).putShort((MPF_SEGMENT_SIZE - 2).toShort())
        bb.put(MPF_SIG)
        bb.put('M'.code.toByte()).put('M'.code.toByte()).putShort(0x002A).putInt(8)
        bb.putShort(3)
        // MPFVersion = "0100"
        bb.putShort(0xB000.toShort()).putShort(7).putInt(4)
            .put("0100".toByteArray(StandardCharsets.US_ASCII))
        // NumberOfImages = 2
        bb.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(2)
        // MPEntry: 2 x 16 bytes, stored 50 bytes in
        bb.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(50)
        bb.putInt(0)
        // The photo: flagged as the primary image, always at offset 0
        bb.putInt(0x00030000).putInt(primarySize).putInt(0).putShort(0).putShort(0)
        // The gain map
        bb.putInt(0).putInt(gainMapSize).putInt(gainMapOffset).putShort(0).putShort(0)
        return bb.array()
    }

    // ---------------------------------------------------------------------------------------
    // Byte helpers
    // ---------------------------------------------------------------------------------------

    private fun segment(marker: Int, payload: ByteArray): ByteArray? {
        if (payload.size > MAX_PAYLOAD) return null
        val out = ByteArray(payload.size + 4)
        out[0] = FF
        out[1] = marker.toByte()
        val length = payload.size + 2
        out[2] = (length shr 8).toByte()
        out[3] = length.toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    private fun xmpText(jpeg: ByteArray, segStart: Int, segEnd: Int): String {
        val from = segStart + 4 + XMP_SIG.size
        return String(jpeg, from, segEnd - from, StandardCharsets.UTF_8)
    }

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun startsWith(b: ByteArray, offset: Int, available: Int, prefix: ByteArray): Boolean {
        if (available < prefix.size || offset + prefix.size > b.size) return false
        for (i in prefix.indices) if (b[offset + i] != prefix[i]) return false
        return true
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        val first = needle[0]
        val last = haystack.size - needle.size
        var i = from
        while (i <= last) {
            if (haystack[i] == first) {
                var k = 1
                while (k < needle.size && haystack[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            i++
        }
        return -1
    }
}
