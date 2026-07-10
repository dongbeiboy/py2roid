package com.xz.py2roid.vision

/**
 * 推理引擎抽象层 —— 统一 ONNX Runtime 和 VCAP 的推理接口。
 *
 * 实现类须保证：
 * - loadModel / infer / close 均线程安全（由 Detector 通过 @Synchronized 保证）
 * - infer 返回原始输出 float 数组，YOLOv8 解码由 Detector 统一处理
 * - close 释放所有 native 资源，调用后对象不可复用
 */
interface InferenceEngine {

    /** 引擎显示名称（用于 HUD 和日志） */
    val name: String

    /** 当前实际运行的 provider（如 "CPU"、"NNAPI"、"VCAP_NPU"） */
    val provider: String

    /** 模型输入宽高（从模型元数据中读取） */
    val inputWidth: Int
    val inputHeight: Int

    /**
     * 加载模型。若失败应抛出异常，由调用方决定是否回退。
     * @param modelPath 模型文件绝对路径（.onnx 或 .vaim）
     */
    fun loadModel(modelPath: String)

    /**
     * 执行一次推理。
     * @param inputTensor CHW float 数组，长度 = 3 × inputWidth × inputHeight
     * @return 原始输出 float 数组（如 YOLOv8 的 [1,84,8400] 展平）
     */
    fun infer(inputTensor: FloatArray): FloatArray

    /** 释放所有 native 资源 */
    fun close()
}
