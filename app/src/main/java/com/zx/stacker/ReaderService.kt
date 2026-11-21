package com.zx.stacker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.min

/**
 * Foreground service: mirrors the screen into an ImageReader, reads text with ML Kit
 * about every 0.7 s, parses Lalamove offers, scores them against live GPS and your
 * current orders, and shows the verdict in a floating bubble. Read-only: never taps anything.
 */
class ReaderService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.zx.stacker.STOP"
        @Volatile var running = false

        private const val CHANNEL = "reader"
        private const val NOTIF_ID = 7
        private const val INTERVAL_MS = 700L
        private const val CAPTURE_WIDTH = 720
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: Overlay? = null
    private var highlighter: Highlighter? = null
    private lateinit var store: Store
    private lateinit var resolver: PlaceResolver
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var fused: FusedLocationProviderClient? = null

    @Volatile private var here: LatLng? = null
    private var busy = false
    private var scale = 1f
    private var offers: List<ParsedOffer> = emptyList()
    private var lastSig = ""
    private val alerted = HashSet<String>()
    private val nearDrop = HashSet<String>()
    private var syncNote: String? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let {
                val p = LatLng(it.latitude, it.longitude)
                here = p
                store.saveHere(p)
                autoProgress(p)
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { main.post { stopSelf() } }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            grab()
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        store = Store(this)
        resolver = PlaceResolver(this)
        here = store.here()
        try {
            begin(code, data)
        } catch (e: Throwable) {
            store.lastError = "Start failed: $e"
            android.widget.Toast.makeText(this, "Offer Reader couldn't start: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {

        // Android 14+: must be in the foreground (type mediaProjection) before getMediaProjection.
        startInForeground()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("screen sharing token was refused")
        projection = mp
        mp.registerCallback(projectionCallback, main)

        startCapture(mp)
        startLocation()

        highlighter = Highlighter(this).also { it.show() }   // added first so the bubble sits above it
        overlay = Overlay(this).also {
            it.show()
            it.setRows(listOf(Overlay.Row("Offer Reader on · open Lalamove Pickup", Overlay.ORANGE)))
        }
        running = true
        main.postDelayed(tick, INTERVAL_MS)
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Offer reader", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ReaderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Offer Reader is watching")
            .setContentText("Reading Lalamove offers on screen")
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (hasLocation()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun startCapture(mp: MediaProjection) {
        val (sw, sh) = screenSize()
        val w = min(sw, CAPTURE_WIDTH)
        scale = w.toFloat() / sw
        val h = (sh * scale).toInt()
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = ir
        display = mp.createVirtualDisplay(
            "offer-reader", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, main
        )
    }

    private fun startLocation() {
        if (!hasLocation()) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        fused = client
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .build()
        try {
            client.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { l -> if (l != null && here == null) here = LatLng(l.latitude, l.longitude) }
        } catch (_: SecurityException) {
        }
    }

    private fun grab() {
        if (busy) return
        val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) { try { render() } catch (e: Throwable) { store.lastError = "Score: $e" }; return }   // screen unchanged: just re-score (GPS may have moved)
        val bmp = try { toBitmap(img) } catch (e: Exception) { null } finally { img.close() }
        if (bmp == null) return
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { try { handle(it) } catch (e: Throwable) { store.lastError = "Read: $e" } }
            .addOnCompleteListener { busy = false; bmp.recycle() }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
        padded.recycle()
        return cropped
    }

    private fun handle(text: Text) {
        val own = overlay?.rect() ?: Rect()
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            val r = Rect((b.left / scale).toInt(), (b.top / scale).toInt(), (b.right / scale).toInt(), (b.bottom / scale).toInt())
            if (!own.isEmpty && Rect.intersects(own, r)) continue   // skip our own bubble
            lines.add(OcrLine(line.text, r.left, r.top, r.right, r.bottom))
        }
        val parsed = OfferParser.parse(lines)
        if (isRecordScreen(lines)) {
            // Record screen: Ongoing cards are YOUR jobs, Completed/Canceled are history. Never score them as offers.
            if (lines.any { it.text.trim().equals("In Progress", ignoreCase = true) }) syncOrders(parsed)
            offers = emptyList()
            render()
            return
        }
        syncNote = null
        offers = parsed
        if (lines.isNotEmpty()) Log.d("OfferReader", "screen: " + lines.joinToString(" | ") { it.text } + "\n-> " + offers)
        render()
    }

    /** Lalamove's Record screen has the Ongoing / Completed / Canceled tabs at the top. */
    private fun isRecordScreen(lines: List<OcrLine>): Boolean {
        val t = lines.map { it.text.trim().lowercase() }.toSet()
        return "ongoing" in t && "completed" in t && ("canceled" in t || "cancelled" in t)
    }

    /** Make "orders on board" match the Ongoing tab. Keeps each order's picked-up flag. */
    private fun syncOrders(parsed: List<ParsedOffer>) {
        val old = store.orders()
        val out = ArrayList<Order>()
        for (o in parsed) {
            val p = resolver.cached(o.pickup)
            val d = resolver.cached(o.drop)
            if (p == null || d == null) {
                if (p == null) resolver.resolveAsync(o.pickup)
                if (d == null) resolver.resolveAsync(o.drop)
                syncNote = "📋 Reading your ongoing orders…"
                return   // try again next frame once places are found
            }
            val prev = old.firstOrNull { it.pickup == o.pickup && it.drop == o.drop }
            out.add(Order(o.pickup, o.drop, p, d, o.fare, o.priority, prev?.picked ?: false))
        }
        val oldKeys = old.map { "${it.pickup}|${it.drop}" }.sorted()
        val newKeys = out.map { "${it.pickup}|${it.drop}" }.sorted()
        if (oldKeys != newKeys) store.saveOrders(out)
        syncNote = "📋 Synced ${out.size} ongoing order${if (out.size == 1) "" else "s"}"
    }

    /**
     * GPS-based progress. Place points are area-level, so thresholds are generous:
     * within ~500 m of a pickup → picked up; reached a drop (~500 m) then drove >1.5 km away → delivered.
     */
    private fun autoProgress(at: LatLng) {
        val list = store.orders()
        if (list.isEmpty()) return
        var changed = false
        val it = list.iterator()
        while (it.hasNext()) {
            val o = it.next()
            val key = "${o.pickup}|${o.drop}"
            if (!o.picked) {
                if (Geo.km(at, o.p) / Geo.ROAD < 0.5) { o.picked = true; changed = true }
            } else {
                val dd = Geo.km(at, o.d) / Geo.ROAD
                if (dd < 0.5) nearDrop.add(key)
                else if (key in nearDrop && dd > 1.5) { it.remove(); nearDrop.remove(key); changed = true }
            }
        }
        if (changed) store.saveOrders(list)
    }

    private fun money(v: Double) = String.format(Locale.US, "$%.2f", v)
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun render() {
        val ov = overlay ?: return
        val start = here
        val rows = ArrayList<Overlay.Row>()
        val boxes = ArrayList<Highlighter.Box>()
        val current = store.orders().map { Job(it.p, it.d, it.picked) }
        val dest = store.destination()
        val minutesLeft = dest?.let { (it.byMillis - System.currentTimeMillis()) / 60000.0 }

        // Goal row: where you must be, by when, and how much time you have to spare on the current plan.
        if (dest != null && start != null && minutesLeft != null) {
            val plan = Scorer.plan(start, current, dest.loc)
            val spare = minutesLeft - plan.minutes
            val net = Scorer.netEarnings(store.orders().map { it.fare to it.priority }, plan.km)
            val by = clock(dest.byMillis)
            rows.add(
                if (spare >= 0) Overlay.Row("🎯 ${dest.name} by $by · ${spare.toInt()} min spare · earning ${money(net)} net", Overlay.ORANGE)
                else Overlay.Row("⚠️ ${dest.name} by $by · running ${(-spare).toInt()} min late · earning ${money(net)} net", Overlay.RED)
            )
        }

        if (offers.isEmpty()) {
            val onBoard = current.size
            val note = syncNote ?: "Watching · no offers on screen"
            val boardNet = if (onBoard > 0 && start != null)
                " · ${money(Scorer.netEarnings(store.orders().map { it.fare to it.priority }, Scorer.plan(start, current, dest?.loc).km))} net" else ""
            rows.add(Overlay.Row(note + (if (onBoard > 0) " · $onBoard on board$boardNet" else "") + if (start == null) " · waiting for GPS" else "", if (syncNote != null) Overlay.ORANGE else Overlay.GREY))
        } else {
            val target = store.target
            for (o in offers.take(4)) {
                val title = "${o.pickup} → ${o.drop}" + if (o.type != null && !o.type.equals("Regular", true)) " · ${o.type}" else ""
                val p = resolver.cached(o.pickup)
                val d = resolver.cached(o.drop)
                if (p == null) resolver.resolveAsync(o.pickup)
                if (d == null) resolver.resolveAsync(o.drop)
                when {
                    start == null -> rows.add(Overlay.Row("$title\nwaiting for GPS", Overlay.GREY))
                    p == null || d == null -> rows.add(Overlay.Row("$title\nlocating place…", Overlay.GREY))
                    else -> {
                        val s = Scorer.score(start, current, p, d, o.fare, o.priority, target, dest?.loc, minutesLeft)
                        val (label, color) = when (s.verdict) {
                            Verdict.TAKE -> "✅ TAKE" to Overlay.GREEN
                            Verdict.OK -> "🟡 OK" to Overlay.AMBER
                            Verdict.SKIP -> "⛔ SKIP" to Overlay.RED
                            Verdict.LATE -> "⏰ LATE ${(-(s.spareMin ?: 0.0)).toInt()} min" to Overlay.RED
                        }
                        val timeLine = s.spareMin?.let { if (it >= 0) " · ${it.toInt()} min spare" else "" } ?: ""
                        val txt = "$label  +${money(s.netGain)} net · ${s.perHour.toInt()}/hr\n" +
                            "$title\n" +
                            "pickup ${one(if (current.isEmpty() && o.pickupKm != null) o.pickupKm else s.pickupKm)} km (max ${one(s.maxPickupKm)}) · min fare ${money(s.minFare)}\n" +
                            "+${one(s.extraKm)} km · +${s.extraMin.toInt()} min$timeLine"
                        if ((s.verdict == Verdict.TAKE || s.verdict == Verdict.OK) && alerted.add(o.key)) buzz()
                        if (o.bottom > o.top) when (s.verdict) {
                            Verdict.TAKE -> boxes.add(Highlighter.Box(o.top, o.bottom, Overlay.GREEN))
                            Verdict.OK -> boxes.add(Highlighter.Box(o.top, o.bottom, Overlay.AMBER))
                            else -> {}
                        }
                        rows.add(Overlay.Row(txt, color))
                    }
                }
            }
        }
        highlighter?.setBoxes(boxes)
        val sig = rows.joinToString("|") { it.text }
        if (sig != lastSig) { lastSig = sig; ov.setRows(rows) }
    }

    private fun clock(ms: Long) = java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(ms))

    private fun buzz() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(tick)
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        projection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        fused?.removeLocationUpdates(locationCallback)
        overlay?.remove()
        highlighter?.remove()
        try { recognizer.close() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
