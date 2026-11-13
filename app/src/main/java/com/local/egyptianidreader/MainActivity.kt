
package com.local.egyptianidreader

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

import com.googlecode.tesseract.android.TessBaseAPI

import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var nameInput: EditText
    private lateinit var idInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var rawInput: EditText

    private var bitmap: Bitmap? = null
    private var cardBitmap: Bitmap? = null
    private var pendingPhotoFile: File? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->

            if (uri != null) {
                try {
                    contentResolver.openInputStream(uri)?.use {
                        bitmap = BitmapFactory.decodeStream(it)
                    }

                    cardBitmap?.recycle()
                    cardBitmap = null

                    preview.setImageBitmap(bitmap)

                    status.text =
                        if (bitmap != null)
                            "تم تحميل الصورة. حدد حدود البطاقة أولاً."
                        else
                            "تعذر قراءة الصورة."

                } catch (e: Exception) {
                    status.text = "تعذر فتح الصورة: ${e.localizedMessage}"
                }
            }
        }

    private val takePhoto =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            val file = pendingPhotoFile

            if (
                result.resultCode == RESULT_OK &&
                file != null &&
                file.exists() &&
                file.length() > 0
            ) {

                try {
                    bitmap = decodeOrientedPhoto(file)

                    cardBitmap?.recycle()
                    cardBitmap = null

                    preview.setImageBitmap(bitmap)

                    status.text =
                        if (bitmap != null)
                            "تم التقاط الصورة. حدد حدود البطاقة قبل الاستخراج."
                        else
                            "تعذر تحميل الصورة."

                } catch (e: Exception) {
                    status.text = "خطأ في معالجة الصورة: ${e.localizedMessage}"
                }

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
            text = "صوّر البطاقة كاملة وبوضوح، واجعلها مستقيمة داخل الصورة."
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, 4, 0, 12)
        }

        root.addView(status)

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            preview,
            LinearLayout.LayoutParams(-1, 230)
        )

        addButton(root, "التقاط صورة عالية الدقة") {
            captureFullResolution()
        }

        addButton(root, "اختيار صورة من الهاتف") {
            pickImage.launch("image/*")
        }

        addButton(root, "تحديد حدود البطاقة") {
            showCardCropDialog()
        }

        addButton(root, "إلغاء القص والعودة للصورة الأصلية") {
            cardBitmap?.recycle()
            cardBitmap = null
            preview.setImageBitmap(bitmap)
            status.text = "تم إلغاء القص."
        }

        addButton(root, "استخراج النص والبيانات") {
            recognize()
        }

        nameInput = addField(root, "الاسم")
        idInput = addField(root, "الرقم القومي")
        addressInput = addField(root, "العنوان")

        rawInput = addField(root, "النص المستخرج — راجع وصحح النتائج").apply {
            minLines = 5
            gravity = Gravity.TOP or Gravity.RIGHT
        }

        addButton(root, "حفظ البيانات محليًا") {
            saveRecord()
        }

        root.addView(TextView(this).apply {
            text = "تتم القراءة محليًا على الهاتف. راجع البيانات يدويًا قبل الحفظ."
            textSize = 12f
            setTextColor(Color.rgb(100, 70, 20))
            setPadding(8, 18, 8, 8)
        })
    }

    private fun captureFullResolution() {

        try {
            val file = File(
                cacheDir,
                "id_card_${System.currentTimeMillis()}.jpg"
            )

            pendingPhotoFile = file

            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(
                android.provider.MediaStore.ACTION_IMAGE_CAPTURE
            ).apply {
                putExtra(
                    android.provider.MediaStore.EXTRA_OUTPUT,
                    uri
                )

                addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            takePhoto.launch(intent)

        } catch (e: Exception) {
            status.text = "تعذر تشغيل الكاميرا: ${e.localizedMessage}"
        }
    }

    private fun decodeOrientedPhoto(file: File): Bitmap? {

        val decoded = BitmapFactory.decodeFile(file.absolutePath)
            ?: return null

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()

        when (orientation) {

            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.postRotate(90f)

            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.postRotate(180f)

            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.postRotate(270f)

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.preScale(-1f, 1f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.preScale(1f, -1f)
        }

        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return decoded
        }

        val corrected = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            matrix,
            true
        )

        if (corrected !== decoded) {
            decoded.recycle()
        }

        return corrected
    }

    private fun showCardCropDialog() {

        val source = bitmap

        if (source == null) {
            status.text = "التقط صورة البطاقة أولاً."
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
        }

        val labels = listOf(
            "الحد الأيسر",
            "الحد العلوي",
            "الحد الأيمن",
            "الحد السفلي"
        )

        val defaults = listOf(15, 12, 85, 88)
        val bars = mutableListOf<SeekBar>()

        for (i in labels.indices) {

            panel.addView(TextView(this).apply {
                text = labels[i]
                textSize = 15f
            })

            val bar = SeekBar(this).apply {
                max = 95
                progress = defaults[i]
            }

            bars.add(bar)
            panel.addView(bar)
        }

        AlertDialog.Builder(this)
            .setTitle("تحديد حدود البطاقة")
            .setMessage(
                "اضبط المؤشرات الأربعة لتشمل البطاقة فقط، " +
                "واستبعد الخلفية والقماش."
            )
            .setView(panel)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("اعتماد القص") { _, _ ->

                val left = bars[0].progress / 100f
                val top = bars[1].progress / 100f
                val right = bars[2].progress / 100f
                val bottom = bars[3].progress / 100f

                if (
                    right <= left ||
                    bottom <= top ||
                    right - left < 0.20f ||
                    bottom - top < 0.20f
                ) {
                    status.text = "حدود القص غير صحيحة."
                    return@setPositiveButton
                }

                val x = (source.width * left).toInt()
                val y = (source.height * top).toInt()

                val width = (
                    source.width * (right - left)
                ).toInt().coerceAtMost(source.width - x)

                val height = (
                    source.height * (bottom - top)
                ).toInt().coerceAtMost(source.height - y)

                if (width <= 0 || height <= 0) {
                    status.text = "تعذر تحديد مساحة البطاقة."
                    return@setPositiveButton
                }

                cardBitmap?.recycle()

                cardBitmap = Bitmap.createBitmap(
                    source,
                    x,
                    y,
                    width,
                    height
                )

                preview.setImageBitmap(cardBitmap)

                status.text =
                    "تم تحديد البطاقة. راجع الصورة ثم اضغط استخراج البيانات."
            }
            .show()
    }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {

        parent.addView(
            Button(this).apply {
                text = label
                setOnClickListener {
                    action()
                }
            },
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addField(
        parent: LinearLayout,
        label: String
    ): EditText {

        parent.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(0, 12, 0, 4)
        })

        return EditText(this).apply {
            textSize = 16f
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.WHITE)
        }.also {
            parent.addView(
                it,
                LinearLayout.LayoutParams(
                    -1,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun cropRegion(
        source: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Bitmap {

        val x1 = (source.width * left)
            .toInt().coerceIn(0, source.width - 1)

        val y1 = (source.height * top)
            .toInt().coerceIn(0, source.height - 1)

        val x2 = (source.width * right)
            .toInt().coerceIn(x1 + 1, source.width)

        val y2 = (source.height * bottom)
            .toInt().coerceIn(y1 + 1, source.height)

        val crop = Bitmap.createBitmap(
            source,
            x1,
            y1,
            x2 - x1,
            y2 - y1
        )

        return enhanceForOcr(crop)
    }

    private fun enhanceForOcr(input: Bitmap): Bitmap {

        val targetWidth = max(
            input.width * 2,
            1000
        ).coerceAtMost(2400)

        val targetHeight = (
            input.height.toFloat() *
            targetWidth /
            input.width
        ).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(
            input,
            targetWidth,
            targetHeight,
            true
        )

        val output = Bitmap.createBitmap(
            targetWidth,
            targetHeight,
            Bitmap.Config.ARGB_8888
        )

        val pixels = IntArray(targetWidth * targetHeight)

        scaled.getPixels(
            pixels,
            0,
            targetWidth,
            0,
            0,
            targetWidth,
            targetHeight
        )

        var minGray = 255
        var maxGray = 0

        val gray = IntArray(pixels.size)

        for (i in pixels.indices) {

            val c = pixels[i]

            val value = (
                Color.red(c) * 299 +
                Color.green(c) * 587 +
                Color.blue(c) * 114
            ) / 1000

            gray[i] = value

            if (value < minGray) minGray = value
            if (value > maxGray) maxGray = value
        }

        val range = max(1, maxGray - minGray)

        for (i in pixels.indices) {

            val value = (
                (gray[i] - minGray) * 255 / range
            ).coerceIn(0, 255)

            pixels[i] = Color.rgb(value, value, value)
        }

        output.setPixels(
            pixels,
            0,
            targetWidth,
            0,
            0,
            targetWidth,
            targetHeight
        )

        if (scaled !== input) {
            scaled.recycle()
        }

        input.recycle()

        return output
    }

    private fun recognize() {

        val source = cardBitmap

        if (source == null) {
            status.text = "حدد حدود البطاقة أولاً قبل الاستخراج."
            return
        }

        if (source.width < 500 || source.height < 250) {
            status.text = "مساحة البطاقة صغيرة. استخدم صورة أوضح."
            return
        }

        status.text = "جارٍ تحسين البطاقة وتشغيل OCR..."

        Thread {

            var api: TessBaseAPI? = null

            var nameCrop: Bitmap? = null
            var addressCrop: Bitmap? = null
            var idCrop: Bitmap? = null

            try {

                val base = File(filesDir, "tesseract")
                val data = File(base, "tessdata")

                data.mkdirs()

                copyAsset(
                    "tessdata/ara.traineddata",
                    File(data, "ara.traineddata")
                )

                copyAsset(
                    "tessdata/eng.traineddata",
                    File(data, "eng.traineddata")
                )

                // هذه النسب محسوبة بالنسبة إلى البطاقة المقصوصة.
                // قد تحتاج إلى ضبطها لاحقًا حسب تصميم البطاقة.

                nameCrop = cropRegion(
                    source,
                    0.40f, 0.28f,
                    0.99f, 0.53f
                )

                addressCrop = cropRegion(
                    source,
                    0.38f, 0.49f,
                    0.99f, 0.78f
                )

                idCrop = cropRegion(
                    source,
                    0.35f, 0.76f,
                    0.99f, 0.98f
                )

                api = TessBaseAPI()

                if (!api.init(base.absolutePath, "ara+eng")) {
                    throw IllegalStateException(
                        "تعذر تشغيل بيانات اللغة العربية والإنجليزية"
                    )
                }

                val nameText = readArabicRegion(api, nameCrop)
                val addressText = readArabicRegion(api, addressCrop)
                val idText = readNumericRegion(api, idCrop)

                val idMatch = Regex("[0-9]{14}")
                    .find(normalizeDigits(idText))
                    ?.value

                val fullText = buildString {

                    appendLine(
                        "الاسم: ${
                            nameText.ifBlank { "لم يتم التعرف عليه" }
                        }"
                    )

                    appendLine(
                        "العنوان: ${
                            addressText.ifBlank { "لم يتم التعرف عليه" }
                        }"
                    )

                    append(
                        "الرقم القومي: ${
                            idMatch ?: "لم يتم العثور على 14 رقمًا"
                        }"
                    )
                }

                runOnUiThread {

                    if (nameText.isNotBlank()) {
                        nameInput.setText(cleanArabic(nameText))
                    }

                    if (addressText.isNotBlank()) {
                        addressInput.setText(cleanArabic(addressText))
                    }

                    if (idMatch != null) {
                        idInput.setText(idMatch)
                    }

                    rawInput.setText(fullText)

                    val missing = mutableListOf<String>()

                    if (nameText.isBlank()) missing.add("الاسم")
                    if (addressText.isBlank()) missing.add("العنوان")
                    if (idMatch == null) missing.add("الرقم القومي")

                    status.text =
                        if (missing.isEmpty()) {
                            "انتهت القراءة. راجع البيانات قبل الحفظ."
                        } else {
                            "لم تُقرأ بعض البيانات: ${
                                missing.joinToString("، ")
                            }. راجعها يدويًا."
                        }
                }

            } catch (e: Exception) {

                runOnUiThread {
                    status.text =
                        "خطأ OCR: ${e.localizedMessage ?: "غير معروف"}"
                }

            } finally {

                api?.recycle()

                nameCrop?.recycle()
                addressCrop?.recycle()
                idCrop?.recycle()
            }

        }.start()
    }

    private fun readArabicRegion(
        api: TessBaseAPI,
        image: Bitmap
    ): String {

        api.setVariable("tessedit_char_whitelist", "")

        api.setPageSegMode(
            TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        )

        api.setImage(image)

        return api.getUTF8Text()?.trim().orEmpty()
    }

    private fun readNumericRegion(
        api: TessBaseAPI,
        image: Bitmap
    ): String {

        api.setVariable(
            "tessedit_char_whitelist",
            "0123456789٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹"
        )

        api.setPageSegMode(
            TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        )

        api.setImage(image)

        val text = api.getUTF8Text()?.trim().orEmpty()

        api.setVariable("tessedit_char_whitelist", "")

        return text
    }

    private fun cleanArabic(value: String): String {

        return value
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun copyAsset(
        path: String,
        destination: File
    ) {

        if (
            destination.exists() &&
            destination.length() > 1000
        ) {
            return
        }

        assets.open(path).use 
