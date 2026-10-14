package com.watchmouse.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.watchmouse.protocol.MouseCommand
import java.util.UUID

/**
 * BLE GATT 服务端管理
 * 手机作为 GATT Server 对外广播，等待手表连接
 *
 * Service UUID:        0000ffe0-0000-1000-8000-00805f9b34fb
 * Characteristic UUID: 0000ffe1-0000-1000-8000-00805f9b34fb
 */
class BleServerManager(private val context: Context) {

    private val tag = "BleServerManager"

    // 服务和特征值 UUID（与手表端对应）
    val serviceUuid: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val charUuid: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val cccUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // 设备名称
    private val deviceName = "WatchMouse"

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var inputCharacteristic: BluetoothGattCharacteristic? = null

    // 连接状态
    var isAdvertising = false
        private set
    var lastErrorMessage: String? = null
        private set
    var connectedDevice: BluetoothDevice? = null
        private set

    // 回调
    var onStateChange: ((state: State) -> Unit)? = null
    var onCommandReceived: ((command: MouseCommand) -> Unit)? = null

    enum class State {
        IDLE, ADVERTISING, CONNECTED, ERROR
    }

    /**
     * 初始化
     */
    fun init(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(tag, "设备不支持蓝牙")
            return false
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Log.e(tag, "蓝牙未开启")
            return false
        }

        // 设置设备名称
        try {
            bluetoothAdapter?.name = deviceName
        } catch (e: SecurityException) {
            Log.w(tag, "无法修改蓝牙名称: ${e.message}")
        }

        return true
    }

    /**
     * 开启 GATT 服务并开始广播
     */
    fun start(): Boolean {
        lastErrorMessage = null
        if (!init()) return false

        // 创建 GATT 服务
        if (!createGattService()) {
            Log.e(tag, "创建 GATT 服务失败")
            return false
        }

        // addService 是异步操作。等 onServiceAdded 成功后再广播，避免手表
        // 扫描到手机后立刻发现服务，却拿到一个尚未注册完成的 GATT 数据库。
        Log.d(tag, "GATT 服务注册请求已提交，等待系统回调")
        return true
    }

    /**
     * 停止服务
     */
    fun stop() {
        stopAdvertising()
        closeGattServer()
        isAdvertising = false
        connectedDevice = null
        onStateChange?.invoke(State.IDLE)
        Log.d(tag, "BLE 服务端已停止")
    }

    /**
     * 创建 GATT 服务
     */
    private fun createGattService(): Boolean {
        gattServer = bluetoothManager?.openGattServer(context, gattCallback)
        if (gattServer == null) {
            Log.e(tag, "无法打开 GATT Server")
            return false
        }

        // 创建服务
        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // 创建写入特征值（手表 → 手机）
        inputCharacteristic = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // 添加描述符（用于通知）
        val cccDescriptor = BluetoothGattDescriptor(
            cccUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        cccDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        inputCharacteristic?.addDescriptor(cccDescriptor)

        service.addCharacteristic(inputCharacteristic)

        // 添加服务
        return gattServer?.addService(service) == true
    }

    /**
     * 开始广播
     */
    private fun startAdvertising(): Boolean {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            lastErrorMessage = "设备未提供 BLE 广播器"
            Log.e(tag, "设备不支持 BLE 广播")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // 把 128-bit UUID 放在主广播包中；设备名放进独立扫描响应包，
        // 避免两者挤在同一个传统广播包里超过 31 字节。
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        return try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            true
        } catch (e: Exception) {
            lastErrorMessage = "提交 BLE 广播失败：${e.message ?: e.javaClass.simpleName}"
            Log.e(tag, "提交 BLE 广播失败", e)
            advertiser = null
            false
        }
    }

    /**
     * 停止广播
     */
    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(tag, "停止广播异常: ${e.message}")
        }
        advertiser = null
    }

    /**
     * 关闭 GATT Server
     */
    private fun closeGattServer() {
        try {
            connectedDevice?.let {
                gattServer?.cancelConnection(it)
            }
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(tag, "关闭 GATT Server 异常: ${e.message}")
        }
        gattServer = null
        connectedDevice = null
    }

    // ========== 广播回调 ==========

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            onStateChange?.invoke(State.ADVERTISING)
            Log.d(tag, "广播已启动")
        }

        override fun onStartFailure(errorCode: Int) {
            lastErrorMessage = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "广播数据超过 31 字节"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "系统没有可用的 BLE 广播资源"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "手机不支持当前 BLE 广播方式"
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "BLE 广播已在运行"
                else -> "BLE 广播启动失败，错误码 $errorCode"
            }
            Log.e(tag, "广播启动失败，错误码: $errorCode")
            isAdvertising = false
            onStateChange?.invoke(State.ERROR)
        }
    }

    // ========== GATT Server 回调 ==========

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(tag, "连接状态变化: ${device?.address}, status=$status, newState=$newState")

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevice = device
                isAdvertising = false
                stopAdvertising() // 连接后停止广播省电
                onStateChange?.invoke(State.CONNECTED)
                Log.d(tag, "设备已连接: ${device?.address}")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectedDevice = null
                onStateChange?.invoke(State.IDLE)
                Log.d(tag, "设备已断开")
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.d(tag, "服务已添加: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (!startAdvertising()) {
                    isAdvertising = false
                    if (lastErrorMessage == null) lastErrorMessage = "提交 BLE 广播请求失败"
                    onStateChange?.invoke(State.ERROR)
                }
            } else {
                isAdvertising = false
                lastErrorMessage = "注册 BLE GATT 服务失败，状态码 $status"
                onStateChange?.invoke(State.ERROR)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device, requestId, characteristic,
                preparedWrite, responseNeeded, offset, value
            )

            // 发送响应
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null
                )
            }

            // 解析数据
            value?.let {
                val command = MouseCommand.fromBytes(it)
                if (command != null) {
                    // Move packets are high frequency; logging every sample can
                    // add avoidable work and UI-thread pressure during cursor motion.
                    if (command.type != MouseCommand.Type.MOVE) {
                        Log.d(tag, "收到数据: ${String(it)}")
                    }
                    onCommandReceived?.invoke(command)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(
                device, requestId, descriptor,
                preparedWrite, responseNeeded, offset, value
            )

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }
    }
}
