package com.vex.client

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var pendingConfig: String? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cfg = pendingConfig
        pendingConfig = null
        if (res.resultCode == RESULT_OK && cfg != null) startVpnService(cfg)
        else notifyState("error", "دسترسی VPN داده نشد")
    }

    // درخواست اعلان فقط هنگام تلاش برای اتصال انجام می‌شود، نه بی‌مقدمه هنگام باز شدن برنامه.
    // رد کردن اجازه اعلان نباید جلوی خود اتصال VPN را بگیرد.
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cfg = pendingConfig
        if (cfg != null) continueStartAfterNotificationPermission(cfg)
        if (!granted && Build.VERSION.SDK_INT >= 33) {
            notifyState("info", "اجازه اعلان داده نشد؛ ممکن است اعلان در نوار اعلان‌ها نمایش داده نشود. اتصال VPN ادامه می‌یابد.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(VexBridge(this), "VexNative")
        web.loadUrl("file:///android_asset/index.html")

        VexVpnService.stateListener = { st, msg -> runOnUiThread { notifyState(st, msg) } }
        VexVpnService.statsListener = { dn, up, tdn, tup -> runOnUiThread { pushStats(dn, up, tdn, tup) } }
    }

    /** از JS صدا زده می‌شود. ابتدا اجازه اعلان را (در اندروید ۱۳+) می‌گیرد و سپس اجازه VPN را. */
    fun requestStart(config: String) {
        pendingConfig = config
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueStartAfterNotificationPermission(config)
        }
    }

    private fun continueStartAfterNotificationPermission(config: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConfig = config
            vpnPermission.launch(intent)
        } else {
            pendingConfig = null
            startVpnService(config)
        }
    }

    fun requestStop() {
        startService(Intent(this, VexVpnService::class.java).setAction(VexVpnService.ACTION_STOP))
    }

    private fun startVpnService(config: String) {
        val i = Intent(this, VexVpnService::class.java)
            .setAction(VexVpnService.ACTION_START)
            .putExtra(VexVpnService.EXTRA_CONFIG, config)
        ContextCompat.startForegroundService(this, i)
    }

    fun notifyState(st: String, msg: String) {
        web.evaluateJavascript("window.vexOnState && window.vexOnState('$st', ${JSONObject.quote(msg)})", null)
    }

    fun pushStats(dn: Long, up: Long, tdn: Long, tup: Long) {
        web.evaluateJavascript("window.vexOnStats && window.vexOnStats($dn,$up,$tdn,$tup)", null)
    }

    override fun onDestroy() {
        VexVpnService.stateListener = null
        VexVpnService.statsListener = null
        super.onDestroy()
    }
}
