package com.robertsullivan.dominoscorer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.robertsullivan.dominoscorer.databinding.ActivityCameraBinding
import com.robertsullivan.dominoscorer.utils.YuvToRgbConverter
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.Canny
import org.opencv.imgproc.Imgproc.circle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


typealias CircleListener = (bitmap: Bitmap, numCircles: Int) -> Unit

class CameraActivity : AppCompatActivity() {

    private class DominoAnalyzer(context: Context, private val listener: CircleListener) : ImageAnalysis.Analyzer {
        private lateinit var bitmapBuffer: Bitmap
        private var imageRotationDegrees: Int = 0
        val converter = YuvToRgbConverter(context)

        override fun analyze(imageProxy: ImageProxy) {

            if (!::bitmapBuffer.isInitialized) {
                // The image rotation and RGB image buffer are initialized only once
                // the analyzer has started running
                imageRotationDegrees = imageProxy.imageInfo.rotationDegrees
                bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
                )
            }

            // Convert the image to RGB and place it in our shared buffer
            imageProxy.use {
                imageProxy.image?.let {
                    converter.yuvToRgb(it, bitmapBuffer)
                }
            }
            /* convert bitmap to mat */
            val mat = Mat(
                bitmapBuffer.width, bitmapBuffer.height,
                CvType.CV_8UC1
            )
            val img = Mat(
                bitmapBuffer.width, bitmapBuffer.height,
                CvType.CV_8UC1
            )

            Utils.bitmapToMat(bitmapBuffer, img)

            // Accumulator value
            val dp = 1.0
            // minimum distance between the center coordinates of detected circles in pixels
            val minDistance = 20.0
            // param1 = gradient value used to handle edge detection
            // param2 = Accumulator threshold value for the
            // cv2.CV_HOUGH_GRADIENT method.
            // The smaller the threshold is, the more circles will be
            // detected (including false circles).
            // The larger the threshold is, the more circles will
            // potentially be returned.
            val param1 = 30.0
            val param2 = 18.0
            val minRadius = 10
            val maxRadius = 20
            val circles = Mat()
            Imgproc.cvtColor(img, img, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(img, img, Size(3.0, 3.0), 1.0)
            Canny(img, img, 300.0, 500.0)
            Imgproc.HoughCircles(img, circles, Imgproc.CV_HOUGH_GRADIENT,
                dp, minDistance, param1, param2, minRadius, maxRadius
            )
            Imgproc.cvtColor(img, img, Imgproc.COLOR_GRAY2BGR)

            /* get the number of circles detected */
            val numberOfCircles = if (circles.rows() == 0) 0 else circles.cols()

            /* draw the circles found on the image */
            for (i in 0 until numberOfCircles) {


                /* get the circle details, circleCoordinates[0, 1, 2] = (x,y,r)
                 * (x,y) are the coordinates of the circle's center
                 */
                val circleCoordinates = circles[0, i]
                try {
                    val x = circleCoordinates[0]
                    val y = circleCoordinates[1]
                    val center = Point(x, y)
                    val radius = circleCoordinates[2].toInt()

                    /* circle's outline */
                    circle(
                        img, center, radius, Scalar(
                            0.0,
                            255.0, 0.0
                        ), -1
                    )
                } catch (e: Exception) {}
            }

            Utils.matToBitmap(img, bitmapBuffer)
            listener(bitmapBuffer, numberOfCircles)

        }
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService

    private var dotCount: Int = 0



    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        OpenCVLoader.initDebug()
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Send count back
        binding.cameraCaptureButton.setOnClickListener {
            val data = Intent()
            data.putExtra("count", dotCount)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            @androidx.camera.core.ExperimentalGetImage
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor,
                        DominoAnalyzer(this) { bitmap, numCircles ->
                            runOnUiThread {
                                binding.imagePreview.setImageBitmap(bitmap)
                                dotCount = numCircles
                                binding.numberOfDots.text = dotCount.toString()
                            }

                        })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                @androidx.camera.core.ExperimentalGetImage
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

                binding.flashToggle.setOnClickListener {
                    if (camera.cameraInfo.torchState.value == 0) {
                        camera.cameraControl.enableTorch(true)
                    } else camera.cameraControl.enableTorch(false)
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}
