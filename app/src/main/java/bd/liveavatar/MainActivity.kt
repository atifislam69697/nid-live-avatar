package bd.liveavatar

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var btnGenerate: Button

    private var faceBitmap: Bitmap? = null          // cropped face, full crop with margin
    private var leftEye: PointF? = null
    private var rightEye: PointF? = null
    private var faceColor: Int = Color.rgb(200, 170, 150)

    data class PointF(val x: Float, val y: Float)

    companion object {
        private const val W = 720
        private const val H = 1280
        private const val FPS = 30
        private const val DURATION_S = 8
        private const val FRAMES = FPS * DURATION_S
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        btnGenerate = findViewById(R.id.btnGenerate)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImage()
        }
        btnGenerate.setOnClickListener { generate() }
    }

    private fun pickImage() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(android.content.Intent.createChooser(intent, "ছবি বাছুন"), 1001)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "মুখ খোঁজা হচ্ছে..."
        lifecycleScope.launch {
            try {
                detectFace(uri)
                btnGenerate.isEnabled = true
                status.text = "মুখ পাওয়া গেছে। এখন ভিডিও বানাতে পারেন।"
            } catch (e: Exception) {
                status.text = "মুখ পাওয়া যায়নি: ${e.message}"
            }
        }
    }

    private fun detectFace(uri: Uri) = lifecycleScope.launch(Dispatchers.Default) {
        val bmp = loadBitmap(uri)
        val image = InputImage.fromBitmap(bmp, 0)
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
        val detector = FaceDetection.getClient(options)
        val faces = detector.process(image).await()
        val face = faces.firstOrNull() ?: throw Exception("কোনো মুখ পাওয়া যায়নি")
        val box = face.boundingBox

        // crop with generous margin, clamped
        val mx = box.width() * 0.55f
        val myTop = box.height() * 0.85f
        val myBottom = box.height() * 1.15f
        val left = (box.left - mx).toInt().coerceAtLeast(0)
        val top = (box.top - myTop).toInt().coerceAtLeast(0)
        val right = (box.right + mx).toInt().coerceAtMost(bmp.width)
        val bottom = (box.bottom + myBottom).toInt().coerceAtMost(bmp.height)
        if (right - left < 50 || bottom - top < 50) throw Exception("মুখ খুব ছোট")

        var crop = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
        // scale so the crop covers 720x1280 nicely (fit height, slight zoom-out)
        val scale = H.toFloat() / crop.height * 1.05f
        val m = Matrix().apply { setScale(scale, scale) }
        crop = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, m, true)

        faceBitmap = crop

        // eye positions relative to crop, mapped through the same scale
        fun map(p: com.google.mlkit.vision.face.FaceLandmark?): PointF? {
            if (p == null) return null
            return PointF((p.position.x - left) * scale, (p.position.y - top) * scale)
        }
        leftEye = map(face.getLandmark(FaceLandmark.LEFT_EYE))
        rightEye = map(face.getLandmark(FaceLandmark.RIGHT_EYE))

        // skin color sampled above the eyes (forehead)
        val fx = ((leftEye?.x ?: rightEye?.x ?: crop.width / 2f))
        val fy = ((leftEye?.y ?: rightEye?.y ?: crop.height / 3f)) - crop.height * 0.10f
        val px = fx.toInt().coerceIn(0, crop.width - 1)
        val py = fy.toInt().coerceIn(0, crop.height - 1)
        faceColor = crop.getPixel(px, py)

        withContext(Dispatchers.Main) {
            preview.setImageBitmap(crop)
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val exif = bytes.inputStream().use { ExifInterface(it) }
        val rot = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f; 8 -> -90f; 3 -> 180f; else -> 0f
        }
        if (rot != 0f) {
            val m = Matrix().apply { setRotate(rot) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    private fun generate() {
        val face = faceBitmap ?: return
        btnGenerate.isEnabled = false
        status.text = "ভিডিও তৈরি হচ্ছে..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uri = renderVideo(face)
                withContext(Dispatchers.Main) {
                    status.text = "ভিডিও সেভ হয়েছে (Movies ফোল্ডারে): nid_live_avatar.mp4"
                    Toast.makeText(this@MainActivity, "ভিডিও তৈরি সম্পন্ন", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "ব্যর্থ: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { btnGenerate.isEnabled = true }
            }
        }
    }

    private fun renderVideo(face: Bitmap): Uri {
        val tmp = File(cacheDir, "nid_live_avatar.mp4")
        if (tmp.exists()) tmp.delete()

        val encoder = android.media.MediaCodec.createEncoderByType("video/avc")
        val format = android.media.MediaFormat.createVideoFormat("video/avc", W, H).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        var muxerStarted = false
        var trackIndex = -1
        val muxer = android.media.MediaMuxer(tmp.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        encoder.setCallback(object : android.media.MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: android.media.MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: android.media.MediaCodec, index: Int,
                                               info: android.media.MediaCodec.BufferInfo) {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && muxerStarted &&
                    info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    muxer.writeSampleData(trackIndex, buf, info)
                }
                codec.releaseOutputBuffer(index, false)
            }
            override fun onError(codec: android.media.MediaCodec, e: android.media.MediaCodec.CodecException) {}
            override fun onOutputFormatChanged(codec: android.media.MediaCodec, fmt: android.media.MediaFormat) {
                trackIndex = muxer.addTrack(fmt)
                muxer.start()
                muxerStarted = true
            }
        }, null)

        val canvas = Canvas(surface)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faceColor }
        val lashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darken(faceColor, 0.55f); strokeWidth = 4f
        }

        val eyeW = face.width * 0.10f
        val baseEyeH = face.height * 0.022f

        // blink schedule: pairs of (startFrame, durationFrames)
        val blinks = listOf(
            (FPS * 1.5f).toInt() to (FPS * 0.22f).toInt(),
            (FPS * 4.8f).toInt() to (FPS * 0.25f).toInt(),
            (FPS * 6.9f).toInt() to (FPS * 0.18f).toInt()
        )

        val matrix = Matrix()
        val renderStartNs = System.nanoTime()
        for (i in 0 until FRAMES) {
            val t = i / FRAMES.toFloat()
            matrix.reset()

            // gentle head motion: slight rotation + scale breathing + drift
            val rot = 1.6f * Math.sin(2 * Math.PI * (t * 1.1)).toFloat()
            val sc = 1.0f + 0.012f * Math.sin(2 * Math.PI * (t * 0.8)).toFloat()
            val dx = 10f * Math.sin(2 * Math.PI * (t * 0.55)).toFloat()
            val dy = 7f * Math.sin(2 * Math.PI * (t * 0.35) + 1.2f).toFloat()

            val cx = W / 2f
            val cy = H / 2f
            matrix.postTranslate(-face.width / 2f, -face.height / 2f)
            matrix.postScale(sc, sc)
            matrix.postRotate(rot)
            matrix.postTranslate(cx + dx, cy + dy)

            canvas.drawColor(Color.rgb(24, 24, 24))
            canvas.save()
            canvas.concat(matrix)
            canvas.drawBitmap(face, 0f, 0f, paint)

            // blink overlay (drawn in same transformed space)
            var blinkAmount = 0f // 0 = open, 1 = closed
            for ((start, dur) in blinks) {
                if (dur <= 0) continue
                val f = i - start
                if (f in 0 until dur) {
                    val phase = f.toFloat() / dur
                    blinkAmount = Math.sin(phase * Math.PI).toFloat()
                    break
                }
            }
            if (blinkAmount > 0.05f) {
                for (eye in listOfNotNull(leftEye, rightEye)) {
                    val h = baseEyeH * (1f + 4f * blinkAmount)
                    canvas.drawOval(RectF(eye.x - eyeW / 2, eye.y - h, eye.x + eyeW / 2, eye.y + h), skinPaint)
                    canvas.drawLine(eye.x - eyeW / 2, eye.y + h * 0.6f,
                        eye.x + eyeW / 2, eye.y + h * 0.6f, lashPaint)
                }
            }
            canvas.restore()

            // pace to real time: Surface-input encoders stamp frames at render time
            val expectNanos = i * 1_000_000_000L / FPS
            val target = renderStartNs + expectNanos
            while (System.nanoTime() < target) Thread.sleep(2)
        }

        encoder.signalEndOfInputStream()
        // give the async callback time to drain buffered frames before stop
        Thread.sleep(1000)

        surface.release()
        encoder.stop()
        encoder.release()
        if (muxerStarted) {
            muxer.stop()
            muxer.release()
        }

        // copy into MediaStore (Movies)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "nid_live_avatar.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NIDLiveAvatar")
        }
        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        contentResolver.openOutputStream(outUri)!!.use { out ->
            tmp.inputStream().use { it.copyTo(out) }
        }
        tmp.delete()
        return outUri
    }

    private fun darken(c: Int, f: Float): Int {
        return Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }
}
