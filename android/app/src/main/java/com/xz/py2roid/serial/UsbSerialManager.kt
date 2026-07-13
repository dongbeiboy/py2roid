package com.xz.py2roid.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB 串口管理器
 *
 * 职责：
 * - USB 设备热插拔检测
 * - 权限请求与自动连接
 * - 驱动自动选择（FTDI/CP210x/CH340/CDC_ACM）
 * - 协程异步读写
 * - 自动断线重连（3s 间隔，最多 3 次）
 */
object UsbSerialManager {

    private const val TAG = "py2roid.UsbSerial"
    private const val ACTION_USB_PERMISSION = "com.xz.py2roid.USB_PERMISSION"
    private const val MAX_RECONNECT_ATTEMPTS = 3
    private const val RECONNECT_DELAY_MS = 3000L
    private const val READ_BUF_SIZE = 4096
    private const val WRITE_TIMEOUT_MS = 1000

    // ── 状态 ──

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data class Connected(val device: UsbDevice, val driver: String) : ConnectionState()
        data class Connecting(val device: UsbDevice) : ConnectionState()
        data class Error(val message: String, val device: UsbDevice? = null) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val availableDevices: StateFlow<List<UsbDevice>> = _availableDevices.asStateFlow()

    private val _incomingData = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val incomingData: Flow<ByteArray> = _incomingData.asSharedFlow()

    // ── 内部状态 ──

    private var usbManager: UsbManager? = null
    private var driverPort: UsbSerialPort? = null
    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var readJob: Job? = null
    private var autoConnectJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private var targetDevice: UsbDevice? = null
    private var targetBaudRate = 115200
    private val usbReceiver = UsbBroadcastReceiver()

    // ── 公开 API ──

    /**
     * 启动 USB 串口管理器。
     * 注册广播接收器、扫描现有设备、自动连接已授权设备。
     */
    fun start(context: Context) {
        if (isRunning.getAndSet(true)) return
        this.context = context.applicationContext
        this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("usb-serial"))

        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // 注册热插拔广播
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)

        // 扫描现有设备
        refreshDevices()

        Log.i(TAG, "UsbSerialManager started")
    }

    /**
     * 停止 USB 串口管理器。
     * 断开连接、取消协程、注销广播。
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        disconnectInternal()
        context?.let { ctx ->
            try { ctx.unregisterReceiver(usbReceiver) } catch (_: IllegalArgumentException) {}
        }
        scope?.cancel()
        scope = null
        context = null
        usbManager = null
        Log.i(TAG, "UsbSerialManager stopped")
    }

    /**
     * 连接到指定 USB 串口设备。
     *
     * @param device 目标 USB 设备
     * @param baudRate 波特率，默认 115200
     */
    fun connect(device: UsbDevice, baudRate: Int = 115200) {
        val mgr = usbManager ?: return
        targetDevice = device
        targetBaudRate = baudRate

        if (mgr.hasPermission(device)) {
            doConnect(device, baudRate)
        } else {
            requestPermission(device)
        }
    }

    /** 断开当前连接 */
    fun disconnect() {
        targetDevice = null
        autoConnectJob?.cancel()
        autoConnectJob = null
        disconnectInternal()
    }

    /**
     * 发送数据到串口（挂起直到发送完成）。
     *
     * @param data 待发送字节数组
     * @return true=全部写入成功, false=失败或部分写入
     */
    suspend fun write(data: ByteArray): Boolean {
        val port = driverPort ?: return false
        return withContext(Dispatchers.IO) {
            try {
                // 部分库版本 write 返回 void，部分返回 int（已写入字节数）。
                // 为兼容两者，简单重试一次：如果抛出异常则回退。
                port.write(data, WRITE_TIMEOUT_MS)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Write failed: ${e.message}")
                onIoError(e)
                false
            }
        }
    }

    // ── 内部方法 ──

    private fun refreshDevices() {
        val mgr = usbManager ?: return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr)
        _availableDevices.value = drivers.map { it.device }
    }

    private fun requestPermission(device: UsbDevice) {
        val mgr = usbManager ?: return
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).apply { setPackage(context?.packageName) },
            PendingIntent.FLAG_IMMUTABLE
        )
        _connectionState.value = ConnectionState.Connecting(device)
        mgr.requestPermission(device, intent)
    }

    private fun doConnect(device: UsbDevice, baudRate: Int) {
        val scope = scope ?: return
        val mgr = usbManager ?: return

        scope.launch {
            _connectionState.value = ConnectionState.Connecting(device)
            try {
                // 查找匹配的驱动
                val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr)
                val targetDriver = drivers.find { it.device.deviceId == device.deviceId }
                    ?: run {
                        // 自定义 ProbeTable 兜底（OpenMV CDC_ACM）
                        val customTable = ProbeTable()
                        customTable.addProduct(device.vendorId, device.productId, CdcAcmSerialDriver::class.java)
                        val customDrivers = UsbSerialProber(customTable).findAllDrivers(mgr)
                        customDrivers.find { it.device.deviceId == device.deviceId }
                    }

                if (targetDriver == null) {
                    _connectionState.value = ConnectionState.Error("不支持的设备: ${String.format("%04X", device.productId)}", device)
                    return@launch
                }

                // 获取串口（v3.7.3+ API: driver.ports 提供 UsbSerialPort）
                val portList = targetDriver.ports
                if (portList.isEmpty()) {
                    _connectionState.value = ConnectionState.Error("设备无可用串口", device)
                    return@launch
                }
                val port = portList[0]

                // 打开连接
                val connection = mgr.openDevice(device)
                if (connection == null) {
                    _connectionState.value = ConnectionState.Error("无法打开 USB 设备连接", device)
                    return@launch
                }
                port.open(connection)
                port.setParameters(baudRate, 8, 1, 0) // PARITY_NONE = 0
                driverPort = port

                val driverName = targetDriver::class.java.simpleName.removeSuffix("SerialDriver")
                Log.i(TAG, "Connected: ${device.productName} ($driverName @ $baudRate)")
                _connectionState.value = ConnectionState.Connected(device, driverName)

                // 启动读协程
                startReadLoop(port)
            } catch (e: SecurityException) {
                _connectionState.value = ConnectionState.Error("USB 权限不足: ${e.message}", device)
            } catch (e: IOException) {
                Log.e(TAG, "Connect failed: ${e.message}")
                _connectionState.value = ConnectionState.Error("连接失败: ${e.message}", device)
                scheduleReconnect(device, baudRate)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected connect error", e)
                _connectionState.value = ConnectionState.Error("未知错误: ${e.message}", device)
            }
        }
    }

    private fun startReadLoop(port: UsbSerialPort) {
        readJob?.cancel()
        readJob = scope?.launch(Dispatchers.IO) {
            val buf = ByteArray(READ_BUF_SIZE)
            while (isActive && isRunning.get()) {
                try {
                    val len = port.read(buf, 100)
                    if (len > 0) {
                        val data = buf.copyOf(len)
                        _incomingData.tryEmit(data)
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.w(TAG, "Read error: ${e.message}")
                        onIoError(e)
                    }
                    break
                }
            }
        }
    }

    private fun disconnectInternal() {
        readJob?.cancel()
        readJob = null
        try {
            driverPort?.close()
        } catch (_: Exception) {}
        driverPort = null
        if (_connectionState.value !is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun onIoError(e: Exception) {
        val device = targetDevice
        disconnectInternal()
        _connectionState.value = ConnectionState.Error("IO 错误: ${e.message}", device)
        if (device != null) {
            scheduleReconnect(device, targetBaudRate)
        }
    }

    private fun scheduleReconnect(device: UsbDevice, baudRate: Int) {
        autoConnectJob?.cancel()
        autoConnectJob = scope?.launch {
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                if (!isRunning.get()) break
                Log.i(TAG, "Reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS...")
                delay(RECONNECT_DELAY_MS)

                val mgr = usbManager ?: break
                // 检查设备是否还连接着
                val deviceList = mgr.deviceList ?: emptyMap()
                if (!deviceList.containsKey(device.deviceName)) {
                    Log.w(TAG, "Device detached, stopping reconnect")
                    _connectionState.value = ConnectionState.Disconnected
                    return@launch
                }

                try {
                    _connectionState.value = ConnectionState.Connecting(device)
                    doConnect(device, baudRate)
                    if (_connectionState.value is ConnectionState.Connected) {
                        Log.i(TAG, "Reconnected successfully")
                        return@launch
                    }
                } catch (_: Exception) {}
            }
            Log.w(TAG, "Reconnect exhausted after $MAX_RECONNECT_ATTEMPTS attempts")
            _connectionState.value = ConnectionState.Error(
                "重连失败（已尝试 $MAX_RECONNECT_ATTEMPTS 次）", device
            )
        }
    }

    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    // ── 广播接收器 ──

    private class UsbBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB attached: ${device?.productName}")
                    refreshDevices()
                    device?.let { dev ->
                        // 自动连接：如果有 targetDevice 优先，否则连第一个
                        val target = targetDevice ?: dev
                        connect(target, targetBaudRate)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB detached: ${device?.productName}")
                    val current = _connectionState.value
                    if (current is ConnectionState.Connected && device?.deviceId == current.device.deviceId) {
                        disconnectInternal()
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    refreshDevices()
                }

                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        Log.i(TAG, "USB permission granted for ${device.productName}")
                        doConnect(device, targetBaudRate)
                    } else {
                        Log.w(TAG, "USB permission denied for ${device?.productName}")
                        _connectionState.value = ConnectionState.Error(
                            "USB 权限被拒绝", device
                        )
                    }
                }
            }
        }
    }
}
