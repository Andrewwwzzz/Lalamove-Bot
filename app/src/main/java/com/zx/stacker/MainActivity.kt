package com.zx.stacker

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Start/stop the reader, set your target, and manage the orders you already have on board. */
class MainActivity : Activity() {

    private val reqCapture = 11
    private val reqPerms = 12
    private var askedPerms = false
    private var resumeStart = false
    private var overlayAsked = false

    private lateinit var store: Store
    private lateinit var resolver: PlaceResolver
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var ordersBox: LinearLayout
    private lateinit var targetIn: EditText
    private lateinit var destStatus: TextView
    private lateinit var planBox: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        resolver = PlaceResolver(this)
        // Save any crash (app or reader service) so it shows on screen next time.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { Store(applicationContext).lastError = e.toString() + " @ " + (e.stackTrace.firstOrNull { it.className.contains("stacker") } ?: e.stackTrace.firstOrNull()) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Came back from the "Display over other apps" screen: carry on with Start automatically.
        if (resumeStart) { resumeStart = false; main.postDelayed({ onStartClicked() }, 400) }
    }

    // ---------- UI ----------

    private fun h(text: String) = TextView(this).apply {
        this.text = text; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        col.addView(TextView(this).apply { text = "Offer Reader  v$ver"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply {
            text = "Reads Lalamove offers on this screen and scores them against your S$/hr target using live GPS. You still tap Accept yourself."
            setPadding(0, dp(4), 0, dp(12))
        })

        startBtn = Button(this).apply {
            textSize = 17f
            setOnClickListener { onStartClicked() }
        }
        col.addView(startBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        status = TextView(this).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(status)

        col.addView(h("Minimum S$/hr net (default 14)"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        targetIn = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmt(store.target))
        }
        row.addView(targetIn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val v = targetIn.text.toString().toDoubleOrNull()
                if (v != null && v > 0) { store.target = v; toast("Target set to S$${fmt(v)}/hr"); refresh() }
            }
        })
        col.addView(row)

        col.addView(h("Be somewhere by a time"))
        destStatus = TextView(this)
        col.addView(destStatus)
        val destIn = AutoCompleteTextView(this).apply {
            hint = "Where (e.g. Paya Lebar)"; threshold = 1; setSingleLine()
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, ArrayList(Geo.AREAS.keys)))
        }
        col.addView(destIn)
        val goalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        goalRow.addView(Button(this).apply {
            text = "Pick time & set"
            setOnClickListener {
                val name = destIn.text.toString().trim()
                if (name.isEmpty()) { toast("Enter where you need to be"); return@setOnClickListener }
                val now = Calendar.getInstance()
                TimePickerDialog(this@MainActivity, { _, hh, mm ->
                    val by = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                    }.timeInMillis
                    resolver.resolveAsync(name) { loc ->
                        if (loc == null) toast("Couldn't find \"$name\". Try an area name from the list.")
                        else { store.saveDestination(Destination(name, loc, by)); destIn.setText(""); refresh() }
                    }
                }, (now.get(Calendar.HOUR_OF_DAY) + 1) % 24, 0, false).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        goalRow.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { store.saveDestination(null); refresh() }
        })
        col.addView(goalRow)

        col.addView(h("Route plan"))
        planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(planBox)
        col.addView(Button(this).apply { text = "Refresh plan"; setOnClickListener { refresh() } })

        col.addView(h("Orders on board"))
        ordersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ordersBox)

        col.addView(h("Add an order you accepted"))
        val names = ArrayList(Geo.AREAS.keys)
        fun place(hint: String) = AutoCompleteTextView(this).apply {
            this.hint = hint; threshold = 1; setSingleLine()
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, names))
        }
        val pIn = place("Pickup (e.g. Bugis)")
        val dIn = place("Drop-off (e.g. Ayer Rajah)")
        val fIn = EditText(this).apply { hint = "Fare shown (S$)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val xIn = EditText(this).apply { hint = "Priority fee (optional)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        col.addView(pIn); col.addView(dIn); col.addView(fIn); col.addView(xIn)
        col.addView(Button(this).apply {
            text = "Add to orders on board"
            setOnClickListener {
                val pn = pIn.text.toString().trim(); val dn = dIn.text.toString().trim()
                val fare = fIn.text.toString().toDoubleOrNull() ?: 0.0
                val pri = xIn.text.toString().toDoubleOrNull() ?: 0.0
                if (pn.isEmpty() || dn.isEmpty()) { toast("Enter pickup and drop-off"); return@setOnClickListener }
                isEnabled = false
                resolver.resolveAsync(pn) { p ->
                    resolver.resolveAsync(dn) { d ->
                        isEnabled = true
                        when {
                            p == null -> toast("Couldn't find \"$pn\". Try an area name from the list.")
                            d == null -> toast("Couldn't find \"$dn\". Try an area name from the list.")
                            else -> {
                                val list = store.orders()
                                list.add(Order(pn, dn, p, d, fare, pri, false))
                                store.saveOrders(list)
                                pIn.setText(""); dIn.setText(""); fIn.setText(""); xIn.setText("")
                                refresh()
                            }
                        }
                    }
                }
            }
        })

        col.addView(h("Last screen read (troubleshooting)"))
        col.addView(TextView(this).apply {
            tag = "dump"; textSize = 11f; alpha = 0.8f; setTextIsSelectable(true)
        })

        col.addView(h("How to use"))
        col.addView(TextView(this).apply {
            text = "1. Tap Start and allow location, notifications and \"Display over other apps\".\n" +
                "2. When Android asks what to share, choose \"Entire screen\" (not a single app).\n" +
                "3. Open Lalamove's Pickup screen. The bubble shows each offer's verdict; tap it to shrink, drag to move.\n" +
                "4. After you accept a job, open Record → Ongoing for a second. Your ongoing orders sync automatically, so new offers are scored as stacks.\n" +
                "5. Pickups and drops tick off by GPS (within ~500 m). Use Picked / Done below if it gets one wrong.\n" +
                "6. Need to be somewhere by a time? Set a goal above. The route plan ends there, offers heading that way score higher, and ones that would make you late show ⏰ LATE.\n\n" +
                "Take-home = 78.5% of fare + 95% of priority fee. Petrol S$0.25/km, other costs S$1/hr, 25 km/h + 5 min per stop. Distances are estimates."
            alpha = 0.8f
        })

        return ScrollView(this).apply { addView(col) }
    }

    private fun refresh() {
        val on = ReaderService.running
        startBtn.text = if (on) "Stop reading" else "Start reading offers"
        (window.decorView.findViewWithTag<TextView>("dump"))?.text = store.lastScreen.ifEmpty { "Nothing read yet. Start reading, open Lalamove, then come back here." }
        val err = store.lastError
        status.text = (if (on) "Running. Open Lalamove → Pickup." else "Stopped.") +
            (if (err != null) "\n⚠️ Last error: $err" else "")

        val dest = store.destination()
        destStatus.text = if (dest == null) "Optional. Without one, offers are scored on S$/hr only."
            else "🎯 ${dest.name} by ${clock(dest.byMillis)}. Offers that would make you late show ⏰ LATE; jobs heading that way score higher."
        drawPlan(dest)

        ordersBox.removeAllViews()
        val list = store.orders()
        if (list.isEmpty()) {
            ordersBox.addView(TextView(this).apply { text = "None. New offers are scored from where you are."; alpha = 0.7f })
        }
        list.forEachIndexed { i, o ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = "${o.pickup} → ${o.drop}\nS$${fmt(o.fare)}${if (o.priority > 0) " + S$${fmt(o.priority)}" else ""} · ${if (o.picked) "picked up" else "to pick up"}"
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!o.picked) row.addView(Button(this).apply {
                text = "Picked"; textSize = 12f
                setOnClickListener { val l = store.orders(); l[i].picked = true; store.saveOrders(l); refresh() }
            })
            row.addView(Button(this).apply {
                text = "Done"; textSize = 12f
                setOnClickListener { val l = store.orders(); l.removeAt(i); store.saveOrders(l); refresh() }
            })
            ordersBox.addView(row)
        }
    }

    private fun clock(ms: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))

    /** Best order for your jobs from where you are, ending at your goal, with arrival times. */
    private fun drawPlan(dest: Destination?) {
        planBox.removeAllViews()
        val orders = store.orders()
        val here = store.here()
        fun line(t: String, bold: Boolean = false) = planBox.addView(TextView(this).apply {
            text = t; setPadding(0, dp(3), 0, dp(3)); if (bold) typeface = Typeface.DEFAULT_BOLD
        })
        if (orders.isEmpty() && dest == null) { line("No jobs and no goal yet."); return }
        if (here == null) { line("Waiting for GPS. Start reading once so the app knows where you are."); return }
        val plan = Scorer.plan(here, orders.map { Job(it.p, it.d, it.picked) }, dest?.loc)
        val now = System.currentTimeMillis()
        plan.stops.forEachIndexed { i, st ->
            val at = clock(now + (st.etaMin * 60_000).toLong())
            val what = if (st.job < 0) "🎯 ${dest!!.name}" else {
                val o = orders[st.job]
                if (st.pickup) "Pick up · ${o.pickup}" else "Drop · ${o.drop}"
            }
            line("${i + 1}. $at   $what  (${String.format(Locale.US, "%.1f", st.legKm)} km)", st.job < 0)
        }
        val net = Scorer.netEarnings(orders.map { it.fare to it.priority }, plan.km)
        val sum = "Total ${String.format(Locale.US, "%.1f", plan.km)} km · ~${plan.minutes.toInt()} min · earn ~S$${String.format(Locale.US, "%.2f", net)} net"
        if (dest != null) {
            val spare = (dest.byMillis - now) / 60000.0 - plan.minutes
            line(if (spare >= 0) "$sum · ${spare.toInt()} min spare for more jobs" else "$sum · ⚠️ ${(-spare).toInt()} min late. Drop a job or leave now.", true)
        } else line(sum, true)
    }

    // ---------- Start flow: permissions → overlay → screen capture → service ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        if (ReaderService.running) {
            startService(Intent(this, ReaderService::class.java).setAction(ReaderService.ACTION_STOP))
            main.postDelayed({ refresh() }, 400)
            return
        }
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty() && !askedPerms) {
            askedPerms = true
            requestPermissions(need.toTypedArray(), reqPerms)
            return
        }
        // Some phones keep reporting "not allowed" after you switch it on, so only send you there once.
        if (!Settings.canDrawOverlays(this) && !overlayAsked) {
            overlayAsked = true
            resumeStart = true
            toast("Turn on \"Display over other apps\" for Offer Reader, then press back.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerms) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) toast("Without location, offers can't be scored from where you are.")
            onStartClicked()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) { toast("Screen sharing was not allowed."); return }
        store.lastError = null
        val i = Intent(this, ReaderService::class.java)
            .putExtra(ReaderService.EXTRA_CODE, resultCode)
            .putExtra(ReaderService.EXTRA_DATA, data)
        startForegroundService(i)
        main.postDelayed({ refresh() }, 600)
        toast("Reading started. Open Lalamove.")
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
