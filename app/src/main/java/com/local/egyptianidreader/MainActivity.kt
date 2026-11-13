package com.local.egyptianidreader

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.app.AlertDialog

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var nameInput: EditText
    private lateinit var idInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var rawInput: EditText
    private var bitmap: Bitmap? = null
    private var pendingPhotoFile: File? = null
    private var cardBitmap: Bitmap? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            contentResolver.openInputStream(uri)?.use { bitmap = BitmapFactory.decodeStream(it) }
            preview.setImageBitmap(bitmap)
            status.text = if (bitmap != null) "تم تحميل الصورة الأصلية. اضغط استخراج البيانات." else "تعذر قراءة الصورة."
        } catch (e: Exception) {
            status.text = "تعذر فتح الصورة: ${e.localizedMessage}"
        }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingPhotoFile
        if (result.resultCode == RESULT_OK && file != null && file.exists() && file.length() > 0) {
            bitmap = BitmapFactory.decodeFile(file.absolutePath)
            preview.setImageBitmap(bitmap)
            status.text = if (bitmap != null) "تم التقاط الصورة الأصلية عالية الدقة." else "تعذر تحميل الصورة الملتقطة."
        } else {
            status.text = "لم يتم التقاط صورة."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 28)
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "قارئ بيانات البطاقة المصرية"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 65, 100))
            setPadding(0, 8, 0, 18)
        })
        status = TextView(this).apply {
            text = "صوّر البطاقة كاملة وبوضوح، واجعلها مستقيمة داخل الإطار."
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }
        root.addView(status)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 210))
        addButton(root, "التقاط صورة عالية الدقة") { captureFullResolution() }
        addButton(root, "اختيار صورة من الهاتف") { pickImage.launch("image/*") }
        addButton(root, "استخراج النص والبيانات") { recognize() }
        nameInput = addField(root, "الاسم")
        idInput = addField(root, "الرقم القومي")
        addressInput = addField(root, "العنوان")
        rawInput = addField(root, "النص المستخرج — راجع وصحح النتائج").apply {
            minLines = 5
            gravity = Gravity.TOP or Gravity.RIGHT
        }
        addButton(root, "حفظ البيانات محليًا") { saveRecord() }
        root.addView(TextView(this).apply {
            text = "تتم القراءة محليًا على الهاتف. قد تختلف النتائج حسب وضوح الصورة؛ راجع البيانات يدويًا قبل الحفظ."
            textSize = 12f
            setTextColor(Color.rgb(100, 70, 20))
            setPadding(8, 18, 8, 8)
        })
    }

    private fun captureFullResolution() {
        try {
            val file = File(cacheDir, "id_card_${System.currentTimeMillis()}.jpg")
            pendingPhotoFile = file
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            takePhoto.launch(intent)
        } catch (e: Exception) {
            status.text = "تعذر تشغيل الكاميرا: ${e.localizedMessage}"
        }
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addField(parent: LinearLayout, label: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(0, 12, 0, 4)
        })
        return EditText(this).apply {
            textSize = 16f
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.WHITE)
        }.also { parent.addView(it, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun cropRegion(source: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x1 = (source.width * left).toInt().coerceIn(0, source.width - 1)
        val y1 = (source.height * top).toInt().coerceIn(0, source.height - 1)
        val x2 = (source.width * right).toInt().coerceIn(x1 + 1, source.width)
        val y2 = (source.height * bottom).toInt().coerceIn(y1 + 1, source.height)
        val crop = Bitmap.createBitmap(source, x1, y1, x2 - x1, y2 - y1)
        return enhanceForOcr(crop)
    }

    // Grayscale, contrast stretch, and upscale each field separately.
    private fun enhanceForOcr(input: Bitmap): Bitmap {
        val targetWidth = max(input.width * 2, 1000).coerceAtMost(2400)
        val targetHeight = (input.height.toFloat() * targetWidth / input.width).toInt()
        val scaled = Bitmap.createScaledBitmap(input, targetWidth, targetHeight, true)
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        var minGray = 255
        var maxGray = 0
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val v = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            gray[i] = v
            if (v < minGray) minGray = v
            if (v > maxGray) maxGray = v
        }
        val range = max(1, maxGray - minGray)
        for (i in pixels.indices) {
            val v = (((gray[i] - minGray) * 255) / range).coerceIn(0, 255)
            pixels[i] = Color.rgb(v, v, v)
        }
        output.setPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (scaled !== input) scaled.recycle()
        input.recycle()
        return output
    }

    private fun recognize() {
        val source = bitmap
        if (source == null) {
            status.text = "اختر صورة البطاقة أولًا."
            return
        }
        if (source.width < 500 || source.height < 300) {
            status.text = "الصورة صغيرة جدًا. استخدم صورة البطاقة الأصلية عالية الدقة."
            return
        }
        status.text = "جارٍ تحسين الصورة وقراءة مناطق البطاقة..."
        Thread {
            var api: TessBaseAPI? = null
            var nameCrop: Bitmap? = null
            var addressCrop: Bitmap? = null
            var idCrop: Bitmap? = null
            try {
                val base = File(filesDir, "tesseract")
                val data = File(base, "tessdata")
                data.mkdirs()
                copyAsset("tessdata/ara.traineddata", File(data, "ara.traineddata"))
                copyAsset("tessdata/eng.traineddata", File(data, "eng.traineddata"))

                // Proportional regions for a full landscape image of the card.
                nameCrop = cropRegion(source, 0.40f, 0.28f, 0.99f, 0.53f)
                addressCrop = cropRegion(source, 0.38f, 0.49f, 0.99f, 0.78f)
                idCrop = cropRegion(source, 0.40f, 0.77f, 0.99f, 0.96f)

                api = TessBaseAPI()
                if (!api.init(base.absolutePath, "ara+eng")) {
                    throw IllegalStateException("تعذر تشغيل بيانات اللغة العربية والإنجليزية")
                }

                val nameText = readArabicRegion(api, nameCrop)
                val addressText = readArabicRegion(api, addressCrop)
                val idText = readNumericRegion(api, idCrop)
                val idMatch = Regex("[0-9]{14}").find(normalizeDigits(idText))?.value
                val fullText = buildString {
                    appendLine("الاسم: ${nameText.ifBlank { "لم يتم التعرف عليه" }}")
                    appendLine("العنوان: ${addressText.ifBlank { "لم يتم التعرف عليه" }}")
                    append("الرقم القومي: ${idMatch ?: "لم يتم العثور على 14 رقمًا"}")
                }

                runOnUiThread {
                    if (nameText.isNotBlank()) nameInput.setText(cleanArabic(nameText))
                    if (addressText.isNotBlank()) addressInput.setText(cleanArabic(addressText))
                    if (idMatch != null) idInput.setText(idMatch)
                    rawInput.setText(fullText)
                    val missing = mutableListOf<String>()
                    if (nameText.isBlank()) missing.add("الاسم")
                    if (addressText.isBlank()) missing.add("العنوان")
                    if (idMatch == null) missing.add("الرقم القومي")
                    status.text = if (missing.isEmpty()) {
                        "انتهت القراءة المبدئية. راجع الحقول الثلاثة قبل الحفظ."
                    } else {
                        "لم تُقرأ بعض البيانات بدقة: ${missing.joinToString("، ")}. جرّب صورة أوضح أو صححها يدويًا."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطأ OCR: ${e.localizedMessage ?: "غير معروف"}" }
            } finally {
                api?.recycle()
                nameCrop?.recycle()
                addressCrop?.recycle()
                idCrop?.recycle()
            }
        }.start()
    }

    private fun readArabicRegion(api: TessBaseAPI, image: Bitmap): String {
        api.setVariable("tessedit_char_whitelist", "")
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        api.setImage(image)
        return api.getUTF8Text()?.trim().orEmpty()
    }

    private fun readNumericRegion(api: TessBaseAPI, image: Bitmap): String {
        api.setVariable("tessedit_char_whitelist", "0123456789٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹")
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
        api.setImage(image)
        val text = api.getUTF8Text()?.trim().orEmpty()
        api.setVariable("tessedit_char_whitelist", "")
        return text
    }

    private fun cleanArabic(value: String): String =
        value.replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s{2,}"), " ").trim()

    private fun copyAsset(path: String, destination: File) {
        if (destination.exists() && destination.length() > 1000) return
        assets.open(path).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
    }

    private fun normalizeDigits(value: String): String {
        val ar = "٠١٢٣٤٥٦٧٨٩"
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        return value.map { c ->
            when {
                c in ar -> ('0'.code + ar.indexOf(c)).toChar()
                c in fa -> ('0'.code + fa.indexOf(c)).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun saveRecord() {
        val id = normalizeDigits(idInput.text.toString()).filter { it.isDigit() }
        if (id.isNotEmpty() && id.length != 14) {
            status.text = "الرقم القومي يجب أن يتكون من 14 رقمًا. صححه قبل الحفظ."
            return
        }
        val prefs = getSharedPreferences("records", MODE_PRIVATE)
        val n = prefs.getInt("count", 0) + 1
        val value = listOf(
            nameInput.text.toString(),
            id,
            addressInput.text.toString(),
            rawInput.text.toString()
        ).joinToString("\\n")
        prefs.edit().putInt("count", n).putString("record_$n", value).apply()
        status.text = "تم الحفظ محليًا على الهاتف."
    }
}
