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
import kotlin.math.min

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
    private lateinit var thickScroller: SeekBar

    private val frameCount = 150
    private val frames = MutableList<Bitmap?>(frameCount) { null }
    private val frameTimesMs = LongArray(frameCount) { it * 1000L }
    private val frameExecutor = Executors.newFixedThreadPool(4)
    private var selectedUri: Uri? = null
    private var columns = 3
    private var navigationMode = 1 // 0 Up, 1 Between, 2 Down
    private var timeModeSeconds = true
    private val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
    private var speedIndex = 0
    private var scrubbingScroller = false
    private var scrubbingBrightness = false
    private var scrubbingVolume = false

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

        // Player is flush to the top edge and spans the available width.
        playerView = PlayerView(this).apply {
            useController = false
            player = this@MainActivity.player
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(playerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f
        ))

        val playerControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(2, 0, 2, 0)
        }
        val backButton = controlButton("−10 فریم") { seekFrames(-10) }
        playButton = controlButton("▶") { togglePlay() }
        val forwardButton = controlButton("+10 فریم") { seekFrames(10) }
        playerControls.addView(backButton, equalParams())
        playerControls.addView(playButton, equalParams())
        playerControls.addView(forwardButton, equalParams())
        root.addView(playerControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
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
                    updateTimeLabel(this@MainActivity.positionLabel)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        positionLabel = TextView(this).apply {
            text = "00:00"
            setTextColor(-1)
            gravity = Gravity.CENTER
            textSize = 11f
        }
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
        seekRow.addView(positionSeek, LinearLayout.LayoutParams(0, dp(34), 1f))
        seekRow.addView(positionLabel, LinearLayout.LayoutParams(dp(52), dp(34)))
        seekRow.addView(timeModeButton, LinearLayout.LayoutParams(dp(54), dp(34)))
        seekRow.addView(speedButton, LinearLayout.LayoutParams(dp(58), dp(34)))
        root.addView(seekRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
        ))

        // A compact brightness/volume gesture strip immediately below player controls.
        val gestureHint = TextView(this).apply {
            text = "کشیدن در نیمه چپ: نور  |  کشیدن در نیمه راست: صدا"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        gestureHint.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scrubbingBrightness = event.x < v.width / 2f
                    scrubbingVolume = !scrubbingBrightness
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = -event.y / max(1f, v.height.toFloat())
                    if (scrubbingVolume) {
                        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                        val maxVol = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            (current + delta * maxVol).toInt().coerceIn(0, maxVol), 0
                        )
                    } else if (scrubbingBrightness) {
                        val attrs = window.attributes
                        val current = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
                        attrs.screenBrightness = (current + delta * 0.08f).coerceIn(0.05f, 1f)
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
        root.addView(gestureHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(20)
        ))

        val storyboardToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val navigationButton = controlButton("Between") { navigateStoryboard() }
        val openButton = controlButton("ویدیو") { pickVideo.launch(arrayOf("video/*")) }
        columnSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                (1..15).map { "$it ستون" }
            )
            setSelection(columns - 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    columns = position + 1
                    storyboard.layoutManager = GridLayoutManager(this@MainActivity, columns)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        storyboardToolbar.addView(navigationButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        storyboardToolbar.addView(openButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        storyboardToolbar.addView(columnSpinner, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(storyboardToolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ))

        val storyboardArea = FrameLayout(this)
        storyboard = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, dp(22), 0)
            clipToPadding = false
        }
        adapter = FrameAdapter(frames) { index ->
            if (player.duration > 0) player.seekTo(frameTimesMs[index].coerceAtMost(player.duration))
        }
        storyboard.adapter = adapter
        storyboardArea.addView(storyboard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Thick draggable scrollbar; updates the table position directly.
        thickScroller = SeekBar(this).apply {
            // Placeholder reference retained for the shared field; the visible draggable
            // scrollbar is a dedicated vertical view below.
            visibility = View.GONE
        }
        val dragBar = ThickVerticalScroller(this) { fraction ->
            val target = (fraction.coerceIn(0f, 1f) * (frameCount - 1)).toInt()
            storyboard.scrollToPosition(target)
        }
        storyboardArea.addView(dragBar, FrameLayout.LayoutParams(
            dp(24), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
        ))
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

    private fun controlButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

    private fun equalParams() = LinearLayout.LayoutParams(0, dp(40), 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun seekFrames(count: Int) {
        val frameRate = 30L // fallback frame step until source video frame rate is read
        val delta = count * 1000L / frameRate
        player.seekTo((player.currentPosition + delta).coerceAtLeast(0L))
    }

    private fun navigateStoryboard() {
        when (navigationMode) {
            0 -> storyboard.scrollToPosition(0)
            1 -> storyboard.scrollToPosition(frameCount / 2)
            2 -> storyboard.scrollToPosition(frameCount - 1)
        }
        navigationMode = (navigationMode + 1) % 3
        val next = when (navigationMode) { 0 -> "Up"; 1 -> "Between"; else -> "Down" }
        (storyboard.parent as? View)?.let { parent ->
            // Toolbar button is the first child of the toolbar; update its label.
            val root = parent.parent as? ViewGroup
            val toolbar = root?.getChildAt(4) as? LinearLayout
            (toolbar?.getChildAt(0) as? Button)?.text = next
        }
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
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<FrameAdapter.Holder>() {

    class Holder(val image: ImageView, val label: TextView, val root: FrameLayout) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = FrameLayout(parent.context).apply {
            val spanCount = ((parent as? RecyclerView)?.layoutManager as? GridLayoutManager)?.spanCount ?: 3
            val available = parent.width.takeIf { it > 0 } ?: parent.resources.displayMetrics.widthPixels
            val cellWidth = max(1, available / spanCount)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (cellWidth * 0.62f).toInt()
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
