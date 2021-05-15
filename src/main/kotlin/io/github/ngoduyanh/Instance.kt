package io.github.ngoduyanh

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.NativeResource
import org.lwjgl.system.Platform
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.EXTMetalSurface.VK_EXT_METAL_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.EXTValidationFeatures.VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT
import org.lwjgl.vulkan.EXTValidationFlags.VK_STRUCTURE_TYPE_VALIDATION_FLAGS_EXT
import org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.KHRWaylandSurface.VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.KHRXlibSurface.VK_KHR_XLIB_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import java.nio.IntBuffer
import kotlin.math.max

data class Instance(
    val instance: VkInstance,
    val debugMessenger: VkDebugUtilsMessengerEXT = VK_NULL_HANDLE,
    val debugCallback: VkDebugUtilsMessengerCallbackEXT?,
    val allocationCallbacks: VkAllocationCallbacks?,
    val headless: Boolean,
    val instanceVersion: VkVersion
) : NativeResource {
    override fun free() {
        debugCallback?.free()
        if (debugMessenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, allocationCallbacks)
        }
        vkDestroyInstance(instance, allocationCallbacks)
    }

    inline fun selectPhysicalDevice(block: PhysicalDeviceSelector.() -> Unit) = selectPhysicalDevice(this, block)
}

class InstanceBuilder : NativeResource {
    // VkApplicationInfo
    var appName: String? = null
    var engineName: String? = null
    var appVersion: VkVersion = 0
    var engineVersion: VkVersion = 0
    var requiredAPIVersion: VkVersion = VK_MAKE_VERSION(1, 0, 0)
    var desiredAPIVersion: VkVersion = VK_MAKE_VERSION(1, 0, 0)

    // VkInstanceCreateInfo
    val layers = arrayListOf<String>()
    val extensions = arrayListOf<String>()
    var flags: VkInstanceCreateFlags = 0

    // unused property in C++ library
    // val pNextElements = arrayListOf<VkBaseOutStructure>()

    // Debug Callback
    var debugCallback: VkDebugUtilsMessengerCallbackEXTI? = null
        set(value) {
            useDebugMessenger = true
            field = value
        }
    var debugMessageSeverity: VkDebugUtilsMessageSeverityFlagsEXT =
        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
    var debugMessageType: VkDebugUtilsMessageTypeFlagsEXT =
        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or
                VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT

    // Validation Features
    val disabledValidationChecks = arrayListOf<VkValidationCheckEXT>()
    val enabledValidationFeatures = arrayListOf<VkValidationFeatureEnableEXT>()
    val disabledValidationFeatures = arrayListOf<VkValidationFeatureDisableEXT>()

    var allocator: VkAllocationCallbacks? = null

    var requestValidationLayers = false
    var enableValidationLayers = false
    var useDebugMessenger = false
    var headlessContext = false

    fun setAppVersion(major: Int, minor: Int, patch: Int) {
        appVersion = VK_MAKE_VERSION(major, minor, patch)
    }

    fun setEngineVersion(major: Int, minor: Int, patch: Int) {
        engineVersion = VK_MAKE_VERSION(major, minor, patch)
    }

    fun requireAPIVersion(major: Int, minor: Int, patch: Int) {
        requiredAPIVersion = VK_MAKE_VERSION(major, minor, patch)
    }

    fun desireAPIVersion(major: Int, minor: Int, patch: Int) {
        desiredAPIVersion = VK_MAKE_VERSION(major, minor, patch)
    }

    fun enableLayer(layer: String) = layers.add(layer).let {}
    fun enableExtension(extension: String) = extensions.add(extension).let {}

    fun addDebugMessengerSeverity(severity: VkDebugUtilsMessageSeverityFlagsEXT) {
        debugMessageSeverity = debugMessageSeverity or severity
    }

    fun addDebugMessengerType(type: VkDebugUtilsMessageTypeFlagsEXT) {
        debugMessageType = debugMessageType or type
    }

    fun addValidationDisable(check: VkValidationCheckEXT) = disabledValidationChecks.add(check).let {}
    fun addValidationFeatureEnable(enable: VkValidationFeatureEnableEXT) = enabledValidationFeatures.add(enable).let {}
    fun addValidationFeatureDisable(disable: VkValidationFeatureDisableEXT) =
        disabledValidationFeatures.add(disable).let {}

    fun useDefaultDebugMessenger() {
        debugCallback = defaultCallback
    }

    fun build(): Result<Instance> = runCatching {
        stack {
            val systemInfo = getSystemInfo().getOrThrow()

            val vk10 = VK_MAKE_VERSION(1, 0, 0)
            var apiVersion = vk10
            if (requiredAPIVersion > vk10 || desiredAPIVersion > vk10) {
                val queriedAPIVersion = VK.getInstanceVersionSupported()
                if (queriedAPIVersion < requiredAPIVersion) {
                    when (VK_VERSION_MINOR(requiredAPIVersion)) {
                        // same behaviour as C++ version
                        2 -> io.github.ngoduyanh.InstanceError.VULKAN_VERSION_1_2_UNAVAILABLE.throwError()
                        0 -> io.github.ngoduyanh.InstanceError.VULKAN_UNAVAILABLE.throwError()
                        else -> io.github.ngoduyanh.InstanceError.VULKAN_VERSION_1_1_UNAVAILABLE.throwError()
                    }
                }
                if (requiredAPIVersion > vk10) {
                    apiVersion = requiredAPIVersion
                } else if (desiredAPIVersion > vk10) {
                    apiVersion = max(desiredAPIVersion, queriedAPIVersion)
                }
            }

            val appInfo = VkApplicationInfo.callocStack()
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pNext(NULL)
                .pApplicationName(UTF8(appName ?: ""))
                .applicationVersion(appVersion)
                .pEngineName(UTF8(engineName ?: ""))
                .engineVersion(engineVersion)
                .apiVersion(apiVersion)

            // at most there are 4 extra extensions, but to be sure, adding 8 should be safe
            val extensions = mallocPointer(this@InstanceBuilder.extensions.size + 8)
            if (debugCallback != null && systemInfo.debugUtilsAvailable) {
                extensions.put(UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
            }

            if (!headlessContext) {
                fun checkAddWindowEXT(name: String): Boolean {
                    return checkExtensionSupported(systemInfo.availableExtensions, name)
                        .also { if (it) extensions.put(UTF8(name)) }
                }

                val khrSurfaceAdded = checkAddWindowEXT(VK_KHR_SURFACE_EXTENSION_NAME)
                val addWindowEXTs = when (Platform.get()) {
                    Platform.WINDOWS -> checkAddWindowEXT(VK_KHR_WIN32_SURFACE_EXTENSION_NAME)
                    Platform.LINUX -> checkAddWindowEXT(VK_KHR_XLIB_SURFACE_EXTENSION_NAME) or
                            checkAddWindowEXT(VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME) or
                            checkAddWindowEXT("VK_KHR_xcb_surface")
                    Platform.MACOSX -> checkAddWindowEXT(VK_EXT_METAL_SURFACE_EXTENSION_NAME)
                    else -> true
                }

                if (!khrSurfaceAdded || !addWindowEXTs) {
                    io.github.ngoduyanh.InstanceError.WINDOWING_EXTENSIONS_NOT_PRESENT.throwError()
                }
            }

            extensions.flip()
            if (!checkExtensionsSupported(systemInfo.availableExtensions, extensions)) {
                io.github.ngoduyanh.InstanceError.REQUESTED_EXTENSIONS_NOT_PRESENT.throwError()
            }

            val layers = mallocPointer(this@InstanceBuilder.layers.size + 1)
            this@InstanceBuilder.layers.forEach { layers.put(UTF8(it)) }
            if (enableValidationLayers || (requestValidationLayers && systemInfo.validationLayerAvailable)) {
                layers.put(UTF8(SystemInfo.VALIDATION_LAYER_NAME))
            }
            layers.flip()

            if (!checkLayersSupported(systemInfo.availableLayers, layers)) {
                io.github.ngoduyanh.InstanceError.REQUESTED_LAYERS_NOT_PRESENT.throwError()
            }

            val pNextChain = arrayListOf<VkBaseOutStructure>()
            var debugCallback: VkDebugUtilsMessengerCallbackEXT? = null
            if (useDebugMessenger) {
                val messengerCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack()
                    .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                    .pNext(NULL)
                    .messageSeverity(debugMessageSeverity)
                    .messageType(debugMessageType)
                    .pfnUserCallback(debugCallback ?: defaultCallback)
                debugCallback = messengerCreateInfo.pfnUserCallback()
                pNextChain.add(VkBaseOutStructure.create(messengerCreateInfo.address()))
            }

            fun toStackIntBuffer(l: List<Int>): IntBuffer {
                val buffer = MemoryStack.stackMallocInt(l.size)
                l.forEach(buffer::put)
                buffer.flip()
                return buffer
            }

            if (enabledValidationFeatures.isNotEmpty() || disabledValidationFeatures.isNotEmpty()) {
                val features = VkValidationFeaturesEXT.callocStack()
                    .sType(VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT)
                    .pNext(NULL)
                    .pEnabledValidationFeatures(toStackIntBuffer(enabledValidationFeatures))
                    .pDisabledValidationFeatures(toStackIntBuffer(disabledValidationFeatures))
                pNextChain.add(VkBaseOutStructure.create(features.address()))
            }

            if (disabledValidationChecks.isNotEmpty()) {
                val checks = VkValidationFlagsEXT.callocStack()
                    .sType(VK_STRUCTURE_TYPE_VALIDATION_FLAGS_EXT)
                    .pNext(NULL)
                    .pDisabledValidationChecks(toStackIntBuffer(disabledValidationChecks))
                pNextChain.add(VkBaseOutStructure.create(checks.address()))
            }

            val instanceCreateInfo = VkInstanceCreateInfo.callocStack()
                .pApplicationInfo(appInfo)
                .flags(flags)
                .ppEnabledExtensionNames(extensions)
                .ppEnabledLayerNames(layers)

            setUpPNextChain(instanceCreateInfo, pNextChain)

            val pp = mallocPointer(1)
            var ret: Int
            ret = vkCreateInstance(instanceCreateInfo, allocator, pp)
            if (ret != VK_SUCCESS) {
                io.github.ngoduyanh.InstanceError.FAILED_CREATE_INSTANCE.throwError(ret)
            }

            val vkInstance = VkInstance(pp[0], instanceCreateInfo)

//            debugCallback?.free()

            val debugMessenger = if (useDebugMessenger) {
                val pMessenger = mallocLong(1)
                ret = createDebugUtilsMessenger(
                    vkInstance,
                    debugCallback,
                    debugMessageSeverity,
                    debugMessageType,
                    pMessenger,
                    allocator
                )
                if (ret != VK_SUCCESS) {
                    io.github.ngoduyanh.InstanceError.FAILED_CREATE_DEBUG_MESSENGER.throwError(ret)
                }
                pMessenger[0]
            } else VK_NULL_HANDLE

            Instance(
                vkInstance,
                debugMessenger,
                debugCallback,
                allocator,
                headlessContext,
                apiVersion
            ).also { systemInfo.free() }
        }
    }

    override fun free() {
    }
}

fun buildInstance(block: InstanceBuilder.() -> Unit) = runCatching {
    val builder = InstanceBuilder()
    block(builder)
    val ret = builder.build()
    builder.free()
    ret.getOrThrow()
}
