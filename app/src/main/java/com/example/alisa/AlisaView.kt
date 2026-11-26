package com.example.alisa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Живая Алиса: показывает выбранную картинку (она лежит только на телефоне)
 * и оживляет её: «дыхание», плывущие частицы магии, свечение у палочки, световой блик.
 * Если картинки нет, показывает нарисованный аватар.
 */
class AlisaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** true: круглое окошко поверх экрана (крупный план лица); false: вся картинка в рамке. */
    var circular = false

    /** true, пока Алиса говорит: эффекты становятся быстрее. */
    var speaking = false

    private var perkAt = -10000L

    /** «Кивок», когда Алиса услышала своё имя. */
    fun perk() {
        perkAt = SystemClock.uptimeMillis()
    }

    private var photo: Bitmap? = null
    private var focusX = 0.62f
    private var focusY = 0.30f
    private var lastT = 0L
    private var ta = 0.0

    private val fallback = ContextCompat.getDrawable(context, R.drawable.alisa_avatar)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val photoMatrix = Matrix()
    private val srcRect = RectF()
    private val clip = Path()
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F1C75B")
    }

    private val dustColors = intArrayOf(
        Color.parseColor("#FFE9A8"), Color.parseColor("#FFB3F0"), Color.parseColor("#C9B3FF")
    )
    private val dust = Array(3) { i ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(dustColors[i], dustColors[i] and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            xfermode = screen
        }
    }
    private val wandGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.parseColor("#FFFF8FE8"), Color.parseColor("#00FF8FE8")),
            null, Shader.TileMode.CLAMP
        )
        xfermode = screen
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            -1f, 0f, 1f, 0f,
            intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        xfermode = screen
    }

    private val rnd = Random(7)
    private val pN = 26
    private val pX = FloatArray(pN) { rnd.nextFloat() }
    private val pSpeed = FloatArray(pN) { 0.00005f + rnd.nextFloat() * 0.00009f }
    private val pOff = FloatArray(pN) { rnd.nextFloat() }
    private val pSize = FloatArray(pN) { 1.4f + rnd.nextFloat() * 3.0f }
    private val pPhase = FloatArray(pN) { rnd.nextFloat() * 6.28f }
    private val pColor = IntArray(pN) { rnd.nextInt(3) }

    private fun prefs() = context.getSharedPreferences("alisa", Context.MODE_PRIVATE)

    /** Загружает выбранную картинку (если есть) и положение лица. */
    fun reloadPhoto() {
        val f = File(context.filesDir, "alisa_photo.jpg")
        photo = if (f.exists()) {
            try {
                BitmapFactory.decodeFile(f.path)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        focusX = prefs().getFloat("face_x", 0.62f)
        focusY = prefs().getFloat("face_y", 0.30f)
        requestLayout()
        invalidate()
    }

    fun setFocus(x: Float, y: Float) {
        focusX = x.coerceIn(0f, 1f)
        focusY = y.coerceIn(0f, 1f)
        prefs().edit().putFloat("face_x", focusX).putFloat("face_y", focusY).apply()
    }

    /** Нажатие на картинку на главном экране: выбирает, что видно в круглом окошке. */
    fun setFocusFromTouch(x: Float, y: Float): Boolean {
        if (photo == null || circular || width == 0 || height == 0) return false
        setFocus(x / width, y / height)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reloadPhoto()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!circular && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val p = photo
            if (p != null) {
                setMeasuredDimension(w, (w * p.height / p.width.toFloat()).toInt())
            } else {
                val side = minOf(w, (280 * resources.displayMetrics.density).toInt())
                setMeasuredDimension(side, side)
            }
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val dens = resources.displayMetrics.density
        val speed = if (speaking) 2f else 1f
        ta += (t - lastT).coerceIn(0L, 100L) * speed
        lastT = t

        val perkP = ((t - perkAt) / 350f).coerceIn(0f, 1f)
        val perkZoom = if (perkP < 1f) 0.08f * sin(PI * perkP).toFloat() else 0f
        val zoom = 1.03f + 0.015f * sin(ta / 2600.0).toFloat() + perkZoom
        val panX = sin(ta / 3100.0).toFloat() * 0.006f * w
        val panY = sin(ta / 2300.0 + 1.0).toFloat() * 0.006f * h

        clip.reset()
        if (circular) clip.addCircle(w / 2, h / 2, minOf(w, h) / 2, Path.Direction.CW)
        else clip.addRoundRect(0f, 0f, w, h, 24 * dens, 24 * dens, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        val p = photo
        if (p != null) {
            // какая часть картинки видна
            if (circular) {
                val side = minOf(p.width, p.height) * 0.62f
                val cx = (focusX * p.width).coerceIn(side / 2, p.width - side / 2)
                val cy = (focusY * p.height).coerceIn(side / 2, p.height - side / 2)
                srcRect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            } else {
                srcRect.set(0f, 0f, p.width.toFloat(), p.height.toFloat())
            }
            val sc = maxOf(w / srcRect.width(), h / srcRect.height())
            photoMatrix.reset()
            photoMatrix.postTranslate(-srcRect.centerX(), -srcRect.centerY())
            photoMatrix.postScale(sc * zoom, sc * zoom)
            photoMatrix.postTranslate(w / 2 + panX, h / 2 + panY)
            canvas.drawBitmap(p, photoMatrix, bitmapPaint)
        } else {
            canvas.drawColor(Color.parseColor("#2B1559"))
            val d = fallback
            if (d != null) {
                val half = (minOf(w, h) / 2).toInt()
                canvas.save()
                canvas.translate(w / 2 + panX, h / 2 + panY)
                canvas.scale(zoom, zoom)
                d.setBounds(-half, -half, half, half)
                d.draw(canvas)
                canvas.restore()
            }
        }

        if (!circular) {
            // свечение у палочки (для вашей картинки)
            if (p != null) {
                val gp = 0.5f + 0.5f * sin(ta / 450.0).toFloat()
                wandGlow.alpha = (110 + 110 * gp).toInt()
                val gr = w * 0.13f * (0.9f + 0.2f * gp)
                canvas.save()
                canvas.translate(w * 0.07f, h * 0.34f)
                canvas.scale(gr, gr)
                canvas.drawCircle(0f, 0f, 1f, wandGlow)
                canvas.restore()
            }
            // световой блик проходит слева направо
            val sx = (((ta % 8000.0) / 8000.0).toFloat() * 1.7f - 0.35f) * w
            canvas.save()
            canvas.translate(sx, 0f)
            canvas.scale(w * 0.18f, 1f)
            canvas.drawRect(-1f, 0f, 1f, h, sweepPaint)
            canvas.restore()
        }

        // частицы магии плывут вверх
        val pr = if (circular) 0.7f else 1f
        for (i in 0 until pN) {
            val prog = (pOff[i] + (ta * pSpeed[i]).toFloat()) % 1f
            val x = w * pX[i] + sin(ta / 900.0 + pPhase[i]).toFloat() * 8f * dens
            val y = h * (1.05f - 1.1f * prog)
            val fade = sin(PI * prog).toFloat()
            val tw = 0.5f + 0.5f * sin(ta / 400.0 + pPhase[i] * 3).toFloat()
            val r = pSize[i] * dens * 3f * pr
            val paint = dust[pColor[i]]
            paint.alpha = (255 * fade * (0.35f + 0.65f * tw)).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(r, r)
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restore()
        }
        canvas.restore()

        // золотая рамка
        ringPaint.strokeWidth = 3f * dens
        if (circular) {
            canvas.drawCircle(w / 2, h / 2, minOf(w, h) / 2 - 1.5f * dens, ringPaint)
        } else {
            canvas.drawRoundRect(
                1.5f * dens, 1.5f * dens, w - 1.5f * dens, h - 1.5f * dens,
                24 * dens, 24 * dens, ringPaint
            )
        }
        postInvalidateDelayed(33)
    }
}
