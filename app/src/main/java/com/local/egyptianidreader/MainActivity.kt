package com.local.egyptianidreader

import androidx.activity.ComponentActivity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var nameInput: EditText
    private lateinit var idInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var rawInput: EditText
    private var bitmap: Bitmap? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            contentResolver.openInputStream(uri)?.use { bitmap = BitmapFactory.decodeStream(it) }
            preview.setImageBitmap(bitmap)
            status.text = "تم تحميل الصورة. اضغط استخراج البيانات."
        } catch (e: Exception) { status.text = "تعذر فتح الصورة: ${e.localizedMessage}" }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val photo = result.data?.extras?.get("data") as? Bitmap
            if (photo != null) {
                bitmap = photo
                preview.setImageBitmap(photo)
                status.text = "تم التقاط صورة معاينة. للحصول على دقة أفضل اختر صورة كاملة من المعرض."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }

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
            text = "قارئ بيانات البطاقة المصرية"; textSize = 23f
            gravity = Gravity.CENTER; setTextColor(Color.rgb(20,65,100))
            setPadding(0,8,0,18)
        })
        status = TextView(this).apply {
            text = "اختر صورة واضحة للبطاقة. المعالجة تتم على الهاتف."
            gravity = Gravity.CENTER; setPadding(0,4,0,12)
        }
        root.addView(status)
        preview = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 210))
        addButton(root, "التقاط صورة") { takePhoto.launch(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)) }
        addButton(root, "اختيار صورة من الهاتف") { pickImage.launch("image/*") }
        addButton(root, "استخراج النص والبيانات") { recognize() }
        nameInput = addField(root, "الاسم")
        idInput = addField(root, "الرقم القومي")
        addressInput = addField(root, "العنوان")
        rawInput = addField(root, "النص المستخرج — راجع وصحح النتائج").apply { minLines = 4; gravity = Gravity.TOP or Gravity.RIGHT }
        addButton(root, "حفظ البيانات محليًا") { saveRecord() }
        root.addView(TextView(this).apply {
            text = "تنبيه: قد يخطئ OCR في الأسماء والأرقام. راجع كل حقل يدويًا. لا يتم رفع الصور أو البيانات إلى خادم."
            textSize = 12f; setTextColor(Color.rgb(100,70,20)); setPadding(8,18,8,8)
        })
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply { text = label; setOnClickListener { action() } },
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun addField(parent: LinearLayout, label: String): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 15f; setPadding(0,12,0,4) })
        return EditText(this).apply { textSize = 16f; setPadding(12,8,12,8); setBackgroundColor(Color.WHITE) }
            .also { parent.addView(it, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun recognize() {
        val source = bitmap
        if (source == null) { status.text = "اختر صورة البطاقة أولًا."; return }
        status.text = "جارٍ تجهيز محرك القراءة..."
        Thread {
            var api: TessBaseAPI? = null
            try {
                val base = File(filesDir, "tesseract")
                val data = File(base, "tessdata")
                data.mkdirs()
                copyAsset("tessdata/ara.traineddata", File(data, "ara.traineddata"))
                copyAsset("tessdata/eng.traineddata", File(data, "eng.traineddata"))
                api = TessBaseAPI()
                if (!api.init(base.absolutePath, "ara+eng")) throw IllegalStateException("تعذر تشغيل بيانات اللغة")
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                api.setImage(source)
                val result = api.UTF8Text ?: ""
                runOnUiThread {
                    rawInput.setText(result.trim())
                    val id = Regex("(?<![0-9])[0-9]{14}(?![0-9])").find(normalizeDigits(result))?.value
                    if (id != null) idInput.setText(id)
                    status.text = if (result.isBlank()) "لم يتم التعرف على نص واضح. جرّب صورة أوضح." else "اكتمل الاستخراج. راجع وصحح البيانات."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطأ OCR: ${e.localizedMessage ?: "غير معروف"}" }
            } finally { api?.recycle() }
        }.start()
    }

    private fun copyAsset(path: String, destination: File) {
        if (destination.exists() && destination.length() > 1000) return
        assets.open(path).use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
    }
    private fun normalizeDigits(value: String): String {
        val ar = "٠١٢٣٤٥٦٧٨٩"; val fa = "۰۱۲۳۴۵۶۷۸۹"
        return value.map { c ->
            when {
                c in ar -> ('0'.code + ar.indexOf(c)).toChar()
                c in fa -> ('0'.code + fa.indexOf(c)).toChar()
                else -> c
            }
        }.joinToString("")
    }
    private fun saveRecord() {
        val prefs = getSharedPreferences("records", MODE_PRIVATE)
        val n = prefs.getInt("count", 0) + 1
        val value = listOf(nameInput.text.toString(), idInput.text.toString(), addressInput.text.toString(), rawInput.text.toString()).joinToString("\n")
        prefs.edit().putInt("count", n).putString("record_$n", value).apply()
        status.text = "تم الحفظ محليًا على الهاتف."
    }
}
