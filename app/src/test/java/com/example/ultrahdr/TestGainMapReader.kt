package com.example.ultrahdr

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A second, separate implementation of "find the gain map in this JPEG", written the way Android's
 * image decoder (Skia) does it. It shares no code with the app's writer, and it is first proven on
 * a real camera file, so when it accepts the app's output the two agree on the file format.
 *
 * Steps, as in the decoder:
 *  1. the photo's own XMP must say hdrgm:Version="1.0", declared on the same rdf:Description;
 *  2. the MPF index (tags in order, version "0100") gives the second picture's place and size;
 *  3. that picture must be a JPEG whose XMP holds the hdrgm numbers;
 *  4. the Container directory must point at the same place.
 */
object TestGainMapReader {

    private const val NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val NS_HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private const val NS_CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val NS_ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val XMP_SIG = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val ISO_SIG = "urn:iso:std:iso:ts:21496:-1\u0000"

    class Segment(val marker: Int, val start: Int, val payloadStart: Int, val payloadLength: Int)

    class Found(
        val primaryEnd: Int,
        val gainMapStart: Int,
        val gainMapSize: Int,
        /** Where the Container directory says the gain map is, or null when it has no such entry. */
        val containerGainMapStart: Int?,
        val containerGainMapSize: Int?,
        val hdrgm: Map<String, String>,
        val isoHeadroomLog2: Double?,
        val isoOffsets: Pair<Double, Double>?,
        val primaryHasIsoMarker: Boolean,
        val primaryXmp: String
    )

    fun read(file: ByteArray): Found {
        val segments = headerSegments(file, 0)
        val xmpSeg = segments.firstOrNull { it.marker == 0xE1 && startsWith(file, it, XMP_SIG) }
            ?: error("photo has no XMP")
        val xmp = String(
            file, xmpSeg.payloadStart + XMP_SIG.length, xmpSeg.payloadLength - XMP_SIG.length, Charsets.UTF_8
        )
        check(hdrgmAttributes(xmp) != null) { "photo XMP does not declare hdrgm:Version=\"1.0\"" }

        val primaryEnd = imageEnd(file, 0) ?: error("photo has no end marker")

        val mpfSeg = segments.firstOrNull { it.marker == 0xE2 && startsWith(file, it, "MPF\u0000") }
            ?: error("photo has no MPF index")
        val (gmStart, gmSize) = secondPicture(file, mpfSeg, primaryEnd)
        check(gmStart + gmSize <= file.size) { "gain map runs past the end of the file" }
        check(file[gmStart] == 0xFF.toByte() && file[gmStart + 1] == 0xD8.toByte()) {
            "MPF does not point at a JPEG"
        }
        check(imageEnd(file, gmStart) == gmStart + gmSize) { "MPF size does not match the gain map JPEG" }

        val gmSegments = headerSegments(file, gmStart)
        val gmXmpSeg = gmSegments.firstOrNull { it.marker == 0xE1 && startsWith(file, it, XMP_SIG) }
            ?: error("gain map has no XMP")
        val gmXmp = String(
            file, gmXmpSeg.payloadStart + XMP_SIG.length, gmXmpSeg.payloadLength - XMP_SIG.length, Charsets.UTF_8
        )
        val hdrgm = hdrgmAttributes(gmXmp) ?: error("gain map XMP does not declare hdrgm:Version=\"1.0\"")

        var isoHeadroom: Double? = null
        var isoOffsets: Pair<Double, Double>? = null
        gmSegments.firstOrNull { it.marker == 0xE2 && startsWith(file, it, ISO_SIG) }?.let { seg ->
            val bb = ByteBuffer.wrap(file, seg.payloadStart + ISO_SIG.length, seg.payloadLength - ISO_SIG.length)
                .slice().order(ByteOrder.BIG_ENDIAN)
            check(bb.remaining() == 61) { "ISO block is ${bb.remaining()} bytes, expected 61 for one channel" }
            check(bb.getShort(0).toInt() == 0) { "ISO minimum_version must be 0" }
            val flags = bb.get(4).toInt() and 0xFF
            check(flags and 0x80 == 0) { "expected a single-channel ISO block" }
            fun unsigned(at: Int) = bb.getInt(at).toLong() and 0xFFFFFFFFL
            check(unsigned(5) == 0L) { "base headroom should be 0 for an SDR base" }
            isoHeadroom = unsigned(13).toDouble() / unsigned(17).toDouble()
            val max = bb.getInt(29).toDouble() / unsigned(33).toDouble()
            check(kotlin.math.abs(max - isoHeadroom!!) < 1e-6) { "ISO gain map max differs from headroom" }
            check(bb.getInt(21) == 0) { "ISO gain map min should be 0" }
            check(unsigned(37) == unsigned(41)) { "ISO gamma should be 1" }
            isoOffsets = bb.getInt(45).toDouble() / unsigned(49).toDouble() to
                bb.getInt(53).toDouble() / unsigned(57).toDouble()
        }

        val container = containerGainMap(xmp)
        return Found(
            primaryEnd = primaryEnd,
            gainMapStart = gmStart,
            gainMapSize = gmSize,
            containerGainMapStart = container?.let { primaryEnd + it.first },
            containerGainMapSize = container?.second,
            hdrgm = hdrgm,
            isoHeadroomLog2 = isoHeadroom,
            isoOffsets = isoOffsets,
            primaryHasIsoMarker = segments.any { it.marker == 0xE2 && startsWith(file, it, ISO_SIG) },
            primaryXmp = xmp
        )
    }

    /** Blocks between the start-of-image marker and the image data. */
    fun headerSegments(b: ByteArray, imageStart: Int): List<Segment> {
        check(b[imageStart] == 0xFF.toByte() && b[imageStart + 1] == 0xD8.toByte()) { "not a JPEG" }
        val out = ArrayList<Segment>()
        var pos = imageStart + 2
        while (pos + 4 <= b.size) {
            check(b[pos] == 0xFF.toByte()) { "broken header at $pos" }
            val marker = b[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val length = u16be(b, pos + 2)
            out += Segment(marker, pos, pos + 4, length - 2)
            pos += 2 + length
        }
        return out
    }

    /** Offset just past the end-of-image marker of the JPEG that starts at [start]. */
    fun imageEnd(b: ByteArray, start: Int): Int? {
        var pos = start + 2
        while (pos + 1 < b.size) {
            if (b[pos] != 0xFF.toByte()) {
                pos++
                continue
            }
            val marker = b[pos + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> pos++
                marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8 -> pos += 2
                marker == 0xD9 -> return pos + 2
                else -> {
                    pos += 2 + u16be(b, pos + 2)
                    if (marker == 0xDA) {
                        // Image data: runs until a marker that is not padding or a restart.
                        while (pos + 1 < b.size) {
                            if (b[pos] != 0xFF.toByte()) {
                                pos++
                                continue
                            }
                            val next = b[pos + 1].toInt() and 0xFF
                            if (next == 0x00 || next in 0xD0..0xD7) pos += 2
                            else if (next == 0xFF) pos++
                            else break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun secondPicture(b: ByteArray, mpf: Segment, primaryEnd: Int): Pair<Int, Int> {
        val base = mpf.payloadStart + 4
        val order = when (String(b, base, 2, Charsets.US_ASCII)) {
            "MM" -> ByteOrder.BIG_ENDIAN
            "II" -> ByteOrder.LITTLE_ENDIAN
            else -> error("MPF byte order mark missing")
        }
        val bb = ByteBuffer.wrap(b, base, mpf.payloadLength - 4).slice().order(order)
        check(bb.getShort(2).toInt() == 0x2A) { "MPF TIFF magic missing" }
        val ifd = bb.getInt(4)
        val count = bb.getShort(ifd).toInt() and 0xFFFF
        var previousTag = 0
        var images = 0
        var entriesAt = -1
        var entriesBytes = 0
        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            val tag = bb.getShort(at).toInt() and 0xFFFF
            check(tag > previousTag) { "MPF tags out of order" }
            previousTag = tag
            val type = bb.getShort(at + 2).toInt() and 0xFFFF
            val n = bb.getInt(at + 4)
            when (tag) {
                0xB000 -> {
                    check(type == 7 && n == 4) { "MPF version entry malformed" }
                    val v = ByteArray(4).also { arr -> for (k in 0 until 4) arr[k] = bb.get(at + 8 + k) }
                    check(String(v, Charsets.US_ASCII) == "0100") { "MPF version must be 0100" }
                }
                0xB001 -> {
                    check(type == 4 && n == 1) { "MPF image count malformed" }
                    images = bb.getInt(at + 8)
                }
                0xB002 -> {
                    check(type == 7) { "MPF entries must be UNDEFINED" }
                    entriesBytes = n
                    entriesAt = bb.getInt(at + 8)
                }
            }
        }
        check(images >= 2) { "MPF lists $images image(s)" }
        check(entriesAt > 0 && entriesBytes == images * 16) { "MPF entry table has the wrong size" }
        val firstSize = bb.getInt(entriesAt + 4)
        val firstOffset = bb.getInt(entriesAt + 8)
        check(firstOffset == 0) { "first MPF picture must sit at offset 0" }
        check(firstSize == primaryEnd) { "MPF says the photo is $firstSize bytes, it is $primaryEnd" }
        val size = bb.getInt(entriesAt + 16 + 4)
        val offset = bb.getInt(entriesAt + 16 + 8)
        return (base + offset) to size
    }

    /** Attributes in the hdrgm namespace, or null unless the node declares the namespace and Version 1.0. */
    private fun hdrgmAttributes(xmp: String): Map<String, String>? {
        val doc = parse(xmp)
        val nodes = doc.getElementsByTagNameNS(NS_RDF, "Description")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val declaresHere = (0 until el.attributes.length).any {
                val a = el.attributes.item(it)
                a.nodeName.startsWith("xmlns:") && a.nodeValue == NS_HDRGM
            }
            if (!declaresHere) continue
            if (el.getAttributeNS(NS_HDRGM, "Version") != "1.0") continue
            val out = HashMap<String, String>()
            for (k in 0 until el.attributes.length) {
                val a = el.attributes.item(k)
                if (a.namespaceURI == NS_HDRGM) out[a.localName] = a.nodeValue
            }
            return out
        }
        return null
    }

    /** (bytes between the photo's end and the gain map, gain map length) from the Container directory. */
    private fun containerGainMap(xmp: String): Pair<Int, Int>? {
        val doc = parse(xmp)
        val dirs = doc.getElementsByTagNameNS(NS_CONTAINER, "Directory")
        if (dirs.length == 0) return null
        val items = (dirs.item(0) as Element).getElementsByTagNameNS(NS_CONTAINER, "Item")
        var offset = 0
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val li = item.parentNode as Element
            check(li.localName == "li" && li.getAttributeNS(NS_RDF, "parseType") == "Resource") {
                "Container items must sit in rdf:li parseType=Resource"
            }
            val semantic = item.getAttributeNS(NS_ITEM, "Semantic")
            val mime = item.getAttributeNS(NS_ITEM, "Mime")
            val length = item.getAttributeNS(NS_ITEM, "Length").toIntOrNull() ?: 0
            val padding = item.getAttributeNS(NS_ITEM, "Padding").toIntOrNull() ?: 0
            if (i == 0) {
                check(semantic == "Primary" && mime == "image/jpeg") { "first Container item must be the photo" }
                offset += padding
                continue
            }
            if (semantic == "GainMap") {
                check(mime == "image/jpeg") { "gain map item must be a JPEG" }
                return offset to length
            }
            offset += length + padding
        }
        return null
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.trim { it <= ' ' || it == '\u0000' }.toByteArray(Charsets.UTF_8)))

    private fun startsWith(b: ByteArray, seg: Segment, sig: String): Boolean {
        if (seg.payloadLength < sig.length) return false
        for (i in sig.indices) if (b[seg.payloadStart + i] != sig[i].code.toByte()) return false
        return true
    }

    private fun u16be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}
