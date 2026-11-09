package com.womp.recvault

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import recvault.core.Cloud

class MainActivity : Activity() {
    companion object {
        const val EXTRA_RESUME = "resume"
        private const val RC_GOOGLE = 41
        private const val RC_PERMS = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var recBtn: Button
    private lateinit var warn: TextView

    private val refresher = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        askPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (data != null && data.scheme == "recvault" && data.host == "oauth") {
            scope.launch {
                try {
                    OneDriveClient.finishSignIn(this@MainActivity, data)
                    toast("OneDrive connected ✓")
                    SyncWorker.kick(this@MainActivity)
                } catch (e: Exception) {
                    alert("OneDrive sign-in failed", e.message ?: e.toString())
                }
            }
        }
        if (i?.getBooleanExtra(EXTRA_RESUME, false) == true && prefs.wantRecording && !RecorderService.running) {
            startRecording()
        }
    }

    // ---------- UI (plain views, no libraries) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "RecVault"; textSize = 28f; setTypeface(typeface, Typeface.BOLD)
        })
        warn = TextView(this).apply { setTextColor(Color.rgb(255, 120, 90)); textSize = 15f }
        col.addView(warn)
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 14f; setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(status)

        recBtn = button("Start recording") {
            if (RecorderService.running) confirmStop() else startRecording()
        }.apply { textSize = 20f }
        col.addView(recBtn)
        col.addView(button("Back up now") { SyncWorker.kick(this); toast("Backup started") })
        col.addView(button("Connect Google Drive") { connectGoogle() })
        if (prefs.configured(Cloud.ONEDRIVE)) col.addView(button("Connect OneDrive") { connectOneDrive() })
        col.addView(button("Let RecVault run unrestricted (battery)") { battery() })

        for (c in Cloud.values()) {
            if (!prefs.configured(c)) continue
            col.addView(CheckBox(this).apply {
                text = if (c == Cloud.DRIVE) "Back up to Google Drive" else "Back up to OneDrive"
                isChecked = prefs.enabled(c)
                setOnCheckedChangeListener { _, on -> prefs.setEnabled(c, on); SyncWorker.kick(this@MainActivity) }
            })
        }

        val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labelEdit = EditText(this).apply { setText(prefs.deviceLabel); hint = "device label" }
        labelRow.addView(TextView(this).apply { text = "Phone name: " })
        labelRow.addView(labelEdit, LinearLayout.LayoutParams(0, -2, 1f))
        labelRow.addView(button("Save") {
            if (RecorderService.running) { toast("Stop recording first"); return@button }
            prefs.deviceLabel = labelEdit.text.toString()
            prefs.driveDevId = null
            labelEdit.setText(prefs.deviceLabel)
            toast("Saved: cloud folder is now ${prefs.deviceLabel}")
        })
        col.addView(labelRow)

        // Support: direct links, no middleman, 100% to the developer. Opens the Venmo / Cash App app if installed.
        col.addView(TextView(this).apply {
            text = "Support RecVault"; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = "RecVault is free, with no ads. If it helps you, you can chip in directly."
            textSize = 13f
        })
        col.addView(button("Venmo  @Timothy-Lee-427") { openLink(Config.VENMO_URL) })
        col.addView(button("Cash App  \$timwlee") { openLink(Config.CASHAPP_URL) })

        col.addView(TextView(this).apply {
            textSize = 13f; setPadding(0, dp(16), 0, 0)
            text = "RecVault never deletes or edits anything on this phone. Recordings are in " +
                "${ChunkStore.relPath()} and stay there even if this app is removed.\n" +
                "Cloud copies: Google Drive › RecVault › ${prefs.deviceLabel}   and   OneDrive › Apps › RecVault › ${prefs.deviceLabel}"
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun render() {
        status.text = StatusText.long(this)
        recBtn.text = if (RecorderService.running) "■ Stop recording" else "● Start recording"
        val w = StringBuilder()
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName))
            w.append("⚠ Battery optimization is ON: the phone may kill RecVault. Tap the battery button.\n")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            w.append("⚠ Notifications are blocked: alarms can't reach you.\n")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            w.append("⚠ Microphone permission missing.\n")
        warn.text = w.toString().trim()
    }

    // ---------- actions ----------

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            want += Manifest.permission.POST_NOTIFICATIONS
            want += Manifest.permission.READ_MEDIA_AUDIO
        } else want += Manifest.permission.READ_EXTERNAL_STORAGE
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), RC_PERMS)
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); toast("Allow the microphone first"); return
        }
        RecorderService.start(this)
        toast("Recording")
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Stop recording?")
            .setMessage("The current chunk is saved and backed up. Recording stays off until you press Start.")
            .setPositiveButton("Stop") { _, _ -> RecorderService.stop(this) }
            .setNegativeButton("Keep recording", null)
            .show()
    }

    private fun connectGoogle() {
        scope.launch {
            try {
                val res = Identity.getAuthorizationClient(this@MainActivity)
                    .authorize(DriveClient.request(null)).await()
                if (res.hasResolution()) {
                    startIntentSenderForResult(res.pendingIntent!!.intentSender, RC_GOOGLE, null, 0, 0, 0, null)
                } else {
                    rememberGoogle(res)
                }
            } catch (e: Exception) {
                alert("Google sign-in failed", e.message ?: e.toString())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun rememberGoogle(res: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        res.toGoogleSignInAccount()?.email?.let { prefs.googleAccount = it }
        toast("Google Drive connected ✓ ${prefs.googleAccount ?: ""}")
        SyncWorker.kick(this)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_GOOGLE) {
            try {
                val res = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
                rememberGoogle(res)
            } catch (e: Exception) {
                alert("Google sign-in failed", e.message ?: e.toString())
            }
        }
    }

    private fun connectOneDrive() {
        if (Config.MS_CLIENT_ID.isBlank()) { alert("OneDrive", "This build has no Microsoft app ID yet."); return }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OneDriveClient.signInUrl(this))))
    }

    @Suppress("BatteryLife")
    private fun battery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } else {
            // Samsung: also make sure it isn't in "Sleeping apps". App info > Battery > Unrestricted.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            toast("Battery › Unrestricted")
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            alert("Couldn't open link", url)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun alert(t: String, m: String) = AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show()
}
