package io.github.ngoduyanh

import org.lwjgl.system.MemoryUtil.memCopy
import org.lwjgl.system.NativeResource
import org.lwjgl.system.Struct
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
import java.nio.FloatBuffer

enum class QueueType {
    PRESENT, GRAPHICS, TRANSFER, COMPUTE
}

data class Device(
    val device: VkDevice,
    val physicalDevice: PhysicalDevice,
    val surface: VkSurfaceKHR,
    val queueFamilies: VkQueueFamilyProperties.Buffer,
    val allocationCallbacks: VkAllocationCallbacks?
) : NativeResource {
    private fun Int.throwIfNegative(error: VkbErrorEnum): Int {
        if (this < 0) {
            error.throwError()
        }
        return this
    }

    fun getQueueIndex(type: QueueType): Result<Int> = runCatching {
        when (type) {
            QueueType.PRESENT -> getPresentQueueIndex(physicalDevice.device, surface, queueFamilies)
                .throwIfNegative(QueueError.PRESENT_UNAVAILABLE)
            QueueType.GRAPHICS -> getGraphicsQueueIndex(queueFamilies)
                .throwIfNegative(QueueError.GRAPHICS_UNAVAILABLE)
            QueueType.COMPUTE -> getSeparateComputeQueueIndex(queueFamilies)
                .throwIfNegative(QueueError.COMPUTE_UNAVAILABLE)
            QueueType.TRANSFER -> getSeparateTransferQueueIndex(queueFamilies)
                .throwIfNegative(QueueError.TRANSFER_UNAVAILABLE)
        }
    }

    fun getDedicatedQueueIndex(type: QueueType): Result<Int> = runCatching {
        when (type) {
            QueueType.COMPUTE -> getDedicatedComputeQueueIndex(queueFamilies)
                .throwIfNegative(QueueError.COMPUTE_UNAVAILABLE)
            QueueType.TRANSFER -> getDedicatedTransferQueueIndex(queueFamilies)
                .throwIfNegative(QueueError.TRANSFER_UNAVAILABLE)
            else -> QueueError.INVALID_QUEUE_FAMILY_INDEX.throwError()
        }
    }

    fun getQueueAt(index: Int): VkQueue = stack {
        val queue = mallocPointer(1)
        vkGetDeviceQueue(device, index, 0, queue)
        queue[0]
    }

    fun getQueue(type: QueueType) = getQueueIndex(type).map(::getQueueAt)
    fun getDedicatedQueue(type: QueueType) = getDedicatedQueueIndex(type).map(::getQueueAt)

    override fun free() {
        queueFamilies.free()
        vkDestroyDevice(device, allocationCallbacks)
    }

    inline fun buildSwapchain(block: SwapchainBuilder.() -> Unit) = buildSwapchain(this, block)
}

data class CustomQueueDescription(val index: Int, val priorities: FloatBuffer)

class DeviceBuilder(val physicalDevice: PhysicalDevice) : NativeResource {
    var flags: VkDeviceCreateFlags = 0
    val pNextChain = arrayListOf<VkBaseOutStructure>()
    val queueDescriptions = arrayListOf<CustomQueueDescription>()
    var allocationCallbacks: VkAllocationCallbacks? = null

    fun build(): Result<Device> = runCatching {
        stack {
            val queueDescriptions = ArrayList(this@DeviceBuilder.queueDescriptions)
            if (queueDescriptions.isEmpty()) {
                repeat(physicalDevice.queueFamilies.remaining()) {
                    queueDescriptions.add(CustomQueueDescription(it, floats(1.0f)))
                }
            }
            val queueCreateInfos = VkDeviceQueueCreateInfo.callocStack(queueDescriptions.size)
            for (desc in queueDescriptions) {
                queueCreateInfos.get()
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(desc.index)
                    .pQueuePriorities(desc.priorities)
            }
            queueCreateInfos.flip()

            val extensions = mallocPointer(physicalDevice.extensionsToEnable.size + 8)
            for (extension in physicalDevice.extensionsToEnable) {
                extensions.put(UTF8(extension))
            }
            if (physicalDevice.surfaceKHR != VK_NULL_HANDLE || physicalDevice.deferSurfaceInitialization) {
                extensions.put(UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
            }
            extensions.flip()

            var hasPhysicalDeviceFeatures2 = false
            var userDefinedPhysicalDeviceFeatures2 = false
            val finalPNextChain = arrayListOf<VkBaseOutStructure>()
            val deviceCreateInfo = VkDeviceCreateInfo.callocStack()

// #if defined(VK_API_VERSION_1_1)

            userDefinedPhysicalDeviceFeatures2 =
                pNextChain.any { it.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 }

            val pdExtensionFeaturesCopy: GenericFeaturesPNextNode.Buffer
            if (physicalDevice.extendedFeaturesChain == null) {
                pdExtensionFeaturesCopy = GenericFeaturesPNextNode.create(-1L, 0)
            } else {
                pdExtensionFeaturesCopy =
                    GenericFeaturesPNextNode.malloc(physicalDevice.extendedFeaturesChain.remaining())
                memCopy(pdExtensionFeaturesCopy, physicalDevice.extendedFeaturesChain)
            }

            val localFeatures2 = VkPhysicalDeviceFeatures2.callocStack()
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)

            if (!userDefinedPhysicalDeviceFeatures2) {
                if (physicalDevice.instanceVersion >= VK_API_VERSION_1_1) {
                    localFeatures2.features(physicalDevice.features)
                    finalPNextChain.add(VkBaseOutStructure.create(localFeatures2.address()))
                    hasPhysicalDeviceFeatures2 = true
                    for (featuresNode in pdExtensionFeaturesCopy) {
                        finalPNextChain.add(VkBaseOutStructure.create(featuresNode.address()))
                    }
                }
            } else {
                println("User provided VkPhysicalDeviceFeatures2 instance found in pNext chain. " +
                        "All requirements added via 'addRequiredExtensionFeatures' will be ignored.")
            }

            if (!userDefinedPhysicalDeviceFeatures2 && !hasPhysicalDeviceFeatures2) {
                deviceCreateInfo.pEnabledFeatures(physicalDevice.features)
            }

// #endif

            finalPNextChain.addAll(pNextChain)
            setUpPNextChain(deviceCreateInfo, finalPNextChain)
            assert(finalPNextChain.all { it.sType() != VK_STRUCTURE_TYPE_APPLICATION_INFO })

            deviceCreateInfo
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .flags(flags)
                .pQueueCreateInfos(queueCreateInfos)
                .ppEnabledExtensionNames(extensions)

            val pDevice = mallocPointer(1)
            val ret = vkCreateDevice(physicalDevice.device, deviceCreateInfo, allocationCallbacks, pDevice)
            if (ret != VK_SUCCESS) {
                DeviceError.FAILED_CREATE_DEVICE.throwError(ret)
            }

            Device(
                VkDevice(pDevice[0], physicalDevice.device, deviceCreateInfo, physicalDevice.instanceVersion),
                physicalDevice,
                physicalDevice.surfaceKHR,
                VkQueueFamilyProperties.malloc(physicalDevice.queueFamilies.remaining()).also {
                    memCopy(physicalDevice.queueFamilies, it)
                },
                allocationCallbacks
            )
        }
    }

    override fun free() {
    }

    fun <T : Struct> addPNext(structure: T) {
        pNextChain.add(VkBaseOutStructure.create(structure.address()))
    }
}

inline fun buildDevice(physicalDevice: PhysicalDevice, block: DeviceBuilder.() -> Unit) = runCatching {
    val builder = DeviceBuilder(physicalDevice)
    block(builder)
    val ret = builder.build()
    builder.free()
    ret.getOrThrow()
}
