package echo.music.iad1tya.playback

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import echo.music.iad1tya.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import timber.log.Timber

internal class FloatingPlayerOverlay(private val context: Context) {
  private val windowManager = checkNotNull(context.getSystemService(WindowManager::class.java))
  private val density = context.resources.displayMetrics.density
  private val params =
    WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    )

  private var container: LinearLayout? = null
  private var controlsRow: LinearLayout? = null
  private var titleView: TextView? = null
  private var artistView: TextView? = null
  private var artworkView: ImageView? = null
  private var waveformView: WaveformView? = null
  private var expandButton: ImageView? = null
  private var loadedArtworkUri: Uri? = null
  private var currentPlayer: Player? = null
  private var expanded = false
  private var attached = false
  private var statusInset = 0
  private var horizontalOffset = 0
  private var verticalOffset = 0
  private var dragging = false

  init {
    params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
  }

  fun show(player: Player) {
    currentPlayer = player
    if (!Settings.canDrawOverlays(context)) {
      hide()
      return
    }

    if (container == null) createWindow()
    updateContent(player)

    if (!attached) {
      try {
        val view = container ?: return
        windowManager.addView(view, params)
        attached = true
        view.requestApplyInsets()
      } catch (exception: WindowManager.BadTokenException) {
        Timber.w(exception, "Floating player overlay could not be attached")
        clearWindow()
      } catch (exception: SecurityException) {
        Timber.w(exception, "Floating player overlay permission was revoked")
        clearWindow()
      }
    } else {
      updateWindowPosition()
    }
  }

  fun hide() {
    currentPlayer = null
    if (attached) {
      try {
        container?.let(windowManager::removeView)
      } catch (exception: IllegalArgumentException) {
        Timber.d(exception, "Floating player window had already been removed")
      } catch (exception: SecurityException) {
        Timber.d(exception, "Floating player window permission was revoked")
      }
    }
    attached = false
    clearWindow()
  }

  private fun createWindow() {
    val availableWidth =
      (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(1))
    params.width =
      min(dp(340), availableWidth)

    val card =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(8), dp(8))
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(23, 23, 25))
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), Color.argb(48, 255, 255, 255))
          }
      }

    val metadata =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      }
    val artwork =
      ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = context.getString(R.string.floating_player_artwork)
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(54, 54, 58))
            cornerRadius = dp(8).toFloat()
          }
        clipToOutline = true
        setImageResource(R.drawable.ic_launcher_nobg)
      }
    metadata.addView(artwork, LinearLayout.LayoutParams(dp(38), dp(38)))

    val textColumn =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(6), 0)
      }
    val title =
      TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    val artist =
      TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 11f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    textColumn.addView(title)
    textColumn.addView(artist)
    metadata.addView(
      textColumn,
      LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    )

    val waveform = WaveformView(context, density)
    waveform.contentDescription = context.getString(R.string.floating_player_waveform)
    waveform.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    metadata.addView(waveform, LinearLayout.LayoutParams(dp(32), dp(30)))

    val expand =
      actionButton(
        R.drawable.navigate_next,
        context.getString(R.string.floating_player_expand)
      ) {
        setExpanded(!expanded)
      }
    metadata.addView(expand, LinearLayout.LayoutParams(dp(48), dp(48)))
    card.addView(
      metadata,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      )
    )

    val controls =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = if (expanded) View.VISIBLE else View.GONE
      }
    controls.addView(
      actionButton(
        R.drawable.skip_previous,
        context.getString(R.string.floating_player_previous)
      ) {
        currentPlayer?.seekToPreviousMediaItem()
      },
      LinearLayout.LayoutParams(dp(48), dp(48))
    )
    controls.addView(
      actionButton(R.drawable.pause, context.getString(R.string.pause)) {
        currentPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
      },
      LinearLayout.LayoutParams(dp(48), dp(48))
    )
    controls.addView(
      actionButton(R.drawable.skip_next, context.getString(R.string.floating_player_next)) {
        currentPlayer?.seekToNextMediaItem()
      },
      LinearLayout.LayoutParams(dp(48), dp(48))
    )
    card.addView(
      controls,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
    )

    val gestureDetector =
      GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
          override fun onDown(event: MotionEvent) = true

          override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            setExpanded(!expanded)
            return true
          }

          override fun onDoubleTap(event: MotionEvent): Boolean {
            currentPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            return true
          }

          override fun onLongPress(event: MotionEvent) {
            dragging = true
          }

          override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            distanceX: Float,
            distanceY: Float
          ): Boolean {
            if (dragging) {
              horizontalOffset -= distanceX.toInt()
              verticalOffset += distanceY.toInt()
              updateWindowPosition()
            }
            return true
          }

          override fun onFling(
            first: MotionEvent?,
            current: MotionEvent,
            velocityX: Float,
            velocityY: Float
          ): Boolean {
            if (first == null) return false
            val deltaX = current.x - first.x
            val deltaY = current.y - first.y
            if (abs(deltaX) > dp(56) && abs(deltaX) > abs(deltaY)) {
              if (deltaX > 0) currentPlayer?.seekToNextMediaItem()
              else currentPlayer?.seekToPreviousMediaItem()
              return true
            }
            return false
          }
        }
      )
    metadata.setOnTouchListener { _, event ->
      gestureDetector.onTouchEvent(event)
      if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
        dragging = false
      }
      true
    }
    params.y = statusInset + dp(8) + verticalOffset
    card.setOnApplyWindowInsetsListener { _, insets ->
      val statusBarTop =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          insets.getInsets(WindowInsets.Type.statusBars()).top
        } else {
          insets.systemWindowInsetTop
        }
      val cutoutTop =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          insets.displayCutout?.safeInsetTop ?: 0
        } else {
          0
        }
      statusInset = max(statusBarTop, cutoutTop)
      updateWindowPosition()
      insets
    }

    container = card
    controlsRow = controls
    titleView = title
    artistView = artist
    artworkView = artwork
    waveformView = waveform
    expandButton = expand
  }

  private fun updateContent(player: Player) {
    val metadata = player.mediaMetadata
    titleView?.text =
      metadata.title?.toString()?.takeIf(String::isNotBlank)
        ?: context.getString(R.string.unknown_track)
    artistView?.text =
      metadata.artist?.toString()?.takeIf(String::isNotBlank)
        ?: context.getString(R.string.unknown_artist)
    waveformView?.isPlaying = player.isPlaying

    val artworkUri = metadata.artworkUri
    if (artworkUri != loadedArtworkUri) {
      loadedArtworkUri = artworkUri
      artworkView?.setImageResource(R.drawable.ic_launcher_nobg)
      artworkUri?.let { uri ->
        val view = artworkView ?: return@let
        view.context.imageLoader.enqueue(
          ImageRequest.Builder(context)
            .data(uri)
            .target(ImageViewTarget(view))
            .build()
        )
      }
    }

    val pauseButton = controlsRow?.getChildAt(1) as? ImageView
    pauseButton?.setImageResource(if (player.isPlaying) R.drawable.pause else R.drawable.play)
    pauseButton?.contentDescription =
      context.getString(if (player.isPlaying) R.string.pause else R.string.play)
  }

  private fun actionButton(
    iconResource: Int,
    description: String,
    onClick: () -> Unit
  ): ImageView =
    ImageView(context).apply {
      setImageResource(iconResource)
      imageTintList = ColorStateList.valueOf(Color.WHITE)
      scaleType = ImageView.ScaleType.CENTER_INSIDE
      setPadding(dp(12), dp(12), dp(12), dp(12))
      contentDescription = description
      isFocusable = true
      isClickable = true
      setOnClickListener { onClick() }
    }

  private fun setExpanded(value: Boolean) {
    expanded = value
    controlsRow?.visibility = if (value) View.VISIBLE else View.GONE
    expandButton?.apply {
      setImageResource(if (value) R.drawable.arrow_upward else R.drawable.navigate_next)
      contentDescription =
        context.getString(
          if (value) R.string.floating_player_collapse else R.string.floating_player_expand
        )
    }
    updateWindowPosition()
    container?.post { updateWindowPosition() }
  }

  private fun updateWindowPosition() {
    if (!attached) {
      params.y = statusInset + dp(8) + verticalOffset
      params.x = horizontalOffset
      return
    }

    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val maxHorizontalOffset = ((screenWidth - params.width) / 2).coerceAtLeast(0)
    horizontalOffset = horizontalOffset.coerceIn(-maxHorizontalOffset, maxHorizontalOffset)
    val maxVerticalOffset =
      (screenHeight - statusInset - dp(8) - (container?.height ?: dp(100))).coerceAtLeast(0)
    verticalOffset = verticalOffset.coerceIn(0, maxVerticalOffset)
    params.x = horizontalOffset
    params.y = (statusInset + dp(8) + verticalOffset).coerceAtLeast(statusInset + dp(4))
    try {
      container?.let { windowManager.updateViewLayout(it, params) }
    } catch (exception: IllegalArgumentException) {
      Timber.d(exception, "Floating player window is no longer attached")
      attached = false
    } catch (exception: SecurityException) {
      Timber.w(exception, "Floating player window permission was revoked")
      hide()
    }
  }

  private fun clearWindow() {
    waveformView?.isPlaying = false
    artworkView?.setImageDrawable(null)
    container = null
    controlsRow = null
    titleView = null
    artistView = null
    artworkView = null
    waveformView = null
    expandButton = null
    loadedArtworkUri = null
  }

  private fun dp(value: Int): Int = (value * density).toInt()

  private class WaveformView(context: Context, private val density: Float) : View(context) {
    private val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 85, 100)
        style = Paint.Style.FILL
      }

    var isPlaying: Boolean = false
      set(value) {
        field = value
        invalidate()
      }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val count = 5
      val gap = 2f * density
      val barWidth = (width - gap * (count - 1)) / count
      val centerY = height / 2f
      val phase = SystemClock.uptimeMillis() / 210.0
      repeat(count) { index ->
        val strength =
          if (isPlaying) {
            0.18 + 0.82 * ((sin(phase + index * 0.9) + 1.0) / 2.0)
          } else {
            0.18
          }
        val barHeight = max(dp(4).toFloat(), height * strength.toFloat())
        val left = index * (barWidth + gap)
        canvas.drawRoundRect(
          left,
          centerY - barHeight / 2,
          left + barWidth,
          centerY + barHeight / 2,
          barWidth / 2,
          barWidth / 2,
          paint
        )
      }
      if (isPlaying) postInvalidateDelayed(40)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
  }
}
