package com.james.assistant.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.james.assistant.JamesService
import com.james.assistant.MusicFolder
import com.james.assistant.MusicLibrary
import com.james.assistant.Places
import com.james.assistant.Prefs
import com.james.assistant.R
import com.james.assistant.VoiceInput
import com.james.assistant.Ui
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws James on top of other apps (Google Maps included):
 *  - the Mini Bubble: a draggable 64 dp "J" circle
 *  - the Full Overlay: a bottom sheet, 85% wide, with Home / Music / Map pages
 * Must be used from the main thread.
 */
class OverlayController(serviceCtx: Context, private val actions: Actions) {

    /**
     * Android 11+ wants overlay windows made from a "window context" tied to the screen,
     * not from the service itself; it gives correct sizes and avoids warnings or refusals.
     */
    private val ctx: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = serviceCtx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            // createDisplayContext + createWindowContext(type, options) works on Android 11 and newer.
            serviceCtx.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            serviceCtx
        }

    interface Actions {
        fun playFolder(name: String)
        fun shuffleAll()
        fun currentFolder(): String?
        fun nowPlaying(): NowPlaying?
        fun previous()
        fun togglePlay()
        fun next()
        /** e.g. "Connected to Pixel Buds". */
        fun statusLine(): String
        /** James speaks; [onDone] runs when he has finished. */
        fun say(text: String, onDone: (() -> Unit)? = null)
        /** Opens Google Maps navigation. Call while the overlay is still on screen. */
        fun navigateTo(address: String): Boolean
        /** Called just before listening, so the service can enable the microphone. */
        fun listeningStarted()
        /** True while James needs the mic for speech recognition (the wake word steps aside). */
        fun micBusy(busy: Boolean)
        fun play()
        fun pause()
    }

    /** What the now-playing card shows. */
    data class NowPlaying(
        val title: String,
        val folder: String,
        val trackUri: Uri?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    enum class Page { HOME, MUSIC, MAP }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var bubble: View? = null
    private var bubbleDot: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var sheet: SheetLayout? = null
    private var content: FrameLayout? = null
    private var released = false

    private val autoCollapse = Runnable { collapse() }

    /** Views of the now-playing card, while the Home page is showing. */
    private class NowPlayingViews(
        val art: ImageView,
        val title: TextView,
        val sub: TextView,
        val wave: WaveformView,
        val progress: ProgressLine,
        val playIcon: ImageView,
        val status: TextView
    )

    private var np: NowPlayingViews? = null
    private var artUri: Uri? = null
    private val progressTicker = object : Runnable {
        override fun run() {
            refreshNowPlaying()
            if (np != null) main.postDelayed(this, 500)
        }
    }

    /** The service calls this when the song, play/pause or status changes. */
    fun onPlaybackChanged() = refreshNowPlaying()

    var isExpanded = false
        private set

    private var currentPage = Page.HOME
    private var wakeListening = false

    /** Green dot on the bubble = "Hey James" is listening. */
    fun setWakeListening(on: Boolean) {
        if (wakeListening == on) return
        wakeListening = on
        (bubbleDot?.background as? GradientDrawable)?.setColor(if (on) GREEN else DOT_IDLE)
        refreshNowPlaying()   // the header line mentions the wake word
    }

    /** James has the mic for speech recognition; tell the service once per change. */
    private var micHeld = false
    private fun holdMic(on: Boolean) {
        if (micHeld == on) return
        micHeld = on
        actions.micBusy(on)
    }

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * Safety net: every 2 s, if neither the sheet nor the bubble is on screen
     * (e.g. Android refused a window while the phone was locked), put the bubble back.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (released) return
            ensureVisible()
            main.postDelayed(this, 2000)
        }
    }
    private var watchdogStarted = false

    /** Makes sure James is visible: the sheet if it's open, otherwise the bubble. */
    fun ensureVisible() {
        if (released || !canDraw()) return
        if (!watchdogStarted) {
            watchdogStarted = true
            main.postDelayed(watchdog, 2000)
        }
        // Decide from our own state, not isAttachedToWindow: a window that was just added
        // isn't attached until the next frame, which would wrongly look "missing".
        if (isExpanded) {
            if (sheet == null) {        // the sheet failed to appear: fall back to the bubble
                isExpanded = false
                showBubble()
            }
            return
        }
        if (bubble == null) showBubble()   // retried every 2 s if Android refused it
    }

    // ================= Public API =================

    /** Opens the Full Overlay on [page], hiding the bubble. */
    fun expand(page: Page = Page.HOME) {
        if (released || !canDraw()) return
        removeBubble()
        if (sheet == null) {
            val s = buildSheet()
            try {
                wm.addView(s, sheetParams())
                sheet = s
            } catch (e: Exception) {
                Log.e(JamesService.TAG, "Couldn't show overlay: ${e.message}")
                showBubble()
                return
            }
        }
        isExpanded = true
        try {
            showPage(page)
        } catch (e: Exception) {
            Log.e(JamesService.TAG, "Couldn't build page $page: ${e.message}", e)
        }
        scheduleAutoCollapse()
        ensureVisible()   // also starts the watchdog
    }

    /** Closes the Full Overlay and shows the bubble. */
    fun collapse() {
        if (released) return
        removeSheet()
        // Add the bubble on the next frame, after the sheet's window is really gone.
        main.post { if (!released && !isExpanded) showBubble() }
    }

    /** Removes everything from the screen (James stopped). */
    fun release() {
        released = true
        main.removeCallbacks(watchdog)
        stopMapFlow()
        voice = null
        removeSheet()
        removeBubble()
        io.shutdown()
    }

    // ================= Bubble =================

    private fun showBubble() {
        if (released || bubble != null || !canDraw()) return
        val view = buildBubble()
        val lp = bubbleParams ?: defaultBubbleParams().also { bubbleParams = it }
        // Keep it on screen, e.g. after a screen rotation.
        val screen = screenSize()
        lp.x = lp.x.coerceIn(0, maxOf(0, screen.x - dp(76)))
        lp.y = lp.y.coerceIn(0, maxOf(0, screen.y - dp(76) - dp(48)))
        try {
            wm.addView(view, lp)
            bubble = view
            Log.i(JamesService.TAG, "Bubble shown")
        } catch (e: Exception) {
            Log.e(JamesService.TAG, "Couldn't show bubble: ${e.message}")
        }
    }

    private fun removeBubble() {
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleDot = null
    }

    private fun defaultBubbleParams(): WindowManager.LayoutParams {
        val screen = screenSize()
        val total = dp(64 + 12)   // circle + shadow room
        return baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screen.x - total - dp(8)        // bottom-right, above Maps' own buttons
            y = (screen.y * 0.62f).toInt()
        }
    }

    private fun buildBubble(): View {
        val root = FrameLayout(ctx).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val circle = TextView(ctx).apply {
            text = "J"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF21E1E22.toInt())
                setStroke(dp(2), 0x40FFFFFF)
            }
            elevation = dp(6).toFloat()
            contentDescription = "James. Tap to open."
        }
        root.addView(circle, FrameLayout.LayoutParams(dp(64), dp(64)))

        // Status dot: green while the wake word is listening, grey otherwise.
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (wakeListening) GREEN else DOT_IDLE)
                setStroke(dp(2), 0xFF1E1E22.toInt())
            }
            elevation = dp(7).toFloat()
        }
        root.addView(dot, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END))
        bubbleDot = dot

        root.setOnTouchListener(BubbleTouch())
        return root
    }

    /** Drag to move; tap to open. Snaps to the nearest side when let go. */
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        val screen = screenSize()
                        lp.x = (startX + dx).toInt().coerceIn(0, screen.x - v.width)
                        lp.y = (startY + dy).toInt().coerceIn(0, screen.y - v.height)
                        safeUpdate(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val screen = screenSize()
                        lp.x = if (lp.x + v.width / 2 < screen.x / 2) dp(4) else screen.x - v.width - dp(4)
                        safeUpdate(v, lp)
                    } else {
                        v.performClick()
                        expand(Page.HOME)
                    }
                }
            }
            return true
        }
    }

    // ================= Sheet =================

    private fun sheetParams(): WindowManager.LayoutParams {
        val screen = screenSize()
        val width = min((screen.x * 0.85f).toInt(), dp(560))
        return baseParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Tell us about taps outside the sheet, but let them through to Maps.
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            windowAnimations = android.R.style.Animation_InputMethod   // slides up/down
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0   // reach the bottom edge; we pad for the navigation bar ourselves
            }
        }
    }

    private fun buildSheet(): SheetLayout {
        val s = SheetLayout(
            ctx,
            onDismiss = { collapse() },
            onOutside = { collapse() },
            onTouched = { scheduleAutoCollapse() }
        )
        val r = dp(24).toFloat()
        s.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF51A1A1E.toInt(), 0xF50E0E10.toInt())   // near-black, ~96% opaque
        ).apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(dp(1), Ui.HAIRLINE)
        }
        s.elevation = dp(16).toFloat()
        s.setPadding(dp(16), dp(8), dp(16), dp(16) + navBarHeight())

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(View(ctx).apply {
            background = rounded(0x55FFFFFF, dp(3))
        }, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })
        val c = FrameLayout(ctx)
        column.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content = c
        s.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return s
    }

    private fun removeSheet() {
        stopMapFlow()
        main.removeCallbacks(autoCollapse)
        main.removeCallbacks(progressTicker)
        np = null
        artUri = null
        sheet?.let { safeRemove(it) }
        sheet = null
        content = null
        isExpanded = false
    }

    private fun scheduleAutoCollapse() {
        main.removeCallbacks(autoCollapse)
        main.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
    }

    private fun showPage(page: Page, mapDestination: String? = null) {
        val c = content ?: return
        c.removeAllViews()
        stopMapFlow()
        homeListen = null
        currentPage = page
        main.removeCallbacks(progressTicker)
        np = null
        artUri = null
        sheet?.scrollChild = null
        sheet?.translationY = 0f
        val view = when (page) {
            Page.HOME -> homePage()
            Page.MUSIC -> musicPage()
            Page.MAP -> mapPage(mapDestination)
        }
        c.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (np != null) main.post(progressTicker)
    }

    // ---------- Page: Home ----------

    /** "James is listening…" strip at the top of Home, shown after the wake word. */
    private class HomeListenViews(val strip: View, val wave: WaveformView, val partial: TextView)
    private var homeListen: HomeListenViews? = null

    private fun homePage(): View {
        val col = vertical()

        val strip = Ui.column(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(12))
        }
        strip.addView(Ui.text(ctx, "James is listening…", 26f, bold = true).apply { gravity = Gravity.CENTER })
        val listenWave = WaveformView(ctx).apply { active = true }
        strip.addView(listenWave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(8)
        })
        val partial = Ui.text(ctx, "Say Music, Map, a playlist, or \"take me to…\"", 17f, Ui.TEXT_2).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        }
        strip.addView(partial)
        col.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        homeListen = HomeListenViews(strip, listenWave, partial)

        // Header: J avatar, "James", status line, close button.
        val header = Ui.row(ctx)
        header.addView(TextView(ctx).apply {
            text = "J"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Ui.BOLD
            gravity = Gravity.CENTER
            background = Ui.oval(ctx, 0xFF1E1E22.toInt(), Ui.GREEN, 2)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val names = Ui.column(ctx).apply { setPadding(dp(12), 0, dp(8), 0) }
        names.addView(Ui.text(ctx, "James", 20f, bold = true))
        val status = Ui.text(ctx, actions.statusLine(), 15f, Ui.TEXT_2, oneLine = true).apply {
            setPadding(0, dp(4), 0, 0)
        }
        names.addView(status)
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.circleButton(ctx, R.drawable.ic_expand_more, 48, 28, Ui.SURFACE_HI, Color.WHITE, "Hide") {
            collapse()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        // Now playing card.
        col.addView(nowPlayingCard(status), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // MUSIC and MAP.
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(bigTile("Music", "Pick a playlist", R.drawable.ic_music, Ui.GREEN) { showPage(Page.MUSIC) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(bigTile("Map", "Start a trip", R.drawable.ic_place, 0xFF5B8DEF.toInt()) { showPage(Page.MAP) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        col.addView(row)
        return col
    }

    private fun nowPlayingCard(status: TextView): View {
        val card = Ui.column(ctx).apply {
            background = Ui.card(ctx, 24)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        val top = Ui.row(ctx)
        val art = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_music)
            imageTintList = ColorStateList.valueOf(Ui.GREEN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = Ui.rounded(ctx, Ui.GREEN_DIM, 14)
        }
        Ui.roundCorners(art, dp(14).toFloat())
        top.addView(art, LinearLayout.LayoutParams(dp(64), dp(64)))

        val texts = Ui.column(ctx).apply { setPadding(dp(14), 0, dp(8), 0) }
        val title = Ui.text(ctx, "Nothing playing", 19f, bold = true, oneLine = true)
        val sub = Ui.text(ctx, "", 15f, Ui.TEXT_2, oneLine = true).apply { setPadding(0, dp(6), 0, 0) }
        texts.addView(title)
        texts.addView(sub)
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val wave = WaveformView(ctx, bars = 9)
        top.addView(wave, LinearLayout.LayoutParams(dp(44), dp(32)))
        card.addView(top)

        val progress = ProgressLine(ctx)
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(16)
        })

        // Controls: previous, play/pause (big, green), next.
        val controls = Ui.row(ctx).apply { gravity = Gravity.CENTER }
        controls.addView(Ui.circleButton(ctx, R.drawable.ic_prev, 56, 30, Color.TRANSPARENT, Color.WHITE, "Previous song") {
            actions.previous()
            main.postDelayed({ refreshNowPlaying() }, 150)
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val play = Ui.circleButton(ctx, R.drawable.ic_play, 64, 34, Ui.GREEN, 0xFF0E0E10.toInt(), "Play or pause") {
            actions.togglePlay()
            main.postDelayed({ refreshNowPlaying() }, 150)
        }
        controls.addView(play, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginStart = dp(24); marginEnd = dp(24)
        })
        controls.addView(Ui.circleButton(ctx, R.drawable.ic_next, 56, 30, Color.TRANSPARENT, Color.WHITE, "Next song") {
            actions.next()
            main.postDelayed({ refreshNowPlaying() }, 150)
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        card.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        np = NowPlayingViews(art, title, sub, wave, progress, play.getChildAt(0) as ImageView, status)
        refreshNowPlaying()
        return card
    }

    private fun refreshNowPlaying() {
        val v = np ?: return
        if (released) return
        setIfChanged(v.status, actions.statusLine())
        val now = actions.nowPlaying()
        if (now == null) {
            setIfChanged(v.title, "Nothing playing")
            setIfChanged(v.sub, "Tap play to start your music")
            v.progress.fraction = 0f
            v.wave.animating = false
            setPlayIcon(v, playing = false)
            if (artUri != null || v.art.tag != ART_DEFAULT) setDefaultArt(v.art)
            artUri = null
            return
        }
        setIfChanged(v.title, now.title.replace('_', ' '))
        setIfChanged(v.sub, spoken(now.folder))
        v.wave.animating = now.isPlaying
        setPlayIcon(v, now.isPlaying)
        v.progress.fraction = if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f

        // Album art for the current song (loaded once per song, off the main thread).
        if (now.trackUri != artUri) {
            artUri = now.trackUri
            setDefaultArt(v.art)
            val uri = now.trackUri ?: return
            if (io.isShutdown) return
            val px = dp(64)
            io.execute {
                val bmp = MusicLibrary.loadArt(ctx, uri, px)
                if (bmp != null) main.post {
                    if (released || np !== v || artUri != uri) return@post
                    v.art.setImageBitmap(bmp)
                    v.art.imageTintList = null
                    v.art.scaleType = ImageView.ScaleType.CENTER_CROP
                    v.art.setPadding(0, 0, 0, 0)
                    v.art.tag = ART_SONG
                }
            }
        }
    }

    /**
     * Only touch a view when its content really changes. Setting the same text every 500 ms
     * makes the whole floating window re-measure itself, which shows up as flicker and stutter.
     */
    private fun setIfChanged(view: TextView, value: String) {
        if (view.text?.toString() != value) view.text = value
    }

    private fun setPlayIcon(v: NowPlayingViews, playing: Boolean) {
        val want = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        if (v.playIcon.tag != want) {
            v.playIcon.setImageResource(want)
            v.playIcon.tag = want
        }
    }

    private fun setDefaultArt(art: ImageView) {
        art.setImageResource(R.drawable.ic_music)
        art.imageTintList = ColorStateList.valueOf(Ui.GREEN)
        art.scaleType = ImageView.ScaleType.CENTER_INSIDE
        art.setPadding(dp(16), dp(16), dp(16), dp(16))
        art.tag = ART_DEFAULT
    }

    // ---------- Page: Music ----------

    private fun musicPage(): View {
        val col = vertical()
        col.addView(backButton())
        col.addView(title("Your Playlists", centered = false))
        val subtitle = text("Pick a playlist to play", 17f, SECONDARY).apply { setPadding(0, 0, 0, dp(14)) }
        col.addView(subtitle)

        val grid = vertical()
        // Up to half the screen; scroll for more. A visible scrollbar and faded edges show there's more.
        val scroll = MaxHeightScrollView(ctx, maxOf(dp(240), (screenSize().y * 0.5f).toInt())).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = false
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet?.scrollChild = scroll

        grid.addView(text("Reading your music…", 18f, SECONDARY))

        col.addView(pill("Shuffle all", R.drawable.ic_shuffle) {
            actions.shuffleAll()
            collapse()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })

        // Read folders off the main thread, then fill the grid.
        io.execute {
            val folders = MusicLibrary.folders(ctx)
            main.post {
                if (!released && scroll.isAttachedToWindow) {
                    Log.i(JamesService.TAG, "Playlist page: ${folders.size} folders")
                    val playable = folders.count { it.tracks.isNotEmpty() }
                    val empty = folders.size - playable
                    subtitle.text = buildString {
                        append(when (playable) { 0 -> "No playlists yet"; 1 -> "1 playlist"; else -> "$playable playlists" })
                        if (empty > 0) append(" · $empty empty")
                        if (folders.size > 4) append(" · scroll for more")
                    }
                    fillGrid(grid, folders)
                }
            }
        }
        return col
    }

    private fun fillGrid(grid: LinearLayout, folders: List<MusicFolder>) {
        grid.removeAllViews()
        if (folders.isEmpty()) {
            grid.addView(text("No folders found. Put folders of songs inside the Music folder on your phone.", 18f, SECONDARY))
            return
        }
        val current = actions.currentFolder()
        // Folders with songs first, empty ones at the end.
        val ordered = folders.filter { it.tracks.isNotEmpty() } + folders.filter { it.tracks.isEmpty() }
        ordered.forEachIndexed { i, folder ->
            val card = folderCard(folder, TILE_COLORS[i % TILE_COLORS.size],
                folder.name.equals(current, ignoreCase = true))
            grid.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun folderCard(folder: MusicFolder, color: Int, isCurrent: Boolean): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = pressable(
                Ui.card(ctx, 18).apply { if (isCurrent) setStroke(dp(3), GREEN) }
            )
            val hasSongs = folder.tracks.isNotEmpty()
            isClickable = hasSongs
            isFocusable = hasSongs
            alpha = if (hasSongs) 1f else 0.5f
            contentDescription = if (hasSongs) "Play ${spoken(folder.name)}" else "${spoken(folder.name)}, no songs"
            if (hasSongs) setOnClickListener {
                actions.playFolder(folder.name)
                collapse()
            }
        }

        val art = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_folder)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(color, dp(12))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
        }
        card.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)))

        val texts = vertical().apply { setPadding(dp(12), 0, 0, 0) }
        texts.addView(text(folder.name.replace('_', ' '), 20f, Color.WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val count = when (folder.tracks.size) {
            0 -> "No songs"
            1 -> "1 song"
            else -> "${folder.tracks.size} songs"
        }
        texts.addView(text(count, 16f, SECONDARY).apply { maxLines = 1 })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Album art from the first song, if it has any.
        val first = folder.tracks.firstOrNull()?.uri
        if (first != null && !io.isShutdown) {
            val px = dp(56)
            io.execute {
                val bmp = MusicLibrary.loadArt(ctx, first, px)   // null: keep the coloured folder tile
                if (bmp != null) main.post {
                    if (released) return@post
                    art.setImageBitmap(bmp)
                    art.imageTintList = null
                    art.scaleType = ImageView.ScaleType.CENTER_CROP
                    art.setPadding(0, 0, 0, 0)
                }
            }
        }
        return card
    }

    // ---------- Page: Map ----------

    /** Views of the Map page, while it's showing. */
    private class MapViews(
        val wave: WaveformView,
        val label: TextView,
        val heard: TextView,
        val goRow: LinearLayout,
        val goText: TextView,
        val retry: View
    )

    private var map: MapViews? = null
    private var voice: VoiceInput? = null
    private var pendingAddress: String? = null
    private var countdown = 0
    private var failures = 0
    private val countdownTick = object : Runnable {
        override fun run() {
            val m = map ?: return
            val address = pendingAddress ?: return
            if (countdown <= 0) {
                go(address)
                return
            }
            m.goText.text = "Go  ·  $countdown"
            countdown--
            main.postDelayed(this, 1000)
        }
    }

    private fun mapPage(initialDestination: String? = null): View {
        val col = vertical()
        col.addView(backButton())
        col.addView(title("Where to?", centered = true))

        val wave = WaveformView(ctx)
        col.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(4); bottomMargin = dp(12)
        })

        // "Heard:" card
        val card = Ui.row(ctx).apply {
            background = Ui.card(ctx, 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            minimumHeight = dp(96)
        }
        card.addView(Ui.icon(ctx, R.drawable.ic_place, Ui.GREEN), LinearLayout.LayoutParams(dp(32), dp(32)))
        val texts = Ui.column(ctx).apply { setPadding(dp(14), 0, 0, 0) }
        val label = Ui.text(ctx, "Getting ready…", 16f, Ui.TEXT_2)
        val heard = Ui.text(ctx, "", 24f, bold = true).apply {
            setPadding(0, dp(6), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(label)
        texts.addView(heard)
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Go (with countdown) + Cancel, shown once an address is heard.
        val goRow = Ui.row(ctx).apply { visibility = View.GONE }
        val goText = Ui.text(ctx, "Go", 22f, 0xFF0E0E10.toInt(), bold = true)
        val goButton = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = Ui.pressable(Ui.rounded(ctx, Ui.GREEN, 20))
            isClickable = true
            isFocusable = true
            contentDescription = "Go"
            setOnClickListener { pendingAddress?.let { go(it) } }
            addView(goText)
        }
        goRow.addView(goButton, LinearLayout.LayoutParams(0, dp(64), 2f).apply { marginEnd = dp(6) })
        val cancel = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = Ui.pressable(Ui.rounded(ctx, Ui.SURFACE_HI, 20, Ui.HAIRLINE, 1))
            isClickable = true
            isFocusable = true
            contentDescription = "Cancel"
            setOnClickListener { cancelTrip() }
            addView(Ui.text(ctx, "Cancel", 20f, bold = true))
        }
        goRow.addView(cancel, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(6) })
        col.addView(goRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        // "Speak again", shown when nothing usable was heard.
        val retry = pill("Speak again", R.drawable.ic_mic) {
            failures = 0
            listen(prompt = null)
        }.apply { visibility = View.GONE }
        col.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        // Quick picks: Home, Office, recent trips.
        val picks = quickPicks()
        if (picks != null) {
            col.addView(Ui.label(ctx, "Quick picks"), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16); bottomMargin = dp(8) })
            col.addView(picks)
        }

        map = MapViews(wave, label, heard, goRow, goText, retry)
        failures = 0
        wave.animating = false

        if (initialDestination != null) {
            // "Hey James, take me to …": skip the question and go straight to the confirmation.
            main.post { useDestination(initialDestination) }
        } else {
            // Ask, then listen once James has finished talking (so he doesn't hear himself).
            main.post { listen(prompt = "Where are we going?") }
        }
        return col
    }

    private fun quickPicks(): View? {
        val items = mutableListOf<Triple<String, String, Int>>()   // label, address, icon
        Places.all(ctx).forEach { p -> items += Triple(p.name, p.address, placeIcon(p.name)) }
        Prefs.recents(ctx).forEach { r ->
            if (items.none { it.second.equals(r, ignoreCase = true) }) items += Triple(r, r, R.drawable.ic_history)
        }
        if (items.isEmpty()) return null
        val row = Ui.row(ctx)
        items.take(8).forEach { (label, address, icon) ->
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(16), 0)
                background = Ui.pressable(Ui.rounded(ctx, Ui.SURFACE_HI, 24, Ui.HAIRLINE, 1))
                isClickable = true
                isFocusable = true
                contentDescription = "Go to $label"
                setOnClickListener { confirm(address, spokenName = label) }
                addView(Ui.icon(ctx, icon, Ui.TEXT_2), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                addView(Ui.text(ctx, label, 17f, bold = true, oneLine = true).apply { maxWidth = dp(180) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
        }
        return android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun placeIcon(name: String): Int = when (name.lowercase()) {
        "home", "house" -> R.drawable.ic_home
        "office", "work" -> R.drawable.ic_work
        else -> R.drawable.ic_place
    }

    /** Says [prompt] (if any), then listens for a destination. */
    private fun listen(prompt: String?) {
        val m = map ?: return
        stopCountdown()
        pendingAddress = null
        m.goRow.visibility = View.GONE
        m.retry.visibility = View.GONE
        m.heard.text = ""
        scheduleAutoCollapse()

        val v = voice ?: VoiceInput(ctx, voiceCallback).also { voice = it }
        if (!v.hasPermission()) {
            m.label.text = "Microphone not allowed"
            m.heard.text = "Allow it in James setup, or pick a place below."
            return
        }
        voiceMode = VoiceMode.MAP
        holdMic(true)   // wake word steps aside, also while James asks (he mustn't hear himself)
        val begin = {
            if (map === m && voiceMode == VoiceMode.MAP) {
                m.label.text = "Listening…"
                m.wave.active = true
                m.wave.animating = true
                actions.listeningStarted()
                v.start()
            }
        }
        if (prompt != null) {
            m.label.text = "Say where you're going"
            actions.say(prompt) { begin() }
        } else {
            begin()
        }
    }

    private enum class VoiceMode { NONE, MAP, COMMAND }
    private var voiceMode = VoiceMode.NONE

    private val voiceCallback = object : VoiceInput.Callback {
        override fun onListening() {
            if (voiceMode == VoiceMode.MAP) map?.label?.text = "Listening…"
            scheduleAutoCollapse()
        }

        override fun onPartial(text: String) {
            when (voiceMode) {
                VoiceMode.MAP -> map?.heard?.text = text
                VoiceMode.COMMAND -> (homeListen?.partial ?: map?.heard)?.text = text
                VoiceMode.NONE -> {}
            }
        }

        override fun onResult(alternatives: List<String>) {
            val mode = voiceMode
            voiceMode = VoiceMode.NONE
            holdMic(false)
            if (mode == VoiceMode.COMMAND) {
                onCommandHeard(alternatives.first())
                return
            }
            val m = map ?: return
            m.wave.active = false
            m.wave.animating = false
            useDestination(alternatives.first())
        }

        override fun onError(reason: String) {
            val mode = voiceMode
            voiceMode = VoiceMode.NONE
            holdMic(false)
            if (mode == VoiceMode.COMMAND) {
                onCommandFailed(reason)
                return
            }
            val m = map ?: return
            m.wave.active = false
            m.wave.animating = false
            when (reason) {
                "no-match" -> {
                    failures++
                    if (failures <= 2) {
                        m.label.text = "Didn't catch that"
                        listen(prompt = "Sorry, say that again")
                        return
                    }
                    m.label.text = "Didn't catch that"
                    m.heard.text = "Tap Speak again, or pick a place below."
                }
                "no-permission" -> {
                    m.label.text = "Microphone not allowed"
                    m.heard.text = "Allow it in James setup."
                }
                "network" -> {
                    m.label.text = "No internet for voice"
                    m.heard.text = "Check your data, or pick a place below."
                }
                "unavailable" -> {
                    m.label.text = "Voice typing isn't available"
                    m.heard.text = "Install or update the Google app, or pick a place below."
                }
                else -> {
                    m.label.text = "Something went wrong"
                    m.heard.text = "Tap Speak again."
                }
            }
            m.retry.visibility = View.VISIBLE
        }
    }

    /** Shows the confirmation for a spoken place, or explains that Home/Office isn't saved. */
    private fun useDestination(said: String) {
        val m = map ?: return
        val resolved = resolvePlace(said)
        if (resolved != null) {
            confirm(resolved.first, spokenName = resolved.second)
        } else {
            m.label.text = "Not saved yet"
            m.heard.text = "Add it under Places in James setup."
            m.retry.visibility = View.VISIBLE
            actions.say("You haven't saved that place yet. You can add it in James setup.")
        }
    }

    /**
     * Turns what was said into an address: strips "take me to…", and maps
     * "home" / "office" / "work" to the saved places. Returns (address, name to say) or
     * null if they asked for a saved place that isn't set.
     */
    private fun resolvePlace(said: String): Pair<String, String>? {
        var t = said.trim().trimEnd('.', '!', '?')
        val prefixes = listOf(
            "take me to ", "navigate to ", "directions to ", "drive to ", "go to ", "let's go to ",
            "we are going to ", "we're going to ", "i'm going to ", "to "
        )
        prefixes.firstOrNull { t.lowercase().startsWith(it) }?.let { t = t.substring(it.length).trim() }
        // A saved place ("church", "my office", "home")?
        Places.find(ctx, t)?.let { return it.address to it.name }
        // They asked for Home or Office but haven't saved it: say so instead of searching the map.
        val bare = t.lowercase().removePrefix("my ").removePrefix("the ").removePrefix("go ").trim()
        if (bare in setOf("home", "house", "office", "work")) return null
        return t to t
    }

    /** Shows "Heard: <place>", says it, and starts the 3-second Go countdown. */
    private fun confirm(address: String, spokenName: String) {
        val m = map ?: return
        voice?.cancel()
        voiceMode = VoiceMode.NONE
        holdMic(false)
        stopCountdown()
        m.wave.active = false
        m.wave.animating = false
        m.retry.visibility = View.GONE
        pendingAddress = address
        m.label.text = "Heard:"
        m.heard.text = if (spokenName.equals(address, ignoreCase = true)) address else "${spokenName.replaceFirstChar { it.uppercase() }} · $address"
        m.goRow.visibility = View.VISIBLE
        m.goText.text = "Go"
        scheduleAutoCollapse()
        actions.say("Going to $spokenName") {
            if (map === m && pendingAddress == address) {
                countdown = 3
                main.post(countdownTick)
            }
        }
    }

    private fun go(address: String) {
        stopCountdown()
        pendingAddress = null
        // Open Maps while our window is still on screen, then shrink to the bubble.
        actions.navigateTo(address)
        collapse()
    }

    private fun cancelTrip() {
        val m = map ?: return
        stopCountdown()
        pendingAddress = null
        m.goRow.visibility = View.GONE
        m.label.text = "Cancelled"
        m.heard.text = "Tap Speak again, or pick a place below."
        m.retry.visibility = View.VISIBLE
    }

    private fun stopCountdown() {
        main.removeCallbacks(countdownTick)
        countdown = 0
    }

    /** Leaving the Map page or closing the sheet: stop listening and any countdown. */
    private fun stopMapFlow() {
        stopCountdown()
        voice?.cancel()
        voiceMode = VoiceMode.NONE
        commandToken++
        holdMic(false)
        pendingAddress = null
        map = null
    }

    // ---------- Voice commands (after "Hey James") ----------

    private var commandToken = 0
    private var commandHeld: String? = null   // address whose countdown was paused by the wake word

    /** Called by the service when the wake word is heard. */
    fun voiceCommand() {
        if (released || !canDraw()) return
        val held = if (isExpanded && currentPage == Page.MAP) pendingAddress else null
        if (held != null) {
            // Mid-countdown on the Map page: listen right here for "go" or "cancel".
            stopCountdown()
            commandHeld = held
            map?.label?.text = "Listening… say Go or Cancel"
            startCommandListening(prompt = "Yes?")
            return
        }
        commandHeld = null
        if (!isExpanded || currentPage != Page.HOME) expand(Page.HOME) else scheduleAutoCollapse()
        showHomeListening(true)
        startCommandListening(prompt = "Speak on, I'm listening")
    }

    private fun startCommandListening(prompt: String) {
        voice?.cancel()
        val v = voice ?: VoiceInput(ctx, voiceCallback).also { voice = it }
        if (!v.hasPermission()) {
            showHomeListening(false)
            actions.say("I need the microphone. Please allow it in James setup.")
            return
        }
        voiceMode = VoiceMode.COMMAND
        holdMic(true)
        val token = ++commandToken
        scheduleAutoCollapse()
        actions.say(prompt) {
            if (token == commandToken && voiceMode == VoiceMode.COMMAND && !released) {
                actions.listeningStarted()
                v.start()
            }
        }
    }

    private fun showHomeListening(on: Boolean) {
        val h = homeListen ?: return
        h.strip.visibility = if (on) View.VISIBLE else View.GONE
        h.wave.animating = on
        if (on) h.partial.text = "Say Music, Map, a playlist, or \"take me to…\""
    }

    private fun onCommandFailed(reason: String) {
        showHomeListening(false)
        val held = commandHeld
        commandHeld = null
        if (held != null && map != null) {
            // Nothing heard: carry on with the paused trip.
            pendingAddress = held
            map?.label?.text = "Heard:"
            countdown = 3
            main.post(countdownTick)
            return
        }
        when (reason) {
            "no-match" -> actions.say("Sorry, I didn't catch that")
            "network" -> actions.say("I need internet to understand you")
            "no-permission" -> actions.say("I need the microphone. Please allow it in James setup.")
            else -> actions.say("Sorry, something went wrong")
        }
    }

    /** Works out what the driver wants and does it. */
    private fun onCommandHeard(said: String) {
        showHomeListening(false)
        val held = commandHeld
        commandHeld = null
        val t = normalize(said)
        Log.i(JamesService.TAG, "Command: \"$said\" -> \"$t\"")

        // During a trip countdown: go / cancel.
        if (held != null) {
            when (t) {
                in GO_WORDS -> { go(held); return }
                in CANCEL_WORDS -> { cancelTrip(); return }
            }
        }

        val destination = DESTINATION_PREFIXES.firstOrNull { t.startsWith(it) }
            ?.let { t.removePrefix(it).trim() }?.takeIf { it.isNotEmpty() }
        when {
            destination != null -> showPage(Page.MAP, destination)
            t in HOME_WORDS -> showPage(Page.MAP, "home")
            Places.find(ctx, t) != null -> showPage(Page.MAP, t)
            t in MAP_WORDS -> showPage(Page.MAP)
            t in MUSIC_WORDS -> showPage(Page.MUSIC)
            t.contains("shuffle") -> { actions.shuffleAll(); collapse() }
            t in PLAY_WORDS -> { actions.play(); collapse() }
            t in PAUSE_WORDS -> { actions.pause(); collapse() }
            t in NEXT_WORDS -> { actions.next(); collapse() }
            t in PREV_WORDS -> { actions.previous(); collapse() }
            t.startsWith("play ") -> playByName(t.removePrefix("play ").trim())
            t in CLOSE_WORDS -> collapse()
            else -> {
                // A bare playlist name ("gospel") also works.
                val folder = bestFolder(t)
                if (folder != null) {
                    actions.playFolder(folder)
                    collapse()
                } else {
                    actions.say("Sorry, I didn't get that. Say music, map, or a playlist name.")
                }
            }
        }
    }

    private fun playByName(name: String) {
        if (name in setOf("music", "songs", "my music", "something", "a song")) {
            actions.play()
            collapse()
            return
        }
        val folder = bestFolder(name)
        if (folder != null) {
            actions.playFolder(folder)   // the service says "Playing …"
            collapse()
        } else {
            actions.say("I couldn't find a playlist called $name")
            showPage(Page.MUSIC)
        }
    }

    /** Matches a spoken name to a folder: exact, then contains, then a near spelling. */
    private fun bestFolder(spoken: String): String? {
        if (spoken.isBlank()) return null
        val folders = MusicLibrary.folders(ctx)
        val key = normalize(spoken)
        val named = folders.map { it.name to normalize(it.name.replace('_', ' ')) }
        named.firstOrNull { it.second == key }?.let { return it.first }
        named.firstOrNull { it.second.contains(key) || (key.length >= 4 && key.contains(it.second)) }?.let { return it.first }
        return named.map { it.first to editDistance(it.second, key) }
            .filter { (_, d) -> d <= maxOf(1, key.length / 4) }
            .minByOrNull { it.second }?.first
    }



    // ================= Small building blocks =================

    private fun vertical() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun title(s: String, centered: Boolean) = text(s, 30f, Color.WHITE, bold = true).apply {
        gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun bigTile(label: String, sub: String, icon: Int, accent: Int, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(16))
            minimumHeight = dp(112)
            background = Ui.pressable(Ui.card(ctx, 24))
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            // Icon in a tinted circle.
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                background = Ui.oval(ctx, (accent and 0x00FFFFFF) or 0x2E000000)
                addView(Ui.icon(ctx, icon, accent), LinearLayout.LayoutParams(dp(24), dp(24)))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(Ui.text(ctx, label.uppercase(), 22f, bold = true).apply {
                letterSpacing = 0.04f
                setPadding(0, dp(10), 0, 0)
            })
            addView(Ui.text(ctx, sub, 15f, Ui.TEXT_2, oneLine = true).apply { setPadding(0, dp(4), 0, 0) })
        }

    private fun pill(label: String, icon: Int, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            background = pressable(rounded(TILE, dp(28)))
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(GREEN)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
            addView(text(label, 19f, Color.WHITE, bold = true))
        }

    private fun backButton(): View {
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
            background = pressable(rounded(0x00000000, dp(24)))
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { showPage(Page.HOME) }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
            addView(text("Back", 18f, Color.WHITE, bold = true))
        }
        val wrap = FrameLayout(ctx)
        wrap.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        return wrap
    }

    private fun rounded(color: Int, radiusPx: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

    private fun pressable(d: Drawable) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null)

    // ================= Window helpers =================

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (e: Exception) { Log.w(JamesService.TAG, "removeView: ${e.message}") }
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { Log.w(JamesService.TAG, "updateView: ${e.message}") }
    }

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = ctx.resources.displayMetrics
            Point(dm.widthPixels, dm.heightPixels)
        }

    /** Only needed on Android 11+, where the sheet is laid out down to the screen's bottom edge. */
    private fun navBarHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else 0

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val ART_DEFAULT = "default"
        private const val ART_SONG = "song"
        private const val AUTO_COLLAPSE_MS = 20_000L   // close by itself if untouched while driving
        private const val GREEN = 0xFF2BD47D.toInt()
        private const val DOT_IDLE = 0xFF8A8A8E.toInt()
        private const val TILE = 0xFF2C2C30.toInt()
        private const val CARD = 0xFF26262A.toInt()
        private const val SECONDARY = 0xB3FFFFFF.toInt()
        private val TILE_COLORS = intArrayOf(
            0xFFF5A25D.toInt(), 0xFF9B7BFF.toInt(), 0xFF5B8DEF.toInt(),
            0xFF2BD47D.toInt(), 0xFFF06BA8.toInt(), 0xFF3CC8C8.toInt()
        )

        /** "James_Default" -> "James Default", for speech and screen readers. */
        fun spoken(folder: String) = folder.replace('_', ' ')

        /** Lower case, no punctuation, no "hey James" / "please". */
        fun normalize(s: String): String =
            s.lowercase()
                .replace(Regex("[^a-z0-9' ]"), " ")
                .replace(Regex("\\b(hey|okay|ok) james\\b|\\bjames\\b|\\bplease\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun editDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        private val GO_WORDS = setOf("go", "go now", "yes", "start", "let's go", "lets go", "ok go", "okay go", "drive", "go go")
        private val CANCEL_WORDS = setOf("cancel", "stop", "no", "don't", "dont", "never mind", "nevermind", "wait")
        private val CLOSE_WORDS = setOf("close", "hide", "nothing", "never mind", "nevermind", "cancel", "goodbye", "bye", "that's all", "thats all", "no")
        private val MAP_WORDS = setOf("map", "maps", "navigation", "navigate", "directions", "where to", "open map", "open maps", "trip", "start a trip")
        private val MUSIC_WORDS = setOf("music", "playlist", "playlists", "songs", "my music", "open music", "choose music", "change music", "change playlist")
        private val PLAY_WORDS = setOf("play", "resume", "continue", "play music", "start music", "play the music", "resume music")
        private val PAUSE_WORDS = setOf("pause", "stop", "stop music", "pause music", "stop the music", "quiet", "silence")
        private val NEXT_WORDS = setOf("next", "next song", "skip", "skip song", "skip this", "next track", "change song", "change the song")
        private val PREV_WORDS = setOf("previous", "previous song", "back", "go back", "last song", "previous track")
        private val HOME_WORDS = setOf("home", "go home", "my house")
        private val DESTINATION_PREFIXES = listOf(
            "take me to ", "navigate to ", "directions to ", "drive to ", "go to ", "let's go to ", "lets go to ",
            "we are going to ", "we're going to ", "i'm going to ", "im going to ", "take me "
        )
    }
}
