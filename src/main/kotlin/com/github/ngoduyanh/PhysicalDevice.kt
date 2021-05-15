package com.github.ngoduyanh

import org.lwjgl.system.MemoryStack.stackGet
import org.lwjgl.system.MemoryStack.wrap
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memCopy
import org.lwjgl.system.NativeResource
import org.lwjgl.system.Pointer
import org.lwjgl.system.Struct
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.VK11.*

fun VkQueueFamilyProperties.isComputeQueue() = queueFlags() and VK_QUEUE_COMPUTE_BIT != 0
fun VkQueueFamilyProperties.isGraphicsQueue() = queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0
fun VkQueueFamilyProperties.isTransferQueue() = queueFlags() and VK_QUEUE_TRANSFER_BIT != 0

fun getGraphicsQueueIndex(properties: VkQueueFamilyProperties.Buffer): Int {
    return properties.indexOfFirst { it.isGraphicsQueue() }
}

fun getSeparateComputeQueueIndex(properties: VkQueueFamilyProperties.Buffer): Int {
    var index = -1
    for (i in properties.position() until properties.limit()) {
        val family = properties[i]
        if (family.isComputeQueue() && !family.isGraphicsQueue()) {
            if (family.isTransferQueue()) {
                index = i
            } else {
                return i
            }
        }
    }
    return index
}

fun getSeparateTransferQueueIndex(properties: VkQueueFamilyProperties.Buffer): Int {
    var index = -1
    for (i in properties.position() until properties.limit()) {
        val family = properties[i]
        if (family.isTransferQueue() && !family.isGraphicsQueue()) {
            if (family.isComputeQueue()) {
                index = i
            } else {
                return i
            }
        }
    }
    return index
}

fun getDedicatedComputeQueueIndex(properties: VkQueueFamilyProperties.Buffer): Int {
    return properties.indexOfFirst {
        it.isComputeQueue() && !it.isGraphicsQueue() && !it.isTransferQueue()
    }
}

fun getDedicatedTransferQueueIndex(properties: VkQueueFamilyProperties.Buffer): Int {
    return properties.indexOfFirst {
        it.isTransferQueue() && !it.isGraphicsQueue() && !it.isComputeQueue()
    }
}

fun getPresentQueueIndex(
    device: VkPhysicalDevice,
    surface: VkSurfaceKHR,
    properties: VkQueueFamilyProperties.Buffer
) = stack {
    val support = ints(0)
    (properties.position() until properties.limit())
        .firstOrNull {
            vkGetPhysicalDeviceSurfaceSupportKHR(device, it, surface, support)
            support[0] != 0
        } ?: -1
}

data class PhysicalDeviceDesc(
    val device: VkPhysicalDevice,
    val queueFamilies: VkQueueFamilyProperties.Buffer,
    val deviceFeatures: VkPhysicalDeviceFeatures,
    val deviceProperties: VkPhysicalDeviceProperties,
    val memoryProperties: VkPhysicalDeviceMemoryProperties,
    val deviceFeatures2: VkPhysicalDeviceFeatures2?,
    val extendedFeaturesChain: GenericFeaturesPNextNode.Buffer?
) {
    companion object {
        // must be called inside a stack {}
        operator fun invoke(
            handle: Long,
            instance: Instance,
            srcExtendedFeaturesChain: List<GenericFeaturesPNextNode>
        ): PhysicalDeviceDesc = stackGet().run {
            val device = VkPhysicalDevice(handle, instance.instance)
            val ip = mallocInt(1)
            vkGetPhysicalDeviceQueueFamilyProperties(device, ip, null)
            val queueFamilies = VkQueueFamilyProperties.mallocStack(ip[0])
            vkGetPhysicalDeviceQueueFamilyProperties(device, ip, queueFamilies)

            val deviceFeatures = VkPhysicalDeviceFeatures.mallocStack()
            vkGetPhysicalDeviceFeatures(device, deviceFeatures)

            val deviceProperties = VkPhysicalDeviceProperties.mallocStack()
            vkGetPhysicalDeviceProperties(device, deviceProperties)

            val memoryProperties = VkPhysicalDeviceMemoryProperties.mallocStack()
            vkGetPhysicalDeviceMemoryProperties(device, memoryProperties)

            var deviceFeatures2: VkPhysicalDeviceFeatures2? = null
            var extendedFeaturesChain: GenericFeaturesPNextNode.Buffer? = null

// #if defined(VK_API_VERSION_1_1)
            deviceFeatures2 = VkPhysicalDeviceFeatures2.mallocStack()
            deviceFeatures2.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)

            val fillChain = GenericFeaturesPNextNode.malloc(srcExtendedFeaturesChain.size)
            srcExtendedFeaturesChain.forEach { fillChain.put(it) }
            fillChain.flip()

            var prev: GenericFeaturesPNextNode? = null
            if (fillChain.hasRemaining()) {
                for (extension in fillChain) {
                    prev?.pNext(extension.address())
                    prev = extension
                }
            }

            stack {
                val localFeatures = VkPhysicalDeviceFeatures2.callocStack()
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    .pNext(fillChain.first().address())

                vkGetPhysicalDeviceFeatures2(device, localFeatures)
            }

            extendedFeaturesChain = fillChain
// #endif

            return PhysicalDeviceDesc(
                device,
                queueFamilies,
                deviceFeatures,
                deviceProperties,
                memoryProperties,
                deviceFeatures2,
                extendedFeaturesChain
            )
        }
    }
}

class PhysicalDeviceSelector(private val instance: Instance) : NativeResource {
    var surface: VkSurfaceKHR = VK_NULL_HANDLE

    var preferredType = PreferredDeviceType.DISCRETE
    var allowAnyType = true
    var requirePresent = true
    var requireDedicatedTransferQueue = false
    var requireDedicatedComputeQueue = false
    var requireSeparateTransferQueue = false
    var requireSeparateComputeQueue = false
    var requiredMemSize = 0L
    var desiredMemSize = 0L

    val requiredExtensions = arrayListOf<String>()
    val desiredExtensions = arrayListOf<String>()

    var requiredVersion = VK_MAKE_VERSION(1, 0, 0)
    var desiredVersion = VK_MAKE_VERSION(1, 0, 0)

    var requiredFeatures = VkPhysicalDeviceFeatures.calloc()
    var requiredFeatures2 = VkPhysicalDeviceFeatures2.calloc()
    val extendedFeaturesChain = arrayListOf<GenericFeaturesPNextNode>()
    var deferSurfaceInitialization = false
    var useFirstGPUUnconditionally = false

    enum class Suitability {
        YES, PARTIAL, NO
    }

    private fun isDeviceSuitable(device: PhysicalDeviceDesc): Suitability {
        var suitability = Suitability.YES

        if (requiredVersion > device.deviceProperties.apiVersion()) return Suitability.NO
        else if (desiredVersion > device.deviceProperties.apiVersion()) {
            suitability = Suitability.PARTIAL
        }

        val dedicatedCompute = getDedicatedComputeQueueIndex(device.queueFamilies) >= 0
        val dedicatedTransfer = getDedicatedTransferQueueIndex(device.queueFamilies) >= 0
        val separateCompute = getSeparateComputeQueueIndex(device.queueFamilies) >= 0
        val separateTransfer = getSeparateTransferQueueIndex(device.queueFamilies) >= 0

        val presentQueue = getPresentQueueIndex(device.device, surface, device.queueFamilies) >= 0

        if ((requireDedicatedComputeQueue && !dedicatedCompute) ||
            (requireDedicatedTransferQueue && !dedicatedTransfer) ||
            (requireSeparateComputeQueue && !separateCompute) ||
            (requireSeparateTransferQueue && !separateTransfer) ||
            (requirePresent && !presentQueue)
        ) {
            return Suitability.NO
        }

        val requiredExtensionsSupported = checkDeviceExtensionSupport(device.device, requiredExtensions)
        if (requiredExtensionsSupported.size != requiredExtensions.size) {
            return Suitability.NO
        }

        val desiredExtensionsSupported = checkDeviceExtensionSupport(device.device, desiredExtensions)
        if (desiredExtensionsSupported.size != desiredExtensions.size) {
            suitability = Suitability.PARTIAL
        }

        val swapchainAdequate = if (deferSurfaceInitialization) {
            true
        } else if (!instance.headless) stack {
            val ip = mallocInt(1)
            vkGetPhysicalDeviceSurfaceFormatsKHR(device.device, surface, ip, null)
            val properties = VkSurfaceFormatKHR.mallocStack(ip[0])
            val ret1 = vkGetPhysicalDeviceSurfaceFormatsKHR(device.device, surface, ip, properties)

            vkGetPhysicalDeviceSurfacePresentModesKHR(device.device, surface, ip, null)
            val presentModes = mallocInt(ip[0])
            val ret2 = vkGetPhysicalDeviceSurfacePresentModesKHR(device.device, surface, ip, presentModes)

            ret1 == VK_SUCCESS && ret2 == VK_SUCCESS && properties.hasRemaining() && presentModes.hasRemaining()
        } else false

        if (requirePresent && !swapchainAdequate) return Suitability.NO

        if (device.deviceProperties.deviceType() == preferredType.asVkPhysicalDeviceType()) {
            if (allowAnyType) {
                suitability = Suitability.PARTIAL
            } else {
                return Suitability.NO
            }
        }

        val requiredFeaturesSupported = supportsFeatures(
            device.deviceFeatures,
            requiredFeatures,
            device.extendedFeaturesChain,
            extendedFeaturesChain
        )

        if (!requiredFeaturesSupported) return Suitability.NO

        val hasRequiredMemory = device.memoryProperties.memoryHeaps().any {
            it.flags() and VK_MEMORY_HEAP_DEVICE_LOCAL_BIT != 0
                    && it.size() > requiredMemSize
        }
        val hasPreferredMemory = device.memoryProperties.memoryHeaps().any {
            it.flags() and VK_MEMORY_HEAP_DEVICE_LOCAL_BIT != 0
                    && it.size() > desiredMemSize
        }

        if (!hasRequiredMemory) return Suitability.NO
        else if (hasPreferredMemory) {
            suitability = Suitability.PARTIAL
        }

        return suitability
    }

    fun select(): Result<PhysicalDevice> = runCatching {
        stack {
            if (!instance.headless && !deferSurfaceInitialization) {
                if (surface == VK_NULL_HANDLE) {
                    com.github.ngoduyanh.PhysicalDeviceError.NO_SURFACE_PROVIDED.throwError()
                }
            }
            val ip = mallocInt(1)
            vkEnumeratePhysicalDevices(instance.instance, ip, null)
            val physicalDevices = mallocPointer(ip[0])
            val ret = vkEnumeratePhysicalDevices(instance.instance, ip, physicalDevices)
            if (ret != VK_SUCCESS) {
                com.github.ngoduyanh.PhysicalDeviceError.FAILED_ENUMERATE_PHYSICAL_DEVICES.throwError(ret)
            }
            if (!physicalDevices.hasRemaining()) {
                com.github.ngoduyanh.PhysicalDeviceError.NO_PHYSICAL_DEVICES_FOUND.throwError()
            }

            val descriptions = (physicalDevices.position() until physicalDevices.limit())
                .map { physicalDevices.get(it) }
                .map { PhysicalDeviceDesc(it, instance, extendedFeaturesChain) }

            var selectedDevice: PhysicalDeviceDesc? = null
            if (useFirstGPUUnconditionally) {
                selectedDevice = descriptions.first()
            } else {
                for (desc in descriptions) {
                    when (isDeviceSuitable(desc)) {
                        Suitability.YES -> {
                            selectedDevice = desc
                            break
                        }

                        Suitability.PARTIAL -> {
                            selectedDevice = desc
                        }
                        else -> continue
                    }
                }
            }
            selectedDevice ?: com.github.ngoduyanh.PhysicalDeviceError.NO_SUITABLE_DEVICE.throwError()

            val extensionsToEnable = ArrayList<String>(requiredExtensions)
            checkDeviceExtensionSupport(selectedDevice.device, desiredExtensions)
                .let { extensionsToEnable.addAll(it) }

            PhysicalDevice(
                selectedDevice.device,
                surface,
                VkPhysicalDeviceFeatures.malloc().set(selectedDevice.deviceFeatures),
                selectedDevice.extendedFeaturesChain?.let {
                    GenericFeaturesPNextNode.malloc(it.remaining()).put(it).flip()
                },
                VkPhysicalDeviceProperties.malloc().also {
                    memCopy(selectedDevice.deviceProperties, it)
                },
                VkPhysicalDeviceMemoryProperties.malloc().also {
                    memCopy(selectedDevice.memoryProperties, it)
                },
                VkQueueFamilyProperties.malloc(selectedDevice.queueFamilies.remaining()).also {
                    memCopy(selectedDevice.queueFamilies, it)
                },
                deferSurfaceInitialization,
                instance.instanceVersion,
                extensionsToEnable
            ).also {
                descriptions.forEach { it.extendedFeaturesChain?.free() }
            }
        }
    }

    override fun free() {
        requiredFeatures.free()
        requiredFeatures2.free()
        extendedFeaturesChain.forEach { free() }
    }

    fun setMinimumVersion(major: Int, minor: Int, patch: Int = 0) {
        requiredVersion = VK_MAKE_VERSION(major, minor, patch)
    }

    fun setDesiredVersion(major: Int, minor: Int, patch: Int = 0) {
        desiredVersion = VK_MAKE_VERSION(major, minor, patch)
    }

    fun <T : Struct> addRequiredExtensionFeatures(features: T) {
        assert(instance.instanceVersion >= VK_API_VERSION_1_1) {
            "This function is available only for Vulkan 1.1 or higher"
        }
        val baseOut = VkBaseOutStructure.create(features.address())
        assert(baseOut.sType() != 0) {
            "Features struct sType must be filled with the struct's corresponding VkStructureType enum"
        }
        assert(baseOut.sType() != VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2) {
            "Do not pass VkPhysicalDeviceFeatures2 as a required extension feature structure. An " +
                    "instance of this is managed internally for selection criteria and device creation."
        }
        val node = GenericFeaturesPNextNode.calloc()
        node.set(features)
        extendedFeaturesChain.add(node)
    }

    // remove these lines for things to work with LWJGL 3.2.3
// #if defined(VK_API_VERSION_1_2)

    fun setRequiredFeature11(features: VkPhysicalDeviceVulkan11Features) {
        features.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)
        addRequiredExtensionFeatures(features)
    }

    fun setRequiredFeature12(features: VkPhysicalDeviceVulkan12Features) {
        features.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
        addRequiredExtensionFeatures(features)
    }

// #endif

}

inline fun selectPhysicalDevice(instance: Instance, block: PhysicalDeviceSelector.() -> Unit) = runCatching {
    val selector = PhysicalDeviceSelector(instance)
    block(selector)
    val ret = selector.select()
    selector.free()
    ret.getOrThrow()
}

enum class PreferredDeviceType(private val vkPhysicalDeviceType: Int) {
    OTHER(VK_PHYSICAL_DEVICE_TYPE_OTHER),
    INTEGRATED(VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU),
    DISCRETE(VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU),
    VIRTUAL_GPU(VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU),
    CPU(VK_PHYSICAL_DEVICE_TYPE_CPU);

    fun asVkPhysicalDeviceType(): Int {
        return vkPhysicalDeviceType
    }
}

data class PhysicalDevice(
    val device: VkPhysicalDevice,
    val surfaceKHR: VkSurfaceKHR,
    val features: VkPhysicalDeviceFeatures,
    val extendedFeaturesChain: GenericFeaturesPNextNode.Buffer?,
    val properties: VkPhysicalDeviceProperties,
    val memoryProperties: VkPhysicalDeviceMemoryProperties,
    val queueFamilies: VkQueueFamilyProperties.Buffer,
    val deferSurfaceInitialization: Boolean,
    val instanceVersion: VkVersion,
    val extensionsToEnable: List<String>
) : NativeResource {
    fun hasDedicatedComputeQueue() = getDedicatedComputeQueueIndex(queueFamilies) >= 0
    fun hasDedicatedTransferQueue() = getDedicatedTransferQueueIndex(queueFamilies) >= 0
    fun hasSeparateComputeQueue() = getSeparateComputeQueueIndex(queueFamilies) >= 0
    fun hasSeparateTransferQueue() = getSeparateTransferQueueIndex(queueFamilies) >= 0

    override fun free() {
        features.free()
        extendedFeaturesChain?.free()
        properties.free()
        memoryProperties.free()
        queueFamilies.free()
    }

    inline fun buildDevice(block: DeviceBuilder.() -> Unit) = buildDevice(this, block)
}
