package com.watchmouse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.watchmouse.ble.BleServerService
import com.watchmouse.mouse.MouseAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnPrimaryAction: Button
    private var revocationFlowActive = false
    private var pendingRevocationStep: RevocationStep? = null
    private var waitingForAccessibilitySettings = false

    private enum class RevocationStep {
        ACCESSIBILITY,
        OVERLAY,
        APP_PERMISSIONS
    }

    private val permissionRequestCode = 1001
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        handleRevocationSettingsReturn()
        if (waitingForAccessibilitySettings) {
            waitingForAccessibilitySettings = false
            Toast.makeText(
                this,
                if (isAccessibilityServiceEnabled()) {
                    R.string.accessibility_enabled_toast
                } else {
                    R.string.accessibility_still_needed_toast
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnPrimaryAction = findViewById(R.id.btnPrimaryAction)
        findViewById<Button>(R.id.btnStopControlAndHideCursor).setOnClickListener {
            stopControlAndHideCursor()
        }
        findViewById<Button>(R.id.btnRevokeAuthorizations).setOnClickListener {
            confirmRevokeAuthorizations()
        }
        findViewById<android.view.View>(R.id.rowAccessibilityPermission).setOnClickListener {
            if (!isAccessibilityServiceEnabled()) openAccessibilitySettings()
        }

        btnPrimaryAction.setOnClickListener {
            if (BleServerService.isRunning) {
                stopControlAndHideCursor()
                return@setOnClickListener
            }

            when {
                !hasAllPermissions() -> requestNecessaryPermissions()
                !isAccessibilityServiceEnabled() -> openAccessibilitySettings()
                !canDrawOverlays() -> openOverlaySettings()
                else -> startService()
            }
        }
    }

    /**
     * 更新界面状态
     */
    private fun updateUI() {
        val hasPermissions = hasAllPermissions()
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = canDrawOverlays()

        setChecklistStatus(tvBluetoothStatus, hasPermissions)
        setChecklistStatus(tvAccessibilityStatus, accessibilityEnabled)
        setChecklistStatus(tvOverlayStatus, overlayEnabled)

        if (BleServerService.isRunning) {
            tvStatus.text = getString(R.string.status_waiting_watch)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connected))
            tvStatusDetail.text = getString(R.string.status_detail_waiting_watch)
            btnPrimaryAction.text = getString(R.string.action_stop)
        } else {
            val nextStep = when {
                !hasPermissions -> R.string.status_step_permissions
                !accessibilityEnabled -> R.string.status_step_accessibility
                !overlayEnabled -> R.string.status_step_overlay
                else -> R.string.status_step_start
            }
            tvStatus.text = getString(nextStep)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.disconnected))
            tvStatusDetail.text = getString(
                if (!hasPermissions) R.string.status_detail_initial
                else if (!accessibilityEnabled) R.string.status_detail_accessibility
                else R.string.status_detail_initial
            )
            btnPrimaryAction.text = when {
                !hasPermissions -> getString(R.string.action_permissions)
                !accessibilityEnabled -> getString(R.string.action_accessibility)
                !overlayEnabled -> getString(R.string.action_overlay)
                else -> getString(R.string.action_start)
            }
        }
    }

    private fun setChecklistStatus(view: TextView, enabled: Boolean) {
        view.text = getString(if (enabled) R.string.status_done else R.string.status_needed)
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.connected else R.color.text_secondary
            )
        )
    }

    private fun startService() {
        BleServerService.start(this)

        // 服务启动只代表开始等待手表；连接状态由 BLE 回调负责显示光标。
        android.os.Handler(mainLooper).postDelayed({
            updateUI()
        }, 500)
    }

    /** 停止手表控制并移除悬浮光标；保留已授予的系统权限。 */
    private fun stopControlAndHideCursor() {
        if (BleServerService.isRunning) {
            BleServerService.stop(this)
        }
        MouseAccessibilityService.getInstance()?.hideCursor()
        window.decorView.postDelayed({ updateUI() }, 300)
        Toast.makeText(this, R.string.cursor_closed_toast, Toast.LENGTH_SHORT).show()
    }

    // ========== 权限检查 ==========

    /**
     * 是否拥有所有必要权限
     */
    private fun hasAllPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取需要申请的权限列表
     */
    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            emptyArray()
        }
    }

    /**
     * 请求必要权限
     */
    private fun requestNecessaryPermissions() {
        val permissions = getRequiredPermissions()
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed, permissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            updateUI()
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========== 无障碍服务 ==========

    /**
     * 检查无障碍服务是否已启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return MouseAccessibilityService.isServiceEnabled()
    }

    /**
     * 打开无障碍设置页面
     */
    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_guide_title)
            .setMessage(R.string.accessibility_guide_message)
            .setNegativeButton(R.string.accessibility_guide_later, null)
            .setPositiveButton(R.string.accessibility_guide_open_settings) { _, _ ->
                waitingForAccessibilitySettings = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    // ========== 悬浮窗权限 ==========

    /**
     * 检查悬浮窗权限
     */
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * 打开悬浮窗设置
     */
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    /** 停止服务后，引导用户逐项关闭系统授权。系统要求用户在设置页确认。 */
    private fun confirmRevokeAuthorizations() {
        AlertDialog.Builder(this)
            .setTitle(R.string.revoke_authorizations_title)
            .setMessage(R.string.revoke_authorizations_message)
            .setNegativeButton(R.string.accessibility_guide_later, null)
            .setPositiveButton(R.string.revoke_authorizations_continue) { _, _ ->
                if (BleServerService.isRunning) {
                    BleServerService.stop(this)
                }
                MouseAccessibilityService.getInstance()?.hideCursor()
                revocationFlowActive = true
                pendingRevocationStep = null
                continueRevocationFlow()
            }
            .show()
    }

    private fun continueRevocationFlow() {
        when {
            isAccessibilityServiceEnabled() -> {
                pendingRevocationStep = RevocationStep.ACCESSIBILITY
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            canDrawOverlays() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                pendingRevocationStep = RevocationStep.OVERLAY
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            hasAnyGrantedRuntimePermission() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ permits an app to revoke its own runtime permissions.
                    // The system applies the change when it kills this app's process.
                    revocationFlowActive = false
                    pendingRevocationStep = null
                    Toast.makeText(
                        this,
                        R.string.revoke_authorizations_runtime_revoking,
                        Toast.LENGTH_LONG
                    ).show()
                    revokeSelfPermissionsOnKill(getRequiredPermissions().toList())
                    // The API defers revocation while this app is in the foreground.
                    // Exit after the request has reached the system so it can apply now.
                    android.os.Handler(mainLooper).postDelayed({
                        finishAffinity()
                        kotlin.system.exitProcess(0)
                    }, 1000)
                } else {
                    pendingRevocationStep = RevocationStep.APP_PERMISSIONS
                    openAppDetailsSettings()
                }
            }
            else -> finishRevocationFlow()
        }
    }

    private fun handleRevocationSettingsReturn() {
        if (!revocationFlowActive) return

        val step = pendingRevocationStep ?: return
        pendingRevocationStep = null
        val stillEnabled = when (step) {
            RevocationStep.ACCESSIBILITY -> isAccessibilityServiceEnabled()
            RevocationStep.OVERLAY -> canDrawOverlays()
            RevocationStep.APP_PERMISSIONS -> hasAnyGrantedRuntimePermission()
        }

        if (stillEnabled) {
            revocationFlowActive = false
            Toast.makeText(this, R.string.revoke_authorizations_incomplete, Toast.LENGTH_LONG).show()
            return
        }

        if (step == RevocationStep.APP_PERMISSIONS) {
            finishRevocationFlow()
        } else {
            continueRevocationFlow()
        }
    }

    private fun hasAnyGrantedRuntimePermission(): Boolean {
        return getRequiredPermissions().any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openAppDetailsSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun finishRevocationFlow() {
        revocationFlowActive = false
        pendingRevocationStep = null
        updateUI()
        Toast.makeText(this, R.string.revoke_authorizations_complete, Toast.LENGTH_LONG).show()
    }

}
