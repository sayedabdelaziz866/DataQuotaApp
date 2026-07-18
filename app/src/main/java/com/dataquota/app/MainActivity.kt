package com.dataquota.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        binding.btnRegisterNetwork.setOnClickListener { registerCurrentNetwork() }
        binding.btnSaveLimit.setOnClickListener { saveLimit() }
        binding.btnToggleMonitoring.setOnClickListener { toggleMonitoring() }
        binding.btnResetUsage.setOnClickListener {
            quotaManager.resetUsage()
            Toast.makeText(this, "تم تصفير الاستهلاك", Toast.LENGTH_SHORT).show()
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

    private fun registerCurrentNetwork() {
        val bssid = WifiHelper.getCurrentBssid(this)
        val ssid = WifiHelper.getCurrentSsid(this)

        if (bssid == null) {
            Toast.makeText(
                this,
                "مش قادر أقرا بيانات الشبكة. اتأكد إنك متصل بالواي فاي وإن إذن الموقع متفعّل",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        quotaManager.setHomeNetwork(bssid, ssid ?: "شبكة غير معروفة الاسم")
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
            if (quotaManager.getHomeBssid() == null) {
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
        refreshUi()
    }

    private fun stopMonitoring() {
        quotaManager.setMonitoringEnabled(false)
        stopService(Intent(this, UsageMonitorService::class.java))
        stopService(Intent(this, QuotaVpnService::class.java).apply {
            action = QuotaVpnService.ACTION_STOP
        })
        quotaManager.setBlocked(false)
        refreshUi()
    }

    private fun refreshUi() {
        val homeSsid = quotaManager.getHomeSsid()
        binding.txtHomeNetwork.text =
            if (homeSsid != null) "شبكة البيت: $homeSsid" else "لسه متسجلتش شبكة بيت"

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

        binding.btnToggleMonitoring.text =
            if (quotaManager.isMonitoringEnabled()) "إيقاف المراقبة" else "بدء المراقبة"
    }
}
