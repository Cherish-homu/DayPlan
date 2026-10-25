package com.jsy.dayplan.ai.attach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** 预处理后的上传图片。 */
data class PreparedImage(
    val sourceId: String,
    val dataUrl: String,
    val encodedBytes: Int,
    val width: Int,
    val height: Int,
    val previewPath: String?,
)

/**
 * 图片上传预处理：先查尺寸再采样（限制解码像素）、按 EXIF 旋转、
 * 重编码为 JPEG（顺带移除 EXIF 位置等元数据）、限制编码后大小。
 */
object ImagePrep {

    /** 解码像素上限 1600 万；编码后单图上限见 AttachLimits。 */
    fun prepare(
        file: File,
        kind: AttachKind,
        sourceId: String,
        maxEncodedBytes: Long = AttachLimits.IMAGE_ENCODED_MAX_BYTES,
        savePreviewTo: File? = null,
    ): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AttachmentException("「${file.name}」无法解码为图片（文件可能已损坏）")
        }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels > AttachLimits.IMAGE_MAX_PIXELS * 4) {
            throw AttachmentException(
                "「${file.name}」像素过大（${bounds.outWidth}×${bounds.outHeight}），请裁剪或缩小后再发送",
            )
        }

        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) >
            AttachLimits.IMAGE_MAX_PIXELS
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw AttachmentException("「${file.name}」无法解码为图片")

        // EXIF 旋转校正（用原文件读方向）
        val rotation = readExifRotation(file)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }

        // 迭代压缩到预算内：先降质量，再缩尺寸
        var quality = 87
        var scale = 1f
        var bytes: ByteArray
        while (true) {
            val target = if (scale < 1f) {
                val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else bitmap
            val bos = ByteArrayOutputStream()
            target.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            if (bytes.size.toLong() <= maxEncodedBytes || (quality <= 55 && scale <= 0.4f)) {
                if (scale < 1f && target != bitmap) bitmap.recycle()
                break
            }
            if (target != bitmap) target.recycle()
            quality -= 12
            if (quality < 55) {
                quality = 70
                scale *= 0.75f
            }
        }
        val w = bitmap.width
        val h = bitmap.height
        savePreviewTo?.let { p ->
            p.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        }
        bitmap.recycle()

        val b64 = Base64.getEncoder().encodeToString(bytes)
        return PreparedImage(
            sourceId = sourceId,
            dataUrl = "data:image/jpeg;base64,$b64",
            encodedBytes = bytes.size,
            width = w,
            height = h,
            previewPath = savePreviewTo?.absolutePath,
        )
    }

    private fun readExifRotation(file: File): Int = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}
