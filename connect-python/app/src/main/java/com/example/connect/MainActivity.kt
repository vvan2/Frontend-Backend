package com.example.connect

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var cameraExecutor: ExecutorService
    private var isRecording = false
    private var activeRecording: Recording? = null
    private lateinit var previewView: PreviewView
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        val photoButton: Button = findViewById(R.id.photoButton)
        val videoButton: Button = findViewById(R.id.videoButton)

        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()

        // Retrofit 설정
        val retrofit = Retrofit.Builder()
            .baseUrl("http://<서버_IP>:5000/") // 서버 IP 주소로 수정
            .addConverterFactory(GsonConverterFactory.create())
            .client(OkHttpClient())
            .build()

        apiService = retrofit.create(ApiService::class.java)

        // 사진 촬영 버튼 클릭 이벤트
        photoButton.setOnClickListener {
            takePhoto()
        }

        // 동영상 촬영 버튼 클릭 이벤트
        videoButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (e: Exception) {
                Log.e("CameraX", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val photoFile = File(
            externalMediaDirs.firstOrNull(),
            "photo-${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d("CameraX", "Photo saved at: ${photoFile.absolutePath}")
                    sendPoseImageToServer(photoFile)  // 서버로 이미지 전송
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed", exception)
                }
            })
    }

    private fun sendPoseImageToServer(photoFile: File) {
        // 사진을 Base64로 인코딩
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val encodedImage = Base64.encodeToString(byteArray, Base64.DEFAULT)

        // PoseRequest 객체 생성
        val poseRequest = PoseRequest(encodedImage)

        // 서버로 이미지 전송
        apiService.predictPose(poseRequest).enqueue(object : Callback<PoseResponse> {
            override fun onResponse(call: Call<PoseResponse>, response: Response<PoseResponse>) {
                if (response.isSuccessful) {
                    val pose = response.body()?.pose
                    Log.d("Pose Prediction", pose ?: "No pose detected")
                    Toast.makeText(this@MainActivity, "Pose: $pose", Toast.LENGTH_LONG).show()
                } else {
                    Log.e("Pose Prediction", "Failed to get response")
                    Toast.makeText(this@MainActivity, "Failed to get pose prediction", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<PoseResponse>, t: Throwable) {
                Log.e("Pose Prediction", "Network error: ${t.message}")
                Toast.makeText(this@MainActivity, "Network error: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun startRecording() {
        val videoFile = File(
            externalMediaDirs.firstOrNull(),
            "video-${System.currentTimeMillis()}.mp4"
        )

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        activeRecording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d("CameraX", "Recording started")
                        isRecording = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        Log.d("CameraX", "Recording saved at: ${videoFile.absolutePath}")
                        isRecording = false
                    }
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        Log.d("CameraX", "Recording stopped")
        isRecording = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
