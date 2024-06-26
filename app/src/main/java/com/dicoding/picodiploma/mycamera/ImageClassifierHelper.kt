package com.dicoding.picodiploma.mycamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.dicoding.picodiploma.mycamera.ml.Model
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.task.vision.classifier.Classifications
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageClassifierHelper(
    val context: Context,
    val classifierListener: ClassifierListener?
) {

    private var imageClassifier: Model? = null
    private val inputImageWidth = 128
    private val inputImageHeight = 128
    private val inputImageChannels = 1

    init {
        setupImageClassifier()
    }

    private fun setupImageClassifier() {
        try {
            imageClassifier = Model.newInstance(context)
        } catch (e: Exception) {
            classifierListener?.onError("Failed to load model: ${e.message}")
            Log.e(TAG, e.message.toString())
        }
    }

    fun classifyImage(image: ImageProxy) {
        if (imageClassifier == null) {
            setupImageClassifier()
        }

        val bitmap = toBitmap(image)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)
        val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, inputImageWidth, inputImageHeight, inputImageChannels), DataType.FLOAT32)
        inputFeature0.loadBuffer(byteBuffer)

        val inferenceTime = SystemClock.uptimeMillis()
        val outputs = imageClassifier?.process(inputFeature0)
        val inferenceDuration = SystemClock.uptimeMillis() - inferenceTime

        val outputFeature0 = outputs?.outputFeature0AsTensorBuffer
        val results = parseResults(outputFeature0)
        classifierListener?.onResults(results, inferenceDuration)

        image.close()
    }

    private fun toBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
//        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
//        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
//        return bitmap

        val yuvImage = YuvImage(bytes, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val yuvBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(yuvBytes, 0, yuvBytes.size)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputImageWidth * inputImageHeight * inputImageChannels * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputImageWidth * inputImageHeight)

        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixelValue in intValues) {
            // Normalize pixel value to [0, 1] range and add to buffer
            val normalizedPixelValue = (pixelValue and 0xFF) / 255.0f
            byteBuffer.putFloat(normalizedPixelValue)
        }
        return byteBuffer
    }

    private fun parseResults(outputBuffer: TensorBuffer?): List<Classifications> {
        val scores = outputBuffer?.floatArray ?: return emptyList()
        val classifications = mutableListOf<Classifications>()
        for (i in scores.indices) {
            classifications.add(Classifications("Label $i", scores[i]))
        }
        return classifications.sortedByDescending { it.score }
    }

    interface ClassifierListener {
        fun onError(error: String)
        fun onResults(results: List<Classifications>, inferenceTime: Long)
    }

    data class Classifications(
        val label: String,
        val score: Float
    )

    companion object {
        private const val TAG = "ImageClassifierHelper"
    }
}