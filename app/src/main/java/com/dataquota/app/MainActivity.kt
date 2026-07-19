package com.dataquota.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dataquota.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var quotaManager: QuotaManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startMonitoring()
        } else {
            Toast.makeText(this, "لازم توافق على إذن الـ VPN عشان يشتغل القطع", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!locationGranted) {
            Toast.makeText(
                this,
                "محتاجين إذن الموقع عشان نقدر نتعرف على شبكة الواي فاي المتصل بيها",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        quotaManager = QuotaManager(this)

        requestNeededPermissions()

        // Safety net: if monitoring was left "on" from a previous session
        // (e.g. before this app update), make sure the service is actually
        // alive right now - not just trusting the stored flag.
        if (quotaManager.isMonitoringEnabled()) {
            startForegroundService(Intent(this, UsageMonitorService::class.java))
            QuotaCheckWorker.schedule(this)
        }

        binding.btnRegisterNetwork.setOnClickListener { registerCurrentNetwork() }
        binding.btnOpenLocationSettings.setOnClickListener { openAppLocationSettings() }
        binding.btnDisableBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimization() }
        binding.btnAddManualBssid.setOnClickListener { addManualBssid() }
        binding.btnSaveLimit.setOnClickListener { saveLimit() }
        binding.btnToggleMonitoring.setOnClickListener { toggleMonitoring() }
        binding.btnResetUsage.setOnClickListener {
            quotaManager.resetUsage()
            Toast.makeText(this, "تم تصفير الاستهلاك", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
        binding.btnOpenUsageAccessSettings.setOnClickListener {
            TopAppsHelper.openUsageAccessSettings(this)
        }
        binding.btnApplyUninstallProtection.setOnClickListener {
            if (!DeviceAdminReceiver.isDeviceOwner(this)) {
                Toast.makeText(
                    this,
                    "التطبيق لسه مش Device Owner - محتاج إعداد عن طريق QR وقت الفورمات",
                    Toast.LENGTH_LONG
                ).show()
            } else if (DeviceAdminReceiver.isUninstallBlocked(this)) {
                DeviceAdminReceiver.removeUninstallProtection(this)
                Toast.makeText(this, "التطبيق بقى عادي، يقدر يتحذف", Toast.LENGTH_SHORT).show()
            } else {
                DeviceAdminReceiver.applyProtections(this)
                Toast.makeText(this, "تم تفعيل الحماية من الحذف", Toast.LENGTH_SHORT).show()
            }
            refreshUi()
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshRunnable)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 5000)
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "التطبيق مستثنى بالفعل من توفير البطارية", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun openAppLocationSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
        Toast.makeText(
            this,
            "روح على Permissions > Location واختار \"Allow all the time\"",
            Toast.LENGTH_LONG
        ).show()
    }

    private val bssidPattern = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    private fun addManualBssid() {
        val text = binding.inputManualBssid.text.toString().trim()
        if (!bssidPattern.matches(text)) {
            Toast.makeText(
                this,
                "الصيغة لازم تكون زي كده: bc:96:80:ad:6b:42",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        quotaManager.addManualBssid(text)
        binding.inputManualBssid.text?.clear()
        Toast.makeText(this, "تمت الإضافة", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun registerCurrentNetwork() {
        val bssid = WifiHelper.getCurrentBssid(this)
        val gatewayIp = WifiHelper.getCurrentGatewayIp(this)
        val ssid = WifiHelper.getCurrentSsid(this)

        if (bssid == null && gatewayIp == null) {
            Toast.makeText(
                this,
                "مش قادر أقرا بيانات الشبكة. اتأكد إنك متصل بالواي فاي",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        quotaManager.setHomeNetwork(bssid, ssid ?: "شبكة غير معروفة الاسم", gatewayIp)
        Toast.makeText(this, "تم تسجيل \"$ssid\" كشبكة البيت", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun saveLimit() {
        val gbText = binding.inputLimitGb.text.toString()
        val gb = gbText.toDoubleOrNull()
        if (gb == null || gb <= 0) {
            Toast.makeText(this, "اكتب رقم جيجابايت صحيح", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = (gb * 1024L * 1024L * 1024L).toLong()
        quotaManager.setLimitBytes(bytes)
        Toast.makeText(this, "تم حفظ الحد: $gb GB", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun toggleMonitoring() {
        if (quotaManager.isMonitoringEnabled()) {
            stopMonitoring()
        } else {
            if (quotaManager.getHomeBssids().isEmpty() && quotaManager.getHomeGatewayIp() == null) {
                Toast.makeText(this, "سجل شبكة البيت الأول", Toast.LENGTH_SHORT).show()
                return
            }
            if (quotaManager.getLimitBytes() <= 0) {
                Toast.makeText(this, "حدد عدد الجيجات الأول", Toast.LENGTH_SHORT).show()
                return
            }
            // VpnService.prepare() returns non-null Intent if the user
            // hasn't approved this app as a VPN yet.
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPrepareLauncher.launch(prepareIntent)
            } else {
                startMonitoring()
            }
        }
    }

    private fun startMonitoring() {
        quotaManager.setMonitoringEnabled(true)
        startForegroundService(Intent(this, UsageMonitorService::class.java))
        QuotaCheckWorker.schedule(this)
        refreshUi()
    }

    private fun stopMonitoring() {
        quotaManager.setMonitoringEnabled(false)
        stopService(Intent(this, UsageMonitorService::class.java))
        stopService(Intent(this, QuotaVpnService::class.java).apply {
            action = QuotaVpnService.ACTION_STOP
        })
        quotaManager.setBlocked(false)
        QuotaCheckWorker.cancel(this)
        refreshUi()
    }

    private fun refreshUi() {
        val homeSsid = quotaManager.getHomeSsid()
        val bssidCount = quotaManager.getHomeBssids().size
        binding.txtHomeNetwork.text = if (homeSsid != null) {
            "شبكة البيت: $homeSsid (مسجل $bssidCount BSSID)"
        } else {
            "لسه متسجلتش شبكة بيت"
        }

        val limitBytes = quotaManager.getLimitBytes()
        val usedBytes = quotaManager.getUsedBytes()
        val limitGb = limitBytes / 1024.0 / 1024.0 / 1024.0
        val usedGb = usedBytes / 1024.0 / 1024.0 / 1024.0

        binding.txtUsage.text = if (limitBytes > 0) {
            String.format("الاستهلاك: %.2f GB من %.2f GB", usedGb, limitGb)
        } else {
            String.format("الاستهلاك: %.2f GB (لسه مفيش حد متحدد)", usedGb)
        }

        binding.txtCurrentWifi.text =
            "الواي فاي الحالي: ${WifiHelper.getCurrentSsid(this) ?: "مش متصل"}"

        binding.txtStatus.text = when {
            quotaManager.isBlocked() -> "الحالة: النت مقطوع (تخطى الحد المسموح)"
            quotaManager.isMonitoringEnabled() -> "الحالة: بيراقب الاستهلاك"
            else -> "الحالة: المراقبة متوقفة"
        }

        val lastCheck = quotaManager.getLastCheckTime()
        binding.txtLastCheck.text = if (lastCheck == 0L) {
            "آخر فحص: لسه مفيش (المراقبة متبدأتش)"
        } else {
            val secondsAgo = (System.currentTimeMillis() - lastCheck) / 1000
            "آخر فحص كان من: $secondsAgo ثانية (المفروض يكون دايمًا أقل من 20)"
        }

        val lastBoot = quotaManager.getLastBootReceiverFired()
        val bootError = quotaManager.getLastBootReceiverError()
        val minsAgo = if (lastBoot > 0) (System.currentTimeMillis() - lastBoot) / 60000 else 0
        binding.txtBootReceiverStatus.text = when {
            lastBoot == 0L -> "تشخيص الريستارت: BootReceiver لسه متنداش خالص من وقت التثبيت"
            bootError != null -> "تشخيص الريستارت: BootReceiver اشتغل بس فشل - $bootError"
            else -> "تشخيص الريستارت: آخر مرة BootReceiver اشتغل من $minsAgo دقيقة"
        }

        val vpnConsentGranted = android.net.VpnService.prepare(this) == null
        binding.txtVpnStatus.text = when {
            !vpnConsentGranted -> "تشخيص القطع: إذن الـ VPN مش متفعّل - القطع مش هيشتغل خالص! دوس بدء المراقبة عشان توافق"
            quotaManager.isBlocked() && !quotaManager.isVpnActuallyEstablished() ->
                "تشخيص القطع: التطبيق بيحاول يقطع بس النفق مش شغال فعليًا - النت شغال رغم الحد"
            quotaManager.isBlocked() && quotaManager.isVpnActuallyEstablished() ->
                "تشخيص القطع: النفق شغال فعليًا، النت مقطوع بالفعل"
            else -> "تشخيص القطع: مش محتاج يقطع دلوقتي"
        }

        binding.btnToggleMonitoring.text =
            if (quotaManager.isMonitoringEnabled()) "إيقاف المراقبة" else "بدء المراقبة"

        binding.txtDeviceOwnerStatus.text = when {
            !DeviceAdminReceiver.isDeviceOwner(this) -> "حماية الحذف: غير مفعّلة (لسه عادي، ممكن يتحذف)"
            DeviceAdminReceiver.isUninstallBlocked(this) -> "حماية الحذف: مفعّلة (التطبيق محمي من الحذف)"
            else -> "حماية الحذف: متوقفة (التطبيق Device Owner بس بيتحذف عادي)"
        }
        binding.btnApplyUninstallProtection.text = if (DeviceAdminReceiver.isDeviceOwner(this) &&
            DeviceAdminReceiver.isUninstallBlocked(this)
        ) {
            "أوقف الحماية من الحذف"
        } else {
            "فعّل الحماية من الحذف"
        }

        updateDailyChart()
        updateTopApps()
    }

    private fun updateDailyChart() {
        val daily = quotaManager.getDailyUsage(7)
        val points = daily.map { (label, bytes) ->
            Pair(label, (bytes / 1024.0 / 1024.0 / 1024.0).toFloat()) // GB
        }
        binding.dailyChart.setData(points)
    }

    private fun updateTopApps() {
        val container = binding.topAppsContainer
        container.removeAllViews()

        if (!TopAppsHelper.hasUsageAccess(this)) {
            addTopAppsMessage("محتاج تفعّل صلاحية Usage Access الأول (الزرار اللي فوق)")
            return
        }

        // Usage since the start of today.
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        val topApps = TopAppsHelper.getTopApps(this, cal.timeInMillis, limit = 5)

        if (topApps.isEmpty()) {
            addTopAppsMessage("لسه مفيش بيانات استهلاك كافية النهاردة")
            return
        }

        topApps.forEach { app ->
            val mb = app.bytes / 1024.0 / 1024.0
            val text = TextView(this).apply {
                text = String.format("%s — %.1f MB", app.appName, mb)
                textSize = 15f
                setPadding(0, 8, 0, 8)
                gravity = Gravity.START
            }
            container.addView(text)
        }
    }

    private fun addTopAppsMessage(message: String) {
        val text = TextView(this).apply {
            text = message
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        binding.topAppsContainer.addView(text)
    }
}
