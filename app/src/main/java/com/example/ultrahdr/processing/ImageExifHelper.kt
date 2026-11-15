package com.example.ultrahdr.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale

object ImageExifHelper {

    // Verified list of metadata attributes supported by Samsung Gallery and Android MediaStore
    val WRITABLE_METADATA_TAGS = listOf(
        // Camera Make & Model
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,

        // Capture Timestamps
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,

        // Exposure & Optics (Displayed in Samsung Gallery Details)
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_COLOR_SPACE,

        // Lens Info
        "LensMake",
        "LensModel",
        "LensSpecification",

        // GPS Geolocation
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    /** How much of a file's start is searched for gain map markers. They sit in the header. */
    const val HEADER_SCAN_BYTES = 262144

    /**
     * True when the start of a file carries the markers of an Ultra HDR / ISO 21496-1 gain map.
     * Does NOT match the generic Google Container namespace, which Samsung Motion Photos also use.
     */
    fun looksLikeUltraHdr(header: ByteArray, length: Int = header.size): Boolean {
        val size = minOf(length, header.size)
        val content = String(header, 0, size, Charsets.ISO_8859_1)
        // HEIC and AVIF keep their gain map as a "tone map" item, or as Apple's own auxiliary image.
        val isHeif = size >= 12 && content.startsWith("ftyp", startIndex = 4)
        if (isHeif && (content.contains("tmap") || content.contains("hdrgainmap"))) return true
        return content.contains("urn:iso:std:iso:ts:21496:-1") ||
            content.contains("Item:Semantic=\"GainMap\"") ||
            content.contains("http://ns.google.com/photos/1.0/gainmap/") ||
            content.contains("xmlns:hdrgm=\"http://ns.google.com/photos/1.0/gainmap/\"") ||
            content.contains("http://ns.adobe.com/hdr-gain-map/1.0/") ||
            content.contains("hdrgm:Version") ||
            content.contains("hdrgm:GainMapMin") ||
            content.contains("hdrgm:GainMapMax")
    }

    /**
     * Inspects whether the image already has an authentic Ultra HDR / ISO 21496-1 Gain Map.
     */
    fun isAlreadyUltraHdr(sourceFile: File): Boolean {
        // 1. Precise byte inspection for specific Ultra HDR Gainmap namespaces
        try {
            // The whole file: some cameras put the labels after a large EXIF block.
            val buffer = ByteArray(minOf(sourceFile.length(), 200L * 1024 * 1024).toInt())
            var filled = 0
            FileInputStream(sourceFile).use { input ->
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
            }
            if (looksLikeUltraHdr(buffer, filled)) return true
        } catch (_: Exception) {}

        // 2. Framework-level gain map check on Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4 // Fast decode to check gainmap presence
                }
                val bmp = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
                if (bmp != null) {
                    val hasMap = bmp.hasGainmap()
                    bmp.recycle()
                    if (hasMap) return true
                }
            } catch (_: Throwable) {}
        }

        return false
    }

    /**
     * Reads all metadata tags from the cached source file into a key-value map.
     */
    fun extractMetadata(sourceFile: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val exif = ExifInterface(sourceFile.absolutePath)
            for (tag in WRITABLE_METADATA_TAGS) {
                val value = exif.getAttribute(tag)
                if (!value.isNullOrBlank()) {
                    map[tag] = value
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    /**
     * Reads original capture date in epoch milliseconds from the cached file.
     */
    fun getCaptureTimestamp(sourceFile: File): Long? = try {
        captureTimestamp(ExifInterface(sourceFile.absolutePath))
    } catch (e: Exception) {
        null
    }

    /** Same as [getCaptureTimestamp], for a photo already held in memory. */
    fun getCaptureTimestamp(bytes: ByteArray): Long? = try {
        captureTimestamp(ExifInterface(ByteArrayInputStream(bytes)))
    } catch (e: Exception) {
        null
    }

    /** Which kinds of metadata a photo carries, for the Copy report: "date+camera+gps", or "none". */
    fun metadataKinds(bytes: ByteArray): String = try {
        metadataKinds(ExifInterface(ByteArrayInputStream(bytes)))
    } catch (e: Exception) {
        "unreadable"
    }

    fun metadataKinds(file: File): String = try {
        metadataKinds(ExifInterface(file.absolutePath))
    } catch (e: Exception) {
        "unreadable"
    }

    private fun metadataKinds(exif: ExifInterface): String = listOfNotNull(
        "date".takeIf { captureTimestamp(exif) != null },
        "camera".takeIf { exif.getAttribute(ExifInterface.TAG_MAKE) != null || exif.getAttribute(ExifInterface.TAG_MODEL) != null },
        "gps".takeIf { exif.latLong != null }
    ).joinToString("+").ifEmpty { "none" }

    /**
     * Gives a finished HDR file a capture date that every gallery reads the same way, whichever
     * route the photo came by. The date is the photo's own capture date, else its last edit date
     * (all that a Lightroom file carries), else [providerTime]: what the picker, the sharing app or
     * the gallery said. It is written with its time zone, because Android's media scanner throws
     * away a capture date without one once the file is more than a day newer than the date, and the
     * photo then sorts as taken today. The orientation label is left as it is. Repairs the MPF index
     * the new EXIF moved. Does nothing when the file already has both, or when no date is known.
     */
    fun stampCaptureDate(file: File, source: ByteArray, providerTime: Long?) {
        val exif = try {
            ExifInterface(ByteArrayInputStream(source))
        } catch (_: Exception) {
            null
        }
        fun valid(tag: String) = exif?.getAttribute(tag)?.takeIf { parseLocal(it) != null }
        val original = valid(ExifInterface.TAG_DATETIME_ORIGINAL)
        val originalOffset = exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        if (original != null && originalOffset != null) return
        val edited = valid(ExifInterface.TAG_DATETIME)
        val (text, offset) = when {
            original != null -> original to (originalOffset ?: offsetAt(original))
            edited != null -> edited to (exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME) ?: offsetAt(edited))
            providerTime != null && providerTime > 0 ->
                newFormat().format(java.util.Date(providerTime)).let { it to offsetAt(it) }
            else -> return
        }
        val values = mutableMapOf(
            ExifInterface.TAG_DATETIME_ORIGINAL to text,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to offset
        )
        if (valid(ExifInterface.TAG_DATETIME_DIGITIZED) == null) {
            values[ExifInterface.TAG_DATETIME_DIGITIZED] = text
            values[ExifInterface.TAG_OFFSET_TIME_DIGITIZED] = offset
        }
        applyMetadata(file, values, resetOrientation = false)
        JpegMpfFixer.fixMpfAndContainer(file)
    }

    private fun newFormat() = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    private fun parseLocal(text: String): Long? = try {
        newFormat().apply { isLenient = false }.parse(text.trim())?.time
    } catch (_: Exception) {
        null
    }

    /** The phone's time zone offset at the moment [local] names, as EXIF writes it: "+05:30". */
    private fun offsetAt(local: String): String {
        val time = parseLocal(local) ?: System.currentTimeMillis()
        val minutes = java.util.TimeZone.getDefault().getOffset(time) / 60_000
        val sign = if (minutes < 0) "-" else "+"
        val abs = kotlin.math.abs(minutes)
        return "%s%02d:%02d".format(Locale.US, sign, abs / 60, abs % 60)
    }

    private fun captureTimestamp(exif: ExifInterface): Long? {
        return try {
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

            if (dateStr != null) {
                val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                format.parse(dateStr)?.time
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes a Bitmap from the cached file and rotates it physically upright based on EXIF.
     * Photos with more than [maxPixels] are halved until they fit, so a 200 MP shot can't exhaust memory.
     */
    fun decodeUprightBitmap(sourceFile: File, maxPixels: Long = 26_000_000L): Bitmap? {
        return try {
            val exif = ExifInterface(sourceFile.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            // Inspect bounds first to check image dimensions
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)

            var sampleSize = 1
            while ((boundsOptions.outWidth / sampleSize).toLong() *
                (boundsOptions.outHeight / sampleSize) > maxPixels
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }

            val rawBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return null
            rotateBitmap(rawBitmap, orientation)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Throwable) {
            bitmap
        }
    }

    /**
     * Writes [metadata] into the target Ultra HDR file. With [resetOrientation] it is also marked
     * ORIENTATION_NORMAL, for a picture that was saved again upright. A file that kept its own
     * picture keeps its own orientation.
     */
    fun applyMetadata(targetFile: File, metadata: Map<String, String>, resetOrientation: Boolean = true) {
        try {
            val targetExif = ExifInterface(targetFile.absolutePath)

            for ((tag, value) in metadata) {
                try {
                    targetExif.setAttribute(tag, value)
                } catch (_: Exception) {}
            }

            // Upright orientation since pixels were physically rotated
            if (resetOrientation) try {
                targetExif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
            } catch (_: Exception) {}

            targetExif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Copies all camera, optics, and capture metadata from source file to target file,
     * then repairs APP2 MPF entry sizes/offsets shifted by the inserted EXIF segment.
     */
    fun copyExifMetadata(sourceFile: File, targetFile: File) {
        val metadata = extractMetadata(sourceFile)
        applyMetadata(targetFile, metadata)
        JpegMpfFixer.fixMpfAndContainer(targetFile)
    }
}
