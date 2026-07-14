package com.xz.py2roid.bridge

import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * OpenMV 脚本执行器。
 *
 * 在 Chaquopy Python 线程中 exec() 用户脚本，
 * 重定向 sys.stdout 捕获 print() 输出。
 */
class ScriptRunner {

    private val TAG = "py2roid.ScriptRunner"

    enum class ScriptState { IDLE, RUNNING, STOPPED, ERROR }

    private val _state = MutableStateFlow(ScriptState.IDLE)
    val state: StateFlow<ScriptState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 128)
    val output: SharedFlow<String> = _output.asSharedFlow()

    private var executionJob: Job? = null
    private var pythonInitDone = false

    companion object {
        private const val MAX_OUTPUT_LINES = 500
        private val outputBuffer = mutableListOf<String>()
    }

    /** 初始化 Python OpenMV 兼容层（只需一次）。 */
    fun ensurePythonInit() {
        if (pythonInitDone) return
        try {
            Python.getInstance().getModule("main").callAttr("init_openmv")
            pythonInitDone = true
            Log.i(TAG, "OpenMV Python layer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "[SR01] Python OpenMV init failed", e)
            _state.value = ScriptState.ERROR
        }
    }

    /**
     * 加载并执行脚本。
     *
     * @param name 脚本名称（用于错误报告）
     * @param content 脚本源代码
     * @param scope 协程作用域
     */
    fun start(name: String, content: String, scope: CoroutineScope) {
        if (_state.value == ScriptState.RUNNING) {
            Log.w(TAG, "Script already running, stop first")
            return
        }

        ensurePythonInit()
        _state.value = ScriptState.RUNNING

        executionJob = scope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("main")

                // 停止标志复位
                module.callAttr("_reset_stop_flag")

                // 设置 stdout 重定向
                setupStdoutRedirect(py)

                // 执行脚本
                Log.i(TAG, "Executing script: $name (${content.length} chars)")
                py.getModule("main")
                    .callAttr("_exec_script", content, name)

                Log.i(TAG, "Script completed: $name")
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                Log.e(TAG, "[SR02] Script error: $msg")
                _output.tryEmit("\n[ERROR] $msg\n")
                _state.value = ScriptState.ERROR
            } finally {
                if (_state.value == ScriptState.RUNNING) {
                    _state.value = ScriptState.STOPPED
                }
                restoreStdout()
            }
        }
    }

    /** 停止当前脚本。 */
    fun stop() {
        executionJob?.cancel()
        executionJob = null
        try {
            Python.getInstance().getModule("main")
                .callAttr("_set_stop_flag")
        } catch (_: Exception) {}
        _state.value = ScriptState.STOPPED
        Log.i(TAG, "Script stopped")
    }

    /** 重启脚本。 */
    fun restart(name: String, content: String, scope: CoroutineScope) {
        stop()
        // 在传入的 scope 中启动，避免独立 CoroutineScope 泄漏
        scope.launch {
            delay(100)
            start(name, content, scope)
        }
    }

    fun isRunning(): Boolean = _state.value == ScriptState.RUNNING

    // ── stdout 重定向（Python 端实现）──

    private fun setupStdoutRedirect(py: Python) {
        try {
            py.getModule("main").callAttr("_redirect_stdout")
        } catch (_: Exception) {}
    }

    private fun restoreStdout() {
        try {
            Python.getInstance().getModule("main").callAttr("_restore_stdout")
        } catch (_: Exception) {}
    }

    override fun toString(): String {
        return "ScriptRunner(state=${_state.value})"
    }
}
