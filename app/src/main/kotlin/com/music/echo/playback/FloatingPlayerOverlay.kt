package echo.music.iad1tya.playback

import android.animation.ValueAnimator
import android.animation.TimeInterpolator
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
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import echo.music.iad1tya.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
  private var expandedContent: LinearLayout? = null
  private var controlsRow: LinearLayout? = null
  private var titleView: TextView? = null
  private var artistView: TextView? = null
  private var artworkView: ImageView? = null
  private var waveformView: WaveformView? = null
  private var elapsedView: TextView? = null
  private var durationView: TextView? = null
  private var progressView: ProgressView? = null
  private var loadedArtworkUri: Uri? = null
  private var artworkRequest: Disposable? = null
  private var currentPlayer: Player? = null
  private var expanded = false
  private var attached = false
  private var statusInset = 0
  private var cutoutLeftInset = 0
  private var cutoutRightInset = 0
  private var horizontalOffset = 0
  private var verticalOffset = 0
  private var dragging = false
  private var expansionAnimator: ValueAnimator? = null

  private val progressUpdater =
    object : Runnable {
      override fun run() {
        if (!attached || !expanded) return
        updateProgress()
        container?.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
      }
    }

  init {
    params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    params.width = widthForState(expanded = false)
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
    expanded = false
    expansionAnimator?.cancel()
    expansionAnimator = null
    container?.removeCallbacks(progressUpdater)
    params.width = widthForState(expanded = false)
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
    val card =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background =
          GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(244, 17, 18, 22), Color.argb(238, 13, 19, 27))
          ).apply {
            cornerRadius = dp(30).toFloat()
            setStroke(dp(1), Color.argb(50, 255, 255, 255))
          }
        elevation = dp(12).toFloat()
        clipToOutline = true
      }

    val metadata =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.floating_player_expand)
        setOnClickListener { setExpanded(!expanded) }
      }
    val artwork =
      ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = context.getString(R.string.floating_player_artwork)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(54, 54, 58))
            cornerRadius = dp(7).toFloat()
          }
        clipToOutline = true
        setImageResource(R.drawable.ic_launcher_nobg)
      }
    metadata.addView(artwork, LinearLayout.LayoutParams(dp(32), dp(32)))

    val textColumn =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), 0, dp(4), 0)
      }
    val title =
      TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    val artist =
      TextView(context).apply {
        setTextColor(Color.rgb(190, 193, 201))
        textSize = 10f
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
    metadata.addView(
      waveform,
      LinearLayout.LayoutParams(dp(24), dp(26)).apply { marginStart = dp(4) }
    )
    card.addView(metadata, LinearLayout.LayoutParams(-1, dp(COLLAPSED_ROW_HEIGHT_DP)))

    val details =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        clipChildren = true
        clipToPadding = true
        alpha = 0f
        translationY = dp(5).toFloat()
      }

    val progressRow =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    val elapsed =
      timeLabel().apply {
        contentDescription = context.getString(R.string.floating_player_elapsed)
      }
    val duration =
      timeLabel().apply {
        contentDescription = context.getString(R.string.floating_player_duration)
        text = formatTime(0L)
      }
    val progress = ProgressView(context, density).apply {
      contentDescription = context.getString(R.string.floating_player_progress)
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    progressRow.addView(elapsed, LinearLayout.LayoutParams(dp(34), dp(20)))
    progressRow.addView(
      progress,
      LinearLayout.LayoutParams(0, dp(20), 1f).apply {
        marginStart = dp(7)
        marginEnd = dp(7)
      }
    )
    progressRow.addView(duration, LinearLayout.LayoutParams(dp(34), dp(20)))
    details.addView(
      progressRow,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)).apply {
        topMargin = dp(7)
      }
    )

    val controls =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
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
    details.addView(
      controls,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(EXPANDED_CONTROLS_HEIGHT_DP))
    )
    card.addView(
      details,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
    )

    val gestureDetector =
      GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
          override fun onDown(event: MotionEvent) = true

          override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            metadata.performClick()
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
            if (abs(deltaX) > dp(48) && abs(deltaX) > abs(deltaY)) {
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

    card.setOnApplyWindowInsetsListener { _, insets ->
      val statusBarTop =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          insets.getInsets(WindowInsets.Type.statusBars()).top
        } else {
          insets.systemWindowInsetTop
        }
      val cutoutTop: Int
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val cutout = insets.displayCutout
        cutoutTop = cutout?.safeInsetTop ?: 0
        cutoutLeftInset = cutout?.safeInsetLeft ?: 0
        cutoutRightInset = cutout?.safeInsetRight ?: 0
      } else {
        cutoutTop = 0
        cutoutLeftInset = 0
        cutoutRightInset = 0
      }
      statusInset = max(statusBarTop, cutoutTop)
      params.width = widthForState(expanded)
      updateWindowPosition()
      insets
    }

    container = card
    expandedContent = details
    controlsRow = controls
    titleView = title
    artistView = artist
    artworkView = artwork
    waveformView = waveform
    elapsedView = elapsed
    durationView = duration
    progressView = progress
  }

  private fun updateContent(player: Player) {
    val metadata = player.mediaMetadata
    val title =
      metadata.title?.toString()?.takeIf(String::isNotBlank)
        ?: context.getString(R.string.unknown_track)
    val artist =
      metadata.artist?.toString()?.takeIf(String::isNotBlank)
        ?: context.getString(R.string.unknown_artist)
    titleView?.text = title
    artistView?.text = artist
    container?.getChildAt(0)?.contentDescription =
      context.getString(
        if (expanded) R.string.floating_player_tap_to_collapse
        else R.string.floating_player_tap_to_expand,
        title,
        artist
      )
    waveformView?.isPlaying = player.isPlaying

    val artworkUri = metadata.artworkUri
    if (artworkUri != loadedArtworkUri) {
      artworkRequest?.dispose()
      artworkRequest = null
      loadedArtworkUri = artworkUri
      artworkView?.setImageResource(R.drawable.ic_launcher_nobg)
      artworkUri?.let { uri ->
        val view = artworkView ?: return@let
        artworkRequest =
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
    updateProgress()
  }

  private fun updateProgress() {
    val player = currentPlayer ?: return
    val duration = player.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
    val position = player.currentPosition.coerceIn(0L, duration)
    elapsedView?.text = formatTime(position)
    durationView?.text = formatTime(duration)
    elapsedView?.contentDescription =
      context.getString(R.string.floating_player_elapsed_value, formatTime(position))
    durationView?.contentDescription =
      context.getString(R.string.floating_player_duration_value, formatTime(duration))
    progressView?.progress =
      if (duration > 0L) (position.toDouble() / duration).toFloat() else 0f
  }

  private fun timeLabel() =
    TextView(context).apply {
      setTextColor(Color.rgb(190, 193, 201))
      textSize = 10f
      gravity = Gravity.CENTER
      includeFontPadding = false
    }

  private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
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
    if (expanded == value || container == null) return
    expanded = value
    expansionAnimator?.cancel()

    val details = expandedContent ?: return
    val currentHeight =
      when {
        details.visibility != View.VISIBLE -> 0
        details.height > 0 -> details.height
        details.layoutParams.height >= 0 -> details.layoutParams.height
        else -> dp(EXPANDED_CONTENT_HEIGHT_DP)
      }
    val targetHeight = if (value) dp(EXPANDED_CONTENT_HEIGHT_DP) else 0
    val startWidth = params.width
    val targetWidth = widthForState(value)
    if (value) {
      details.visibility = View.VISIBLE
      details.layoutParams = details.layoutParams.apply { height = currentHeight }
      container?.getChildAt(0)?.contentDescription =
        context.getString(
          R.string.floating_player_tap_to_collapse,
          titleView?.text ?: context.getString(R.string.unknown_track),
          artistView?.text ?: context.getString(R.string.unknown_artist)
        )
      container?.post(progressUpdater)
    } else {
      container?.removeCallbacks(progressUpdater)
      container?.getChildAt(0)?.contentDescription =
        context.getString(
          R.string.floating_player_tap_to_expand,
          titleView?.text ?: context.getString(R.string.unknown_track),
          artistView?.text ?: context.getString(R.string.unknown_artist)
        )
    }

    var cancelled = false
    expansionAnimator =
      ValueAnimator.ofFloat(0f, 1f).apply {
        duration = EXPANSION_DURATION_MS
        interpolator = SPRING_INTERPOLATOR
        addUpdateListener { animator ->
          val fraction = animator.animatedValue as Float
          params.width =
            (startWidth + (targetWidth - startWidth) * fraction)
              .toInt()
              .coerceIn(dp(1), availableWidth())
          details.layoutParams =
            details.layoutParams.apply {
              height =
                (currentHeight + (targetHeight - currentHeight) * fraction)
                  .toInt()
                  .coerceIn(0, targetHeight + dp(4))
            }
          details.alpha = fraction.coerceIn(0f, 1f)
          details.translationY = dp(5) * (1f - fraction.coerceIn(0f, 1f))
          updateWindowPosition()
        }
        addListener(
          object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
              cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
              if (cancelled) return
              details.layoutParams =
                details.layoutParams.apply {
                  height =
                    if (value) LinearLayout.LayoutParams.WRAP_CONTENT else 0
                }
              if (!value) {
                details.visibility = View.GONE
                details.alpha = 0f
                details.translationY = dp(5).toFloat()
              } else {
                details.alpha = 1f
                details.translationY = 0f
                updateProgress()
              }
              expansionAnimator = null
              updateWindowPosition()
            }
          }
        )
        start()
      }
  }

  private fun widthForState(expanded: Boolean): Int {
    val maxWidth = availableWidth()
    return min(dp(if (expanded) EXPANDED_MAX_WIDTH_DP else COLLAPSED_MAX_WIDTH_DP), maxWidth)
      .coerceAtLeast(dp(1))
  }

  private fun availableWidth(): Int =
    (context.resources.displayMetrics.widthPixels -
      cutoutLeftInset -
      cutoutRightInset -
      dp(SCREEN_SIDE_MARGIN_DP * 2))
      .coerceAtLeast(dp(1))

  private fun updateWindowPosition() {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    params.x = horizontalOffset
    params.y = (statusInset + dp(4) + verticalOffset).coerceAtLeast(statusInset + dp(4))
    if (!attached) return

    val maxHorizontalOffset =
      ((screenWidth - params.width) / 2 - max(cutoutLeftInset, cutoutRightInset)).coerceAtLeast(0)
    horizontalOffset = horizontalOffset.coerceIn(-maxHorizontalOffset, maxHorizontalOffset)
    val maxVerticalOffset =
      (screenHeight - statusInset - dp(8) - (container?.height ?: dp(64))).coerceAtLeast(0)
    verticalOffset = verticalOffset.coerceIn(0, maxVerticalOffset)
    params.x = horizontalOffset
    params.y = (statusInset + dp(4) + verticalOffset).coerceAtLeast(statusInset + dp(4))
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
    container?.removeCallbacks(progressUpdater)
    artworkRequest?.dispose()
    artworkRequest = null
    waveformView?.isPlaying = false
    artworkView?.setImageDrawable(null)
    container = null
    expandedContent = null
    controlsRow = null
    titleView = null
    artistView = null
    artworkView = null
    waveformView = null
    elapsedView = null
    durationView = null
    progressView = null
    loadedArtworkUri = null
  }

  private fun dp(value: Int): Int = (value * density).toInt()

  private fun dp(value: Float): Float = value * density

  private class ProgressView(context: Context, private val density: Float) : View(context) {
    private val trackPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(86, 255, 255, 255)
        style = Paint.Style.FILL
      }
    private val progressPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
      }

    var progress: Float = 0f
      set(value) {
        field = value.coerceIn(0f, 1f)
        invalidate()
      }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val barHeight = 2f * density
      val top = (height - barHeight) / 2f
      val radius = barHeight / 2f
      canvas.drawRoundRect(0f, top, width.toFloat(), top + barHeight, radius, radius, trackPaint)
      canvas.drawRoundRect(
        0f,
        top,
        width * progress,
        top + barHeight,
        radius,
        radius,
        progressPaint
      )
    }
  }

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
        val barHeight = max(dp(3).toFloat(), height * strength.toFloat())
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

  private companion object {
    const val COLLAPSED_MAX_WIDTH_DP = 252
    const val COLLAPSED_ROW_HEIGHT_DP = 40
    const val EXPANDED_MAX_WIDTH_DP = 420
    const val EXPANDED_CONTENT_HEIGHT_DP = 77
    const val EXPANDED_CONTROLS_HEIGHT_DP = 48
    const val SCREEN_SIDE_MARGIN_DP = 16
    const val EXPANSION_DURATION_MS = 360L
    const val PROGRESS_UPDATE_INTERVAL_MS = 500L

    val SPRING_INTERPOLATOR =
      TimeInterpolator { input ->
        (1f - (exp((-8f * input).toDouble()) * cos(10f * input))).toFloat()
      }
  }
}
