package com.fveditor.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var storyboard: RecyclerView
    private lateinit var adapter: FrameAdapter
    private lateinit var columnSpinner: Spinner
    private lateinit var positionLabel: TextView
    private lateinit var playButton: Button
    private lateinit var speedButton: Button
    private lateinit var timeModeButton: Button
    private lateinit var dragScroller: ThickVerticalScroller

    private val frameCount = 150
    private val frames = MutableList<Bitmap?>(frameCount) { null }
    private val frameTimesMs = LongArray(frameCount) { it * 1000L }
    private val frameExecutor = Executors.newFixedThreadPool(4)
    private var selectedUri: Uri? = null
    private var columns = 3
    private var timeModeSeconds = true
    private val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)
    private var speedIndex = 3 // 1.0x is selected initially
    private var scrubbingBrightness = false
    private var scrubbingVolume = false
    private var gestureStartY = 0f
    private var gestureStartBrightness = 0.5f
    private var gestureStartVolume = 0

    private val pickVideo = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            loadVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        player = ExoPlayer.Builder(this).build()
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            setPadding(0, 0, 0, 0)
        }

        // Tool strip: one horizontal row at the very top. It scrolls internally
        // on narrow screens instead of forcing the whole page to scroll.
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        toolRow.addView(controlButton("Tool") { }, fixedButtonParams())
        toolRow.addView(controlButton("Import Video") { pickVideo.launch(arrayOf("video/*")) }, fixedButtonParams())
        toolRow.addView(controlButton("Export Video") { showNotReady("خروجی ویدیو") }, fixedButtonParams())
        toolRow.addView(controlButton("Delete") { showNotReady("حذف") }, fixedButtonParams())
        toolRow.addView(controlButton("Crop") { showNotReady("برش") }, fixedButtonParams())
        toolRow.addView(controlButton("Merge") { showNotReady("ادغام") }, fixedButtonParams())
        toolRow.addView(controlButton("Join") { showNotReady("اتصال") }, fixedButtonParams())
        toolRow.addView(controlButton("Color and Light") { showNotReady("رنگ و نور") }, fixedButtonParams())
        toolRow.addView(controlButton("Tracks") { showNotReady("ترک‌ها") }, fixedButtonParams())
        toolRow.addView(controlButton("Format") { showNotReady("فرمت") }, fixedButtonParams())
        toolScroll.addView(toolRow)
        root.addView(toolScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(85)
        ))

        // Keep the player itself unchanged; the transparent gesture layer sits
        // over it and interprets vertical drags on the left/right halves.
        val playerContainer = FrameLayout(this)
        playerView = PlayerView(this).apply {
            useController = false
            player = this@MainActivity.player
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val gestureLayer = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        scrubbingBrightness = event.x < v.width / 2f
                        scrubbingVolume = !scrubbingBrightness
                        gestureStartY = event.y
                        val attrs = window.attributes
                        gestureStartBrightness = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
                        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                        gestureStartVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = ((gestureStartY - event.y) / max(1f, v.height.toFloat())).coerceIn(-1f, 1f)
                        if (scrubbingVolume) {
                            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                            val maxVol = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            audio.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                (gestureStartVolume + delta * maxVol).toInt().coerceIn(0, maxVol), 0
                            )
                        } else if (scrubbingBrightness) {
                            val attrs = window.attributes
                            attrs.screenBrightness = (gestureStartBrightness + delta).coerceIn(0.05f, 1f)
                            window.attributes = attrs
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        scrubbingBrightness = false
                        scrubbingVolume = false
                        true
                    }
                    else -> true
                }
            }
        }
        playerContainer.addView(gestureLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(playerContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f
        ))

        // Speed and time-unit controls are moved into the row above the seekbar.
        val playerControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
        }
        val backButton = controlButton("−10 فریم") { seekFrames(-10) }
        playButton = controlButton("▶") { togglePlay() }
        val forwardButton = controlButton("+10 فریم") { seekFrames(10) }
        timeModeButton = controlButton("SEC") {
            timeModeSeconds = !timeModeSeconds
            timeModeButton.text = if (timeModeSeconds) "SEC" else "MIN"
            updateTimeLabel(positionLabel)
        }
        speedButton = controlButton("1.0x") {
            speedIndex = (speedIndex + 1) % speeds.size
            val speed = speeds[speedIndex]
            player.setPlaybackSpeed(speed)
            speedButton.text = "${speed}x"
        }
        listOf(backButton, playButton, forwardButton, timeModeButton, speedButton).forEach {
            playerControls.addView(it, fixedButtonParams())
        }
        root.addView(playerControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(85)
        ))

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val positionSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && player.duration > 0) player.seekTo(player.duration * p / 1000)
                    updateTimeLabel(positionLabel)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        positionLabel = TextView(this).apply {
            text = "00:00"
            setTextColor(-1)
            gravity = Gravity.CENTER
            textSize = 9f
        }
        seekRow.addView(positionSeek, LinearLayout.LayoutParams(0, dp(42), 1f))
        seekRow.addView(positionLabel, LinearLayout.LayoutParams(dp(60), dp(42)))
        root.addView(seekRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ))

        val storyboardToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        val upButton = controlButton("بالا") { scrollStoryboardTo(0) }
        val middleButton = controlButton("وسط") { scrollStoryboardTo(frameCount / 2) }
        val downButton = controlButton("پایین") { scrollStoryboardTo(frameCount - 1) }
        columnSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                (1..15).map { "$it ستون" }
            )
            setSelection(columns - 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val firstVisible = (storyboard.layoutManager as? GridLayoutManager)
                        ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
                    columns = position + 1
                    (storyboard.layoutManager as? GridLayoutManager)?.spanCount = columns
                    this@MainActivity.adapter.notifyDataSetChanged()
                    storyboard.requestLayout()
                    storyboard.post { storyboard.scrollToPosition(firstVisible) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        listOf(upButton, middleButton, downButton).forEach {
            storyboardToolbar.addView(it, fixedButtonParams())
        }
        storyboardToolbar.addView(columnSpinner, LinearLayout.LayoutParams(0, dp(85), 1f))
        root.addView(storyboardToolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(85)
        ))

        val storyboardArea = FrameLayout(this)
        storyboard = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, dp(24), 0)
            clipToPadding = false
            setHasFixedSize(false)
        }
        adapter = FrameAdapter(
            frames,
            columnCount = { columns },
            availableWidth = { storyboard.width }
        ) { index ->
            if (player.duration > 0) player.seekTo(frameTimesMs[index].coerceAtMost(player.duration))
        }
        storyboard.adapter = adapter
        storyboardArea.addView(storyboard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        dragScroller = ThickVerticalScroller(this) { fraction ->
            val target = (fraction.coerceIn(0f, 1f) * (frameCount - 1)).toInt()
            storyboard.scrollToPosition(target)
        }
        storyboardArea.addView(dragScroller, FrameLayout.LayoutParams(
            dp(24), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
        ))
        storyboard.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
                val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
                dragScroller.setScrollFraction(first.toFloat() / (frameCount - 1).coerceAtLeast(1))
            }
        })
        root.addView(storyboardArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "Ⅱ" else "▶"
            }
        })
    }

    private fun fixedButtonParams() = LinearLayout.LayoutParams(dp(54), dp(85))

    private fun scrollStoryboardTo(position: Int) {
        storyboard.scrollToPosition(position.coerceIn(0, frameCount - 1))
        dragScroller.setScrollFraction(position.toFloat() / (frameCount - 1).coerceAtLeast(1))
    }

    private fun showNotReady(tool: String) {
        Toast.makeText(this, "$tool هنوز پیاده‌سازی نشده است", Toast.LENGTH_SHORT).show()
    }

    private fun controlButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 9f
            gravity = Gravity.CENTER
            setSingleLine(false)
            maxLines = 3
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun seekFrames(count: Int) {
        val frameRate = 30L // fallback frame step until source video frame rate is read
        val delta = count * 1000L / frameRate
        player.seekTo((player.currentPosition + delta).coerceAtLeast(0L))
    }


    private fun loadVideo(uri: Uri) {
        selectedUri = uri
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = false
        extractFrames(uri)
    }

    private fun extractFrames(uri: Uri) {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(this, uri)
            val durationMs = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            metadata.release()
            if (durationMs <= 0) return

            synchronized(frames) {
                frames.forEachIndexed { index, bitmap ->
                    bitmap?.recycle()
                    frames[index] = null
                    frameTimesMs[index] = (durationMs * index / frameCount).coerceAtMost(durationMs - 1)
                }
            }
            adapter.notifyDataSetChanged()

            val next = AtomicInteger(0)
            repeat(4) {
                frameExecutor.execute {
                    val workerRetriever = MediaMetadataRetriever()
                    try {
                        workerRetriever.setDataSource(this, uri)
                        while (true) {
                            val index = next.getAndIncrement()
                            if (index >= frameCount) break
                            try {
                                val bitmap = workerRetriever.getFrameAtTime(
                                    frameTimesMs[index] * 1000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                )
                                if (bitmap != null) {
                                    synchronized(frames) { frames[index] = bitmap }
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed) adapter.notifyItemChanged(index)
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    } catch (_: Exception) {
                    } finally {
                        workerRetriever.release()
                    }
                }
            }
        } catch (_: Exception) {
            try { metadata.release() } catch (_: Exception) {}
            Toast.makeText(this, "خواندن فریم‌ها ناموفق بود", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTimeLabel(label: TextView) {
        val ms = player.currentPosition.coerceAtLeast(0)
        val seconds = ms / 1000
        label.text = if (timeModeSeconds) "${seconds}s" else
            String.format("%02d:%02d", seconds / 60, seconds % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        frameExecutor.shutdownNow()
        synchronized(frames) { frames.filterNotNull().forEach { it.recycle() } }
    }
}

private class FrameAdapter(
    private val frames: List<Bitmap?>,
    private val columnCount: () -> Int,
    private val availableWidth: () -> Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<FrameAdapter.Holder>() {

    class Holder(val image: ImageView, val label: TextView, val root: FrameLayout) :
        RecyclerView.ViewHolder(root)

    private fun dpFrom(view: View, value: Int) =
        (value * view.resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0xFF242424.toInt())
        }
        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val label = TextView(parent.context).apply {
            textSize = 9f
            setTextColor(-1)
            setBackgroundColor(0x99000000.toInt())
            setPadding(3, 1, 3, 1)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        }
        root.addView(image)
        root.addView(label)
        return Holder(image, label, root)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val spanCount = columnCount().coerceAtLeast(1)
        val measuredWidth = availableWidth().takeIf { it > 0 }
            ?: holder.itemView.resources.displayMetrics.widthPixels
        val horizontalPadding = dpFrom(holder.itemView, 24)
        val cellWidth = ((measuredWidth - horizontalPadding).coerceAtLeast(1) / spanCount).coerceAtLeast(1)
        val params = holder.root.layoutParams ?: RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = (cellWidth * 0.62f).toInt().coerceAtLeast(1)
        holder.root.layoutParams = params
        holder.image.setImageBitmap(frames[position])
        holder.label.text = "%03d".format(position + 1)
        holder.root.setOnClickListener { onClick(position) }
    }

    override fun getItemCount() = frames.size
}


/** Thick, finger-friendly vertical scrubber for the storyboard. */
private class ThickVerticalScroller(
    context: android.content.Context,
    private val onFractionChanged: (Float) -> Unit
) : View(context) {
    private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF.toInt()
        strokeWidth = dp(5f)
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val thumbPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF39B66D.toInt()
        strokeWidth = dp(13f)
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var fraction = 0f
    private var dragging = false

    fun setScrollFraction(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val top = dp(12f)
        val bottom = height - dp(12f)
        canvas.drawLine(x, top, x, bottom, trackPaint)
        val y = top + (bottom - top) * fraction
        canvas.drawLine(x, y - dp(22f), x, y + dp(22f), thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                updateFromTouch(event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    private fun updateFromTouch(y: Float) {
        val top = dp(12f)
        val bottom = height - dp(12f)
        fraction = ((y - top) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f)
        invalidate()
        onFractionChanged(fraction)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
