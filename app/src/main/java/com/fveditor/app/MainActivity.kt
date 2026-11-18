package com.fveditor.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.OpenableColumns
import android.os.Environment
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.io.File
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
    private lateinit var brightnessOverlay: View
    private lateinit var positionSeek: SeekBar
    private val workerLabels = mutableListOf<TextView>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isUserScrubbing = false
    private var extractionGeneration = 0
    private var framesCompleted = 0

    private val frameCount = 150
    private val frames = MutableList<Bitmap?>(frameCount) { null }
    private val frameTimesMs = LongArray(frameCount) { it * 1000L }
    private val frameExecutor = Executors.newFixedThreadPool(4)
    private var selectedUri: Uri? = null
    private val importedVideos = mutableListOf<Uri>()
    private var outputTreeUri: Uri? = null
    private var pendingSplitAfterFolder = false
    private var splitByCount = true
    private var splitCount = 2
    private var splitSeconds = 30
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
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                if (!importedVideos.contains(uri)) importedVideos.add(uri)
            }
            loadVideo(uris.first())
            Toast.makeText(this, "${importedVideos.size} ویدیو وارد شده", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickOutputFolder = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            outputTreeUri = uri
            Toast.makeText(this, "پوشه خروجی انتخاب شد", Toast.LENGTH_SHORT).show()
            if (pendingSplitAfterFolder) {
                pendingSplitAfterFolder = false
                runSplitAll()
            }
        } else {
            pendingSplitAfterFolder = false
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

        // Compact top strip: keep only unique main actions. Player controls are
        // integrated here; duplicate/unimplemented tool launchers are omitted.
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(2), dp(1), dp(2), 0)
        }
        toolRow.addView(controlButton("Import\nVideo") { pickVideo.launch(arrayOf("video/*")) }, fixedButtonParams())
        toolRow.addView(controlButton("Videos") { showVideoList() }, fixedButtonParams())
        toolRow.addView(controlButton("Export\nVideo") { showExportDialog() }, fixedButtonParams())
        toolRow.addView(controlButton("Split") { showSplitDialog() }, fixedButtonParams())

        val controlsColumnOne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val controlsColumnTwo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val backButton = controlButton("−10\nفریم") { seekFrames(-10) }
        playButton = controlButton("▶") { togglePlay() }
        val forwardButton = controlButton("+10\nفریم") { seekFrames(10) }
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
        listOf(backButton, forwardButton, speedButton).forEach {
            controlsColumnOne.addView(it, fixedButtonParams())
        }
        listOf(playButton, timeModeButton).forEach {
            controlsColumnTwo.addView(it, fixedButtonParams())
        }
        toolRow.addView(controlsColumnOne)
        toolRow.addView(controlsColumnTwo)
        toolScroll.addView(toolRow)
        root.addView(toolScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(86)
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
        brightnessOverlay = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = false
        }
        playerContainer.addView(brightnessOverlay, FrameLayout.LayoutParams(
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
                        gestureStartBrightness = 0.5f
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
                            val level = (gestureStartBrightness + delta).coerceIn(0f, 1f)
                            val amount = (kotlin.math.abs(level - 0.5f) * 1.5f).coerceIn(0f, 0.72f)
                            val alpha = (amount * 255).toInt()
                            val tint = if (level >= 0.5f) {
                                android.graphics.Color.argb(alpha, 255, 255, 255)
                            } else {
                                android.graphics.Color.argb(alpha, 0, 0, 0)
                            }
                            brightnessOverlay.setBackgroundColor(tint)
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

        // Playback controls are already integrated into the top tool strip.

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        positionSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && player.duration > 0) {
                        val previewMs = player.duration * p / 1000
                        positionLabel.text = formatTime(previewMs)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    isUserScrubbing = true
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (player.duration > 0) player.seekTo(player.duration * progress / 1000)
                    isUserScrubbing = false
                }
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

        val workerStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), 0, dp(3), 0)
        }
        repeat(4) { index ->
            val label = TextView(this).apply {
                text = "P${index + 1}: آماده"
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(0xFFBDBDBD.toInt())
                setBackgroundColor(0xFF252525.toInt())
                setPadding(dp(2), dp(3), dp(2), dp(3))
            }
            workerLabels.add(label)
            workerStatusRow.addView(label, LinearLayout.LayoutParams(0, dp(25), 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        root.addView(workerStatusRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
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
            val spinnerAdapter = object : ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_spinner_item,
                (1..15).map { "$it ستون" }
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(android.graphics.Color.WHITE)
                    view.textSize = 11f
                    view.gravity = Gravity.CENTER
                    view.setPadding(dp(2), 0, dp(2), 0)
                    return view
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    view.setTextColor(android.graphics.Color.WHITE)
                    view.setBackgroundColor(0xFF292929.toInt())
                    view.textSize = 12f
                    view.setPadding(dp(10), dp(8), dp(10), dp(8))
                    return view
                }
            }
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = spinnerAdapter
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF292929.toInt())
                setStroke(dp(1), 0xFF777777.toInt())
                cornerRadius = dp(4).toFloat()
            }
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
        storyboardToolbar.addView(columnSpinner, LinearLayout.LayoutParams(dp(88), dp(39)))
        val minuteInput = EditText(this).apply {
            hint = "Min"
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            singleLine = true
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF292929.toInt()); setStroke(dp(1), 0xFF777777.toInt()); cornerRadius = dp(4).toFloat()
            }
        }
        val secondInput = EditText(this).apply {
            hint = "Sec"
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            singleLine = true
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF292929.toInt()); setStroke(dp(1), 0xFF777777.toInt()); cornerRadius = dp(4).toFloat()
            }
        }
        storyboardToolbar.addView(minuteInput, LinearLayout.LayoutParams(dp(42), dp(38)).apply { marginStart = dp(3) })
        storyboardToolbar.addView(secondInput, LinearLayout.LayoutParams(dp(42), dp(38)).apply { marginStart = dp(2) })
        storyboardToolbar.addView(controlButton("Time\nSet") {
            val minutes = minuteInput.text.toString().toLongOrNull() ?: 0L
            val seconds = secondInput.text.toString().toLongOrNull() ?: 0L
            val target = (minutes * 60_000L + seconds * 1_000L).coerceAtLeast(0L)
            if (player.duration > 0) player.seekTo(target.coerceAtMost(player.duration))
            else Toast.makeText(this, "ابتدا ویدیو وارد کنید", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(48), dp(39)).apply { marginStart = dp(3) })
        root.addView(storyboardToolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(43)
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
        mainHandler.post(positionTicker)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "Ⅱ" else "▶"
            }
        })
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                if (!isUserScrubbing && player.duration > 0) {
                    positionSeek.progress = ((player.currentPosition * 1000L) / player.duration)
                        .toInt().coerceIn(0, 1000)
                    updateTimeLabel(positionLabel)
                }
                mainHandler.postDelayed(this, 100L)
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        return if (timeModeSeconds) "${totalSeconds}s"
        else String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun setWorkerStatus(index: Int, message: String, done: Boolean = false) {
        if (index !in workerLabels.indices) return
        runOnUiThread {
            if (index in workerLabels.indices) {
                workerLabels[index].text = "P${index + 1}: $message"
                workerLabels[index].setTextColor(
                    if (done) 0xFF80D8A0.toInt() else 0xFFFFD180.toInt()
                )
            }
        }
    }

    private fun fixedButtonParams() = LinearLayout.LayoutParams(dp(54), dp(42))

    private fun scrollStoryboardTo(position: Int) {
        storyboard.scrollToPosition(position.coerceIn(0, frameCount - 1))
        dragScroller.setScrollFraction(position.toFloat() / (frameCount - 1).coerceAtLeast(1))
    }

    private fun showNotReady(tool: String) {
        Toast.makeText(this, "$tool هنوز پیاده‌سازی نشده است", Toast.LENGTH_SHORT).show()
    }

    private fun showVideoList() {
        if (importedVideos.isEmpty()) {
            Toast.makeText(this, "ابتدا ویدیو وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val names = importedVideos.mapIndexed { i, uri -> "${i + 1}. ${displayName(uri)}" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("ویدیوهای واردشده (${importedVideos.size})")
            .setItems(names) { _, which -> loadVideo(importedVideos[which]) }
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun showExportDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val rename = CheckBox(this).apply { text = "ReName"; isChecked = false }
        val tag = CheckBox(this).apply { text = "tag"; isChecked = false }
        val original = CheckBox(this).apply { text = "Original"; isChecked = true }
        panel.addView(rename); panel.addView(tag); panel.addView(original)
        android.app.AlertDialog.Builder(this)
            .setTitle("Export Video")
            .setView(panel)
            .setNegativeButton("بستن", null)
            .setPositiveButton("Save") { _, _ ->
                Toast.makeText(this, "تنظیمات انتخاب شد؛ موتور Export کامل نشده است", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Save As") { _, _ -> pickOutputFolder.launch(null) }
            .show()
    }

    private fun showSplitDialog() {
        if (importedVideos.isEmpty()) {
            Toast.makeText(this, "ابتدا یک یا چند ویدیو وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val mode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val countMode = RadioButton(this).apply { id = View.generateViewId(); text = "تقسیم بر اساس تعداد قسمت‌ها" }
        val timeMode = RadioButton(this).apply { id = View.generateViewId(); text = "تقسیم بر اساس زمان هر قسمت (ثانیه)" }
        mode.addView(countMode); mode.addView(timeMode)
        countMode.isChecked = splitByCount
        timeMode.isChecked = !splitByCount
        val countInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "تعداد قسمت‌ها"; setText(splitCount.toString())
        }
        val secondsInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "زمان هر قسمت به ثانیه"; setText(splitSeconds.toString())
        }
        panel.addView(mode); panel.addView(countInput); panel.addView(secondsInput)
        android.app.AlertDialog.Builder(this)
            .setTitle("Split – همه ویدیوهای واردشده")
            .setView(panel)
            .setNegativeButton("لغو", null)
            .setNeutralButton("Save As") { _, _ ->
                splitByCount = countMode.isChecked
                splitCount = countInput.text.toString().toIntOrNull()?.coerceIn(2, 100) ?: 2
                splitSeconds = secondsInput.text.toString().toIntOrNull()?.coerceIn(1, 86400) ?: 30
                pendingSplitAfterFolder = true
                pickOutputFolder.launch(null)
            }
            .setPositiveButton("Save") { _, _ ->
                splitByCount = countMode.isChecked
                splitCount = countInput.text.toString().toIntOrNull()?.coerceIn(2, 100) ?: 2
                splitSeconds = secondsInput.text.toString().toIntOrNull()?.coerceIn(1, 86400) ?: 30
                runSplitAll()
            }
            .show()
    }

    private fun runSplitAll() {
        val sources = importedVideos.toList()
        if (sources.isEmpty()) return
        Toast.makeText(this, "شروع تقسیم ${sources.size} ویدیو…", Toast.LENGTH_SHORT).show()
        Thread {
            var success = 0
            var failed = 0
            sources.forEachIndexed { sourceIndex, uri ->
                try {
                    val duration = MediaMetadataRetriever().run {
                        setDataSource(this@MainActivity, uri)
                        val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        release()
                        d
                    }
                    if (duration <= 0) throw IllegalStateException("duration")
                    val segmentMs = if (splitByCount) (duration / splitCount).coerceAtLeast(1L) else splitSeconds * 1000L
                    splitOneVideo(uri, segmentMs, sourceIndex + 1)
                    success++
                } catch (_: Exception) { failed++ }
            }
            runOnUiThread {
                Toast.makeText(this, "تقسیم تمام شد؛ موفق: $success، ناموفق: $failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun splitOneVideo(uri: Uri, segmentMs: Long, sourceNumber: Int) {
        val extractor = MediaExtractor()
        val tempFiles = mutableListOf<File>()
        try {
            extractor.setDataSource(this, uri, null)
            val trackFormats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val videoTrack = trackFormats.indexOfFirst {
                (it.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")
            }
            if (videoTrack < 0) throw IllegalStateException("No video track")
            trackFormats.indices.forEach { extractor.selectTrack(it) }
            val sourceName = displayName(uri)
            val baseName = sourceName.substringBeforeLast('.', sourceName)
                .replace(Regex("[^A-Za-z0-9._-]"), "_") + "_video_$sourceNumber"
            val buffer = java.nio.ByteBuffer.allocate(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var muxer: MediaMuxer? = null
            var currentFile: File? = null
            var segmentIndex = 0
            var segmentStartUs = -1L
            var nextBoundaryUs = segmentMs * 1000L
            fun startSegment(startUs: Long) {
                val file = File(cacheDir, "${baseName}_part_${segmentIndex + 1}.mp4")
                tempFiles.add(file)
                currentFile = file
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                trackFormats.forEach { muxer!!.addTrack(it) }
                muxer!!.start()
                segmentStartUs = startUs
            }
            fun finishSegment() {
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                muxer = null
                val file = currentFile
                if (file != null && file.exists() && file.length() > 0) {
                    saveOutputFile(file, "${baseName}_part_${segmentIndex + 1}.mp4")
                }
                segmentIndex++
            }
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val timeUs = extractor.sampleTime
                if (track == videoTrack && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 &&
                    segmentStartUs >= 0 && timeUs >= nextBoundaryUs) {
                    finishSegment()
                    startSegment(timeUs)
                    nextBoundaryUs += segmentMs * 1000L
                } else if (muxer == null && track == videoTrack &&
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    startSegment(timeUs)
                }
                if (muxer != null && segmentStartUs >= 0 &&
                    (track == videoTrack || timeUs >= segmentStartUs)) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size > 0) {
                        info.set(0, size, (timeUs - segmentStartUs).coerceAtLeast(0L),
                            extractor.sampleFlags)
                        try { muxer!!.writeSampleData(track, buffer, info) } catch (_: Exception) {}
                    }
                }
                if (!extractor.advance()) break
            }
            if (muxer != null) finishSegment()
            if (segmentIndex == 0) throw IllegalStateException("No segments")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            tempFiles.forEach { try { it.delete() } catch (_: Exception) {} }
        }
    }

    private fun saveOutputFile(file: File, name: String) {
        val tree = outputTreeUri
        if (tree == null) {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val outDir = File(dir, "FVEditor").apply { mkdirs() }
            file.copyTo(File(outDir, name), overwrite = true)
        } else {
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, tree)
                ?: throw IllegalStateException("folder")
            val out = root.createFile("video/mp4", name) ?: throw IllegalStateException("create")
            contentResolver.openOutputStream(out.uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("write")
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "video"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        return name
    }

    private fun controlButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = if (label.contains(' ')) label.replace(" ", "\n") else label
            textSize = 8.5f
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
        val generation = ++extractionGeneration
        framesCompleted = 0
        workerLabels.forEachIndexed { index, _ -> setWorkerStatus(index, "در صف") }
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
            repeat(4) { workerIndex ->
                frameExecutor.execute {
                    if (generation != extractionGeneration) return@execute
                    setWorkerStatus(workerIndex, "در حال کار")
                    var localDone = 0
                    val workerRetriever = MediaMetadataRetriever()
                    try {
                        workerRetriever.setDataSource(this, uri)
                        while (generation == extractionGeneration) {
                            val index = next.getAndIncrement()
                            if (index >= frameCount) break
                            try {
                                val bitmap = workerRetriever.getFrameAtTime(
                                    frameTimesMs[index] * 1000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                )
                                if (bitmap != null) {
                                    synchronized(frames) {
                                        if (generation == extractionGeneration) frames[index] = bitmap
                                        else bitmap.recycle()
                                    }
                                    if (generation == extractionGeneration) {
                                        localDone++
                                        synchronized(this) { framesCompleted++ }
                                        runOnUiThread {
                                            if (!isFinishing && !isDestroyed && generation == extractionGeneration) {
                                                adapter.notifyItemChanged(index)
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    } catch (_: Exception) {
                    } finally {
                        workerRetriever.release()
                        setWorkerStatus(workerIndex, "تمام: $localDone", done = true)
                    }
                }
            }
        } catch (_: Exception) {
            try { metadata.release() } catch (_: Exception) {}
            Toast.makeText(this, "خواندن فریم‌ها ناموفق بود", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTimeLabel(label: TextView) {
        label.text = formatTime(player.currentPosition)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(positionTicker)
        extractionGeneration++
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
