package io.github.ngoduyanh.vkb

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.NativeResource
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import java.lang.Integer.min
import java.nio.LongBuffer

data class Swapchain(
    val device: VkDevice,
    val swapchain: VkSwapchainKHR,
    val imageCount: Int,
    val imageFormat: VkFormat,
    val extent: VkExtent2D,
    val allocationCallbacks: VkAllocationCallbacks?
) : NativeResource {
    override fun free() {
        if (swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, swapchain, allocationCallbacks)
        }
        extent.free()
    }

    inline fun getImages(allocator: (Int) -> LongBuffer = ::memAllocLong) = runCatching {
        stack {
            val ip = mallocInt(1)
            vkGetSwapchainImagesKHR(device, swapchain, ip, null)
            val images = allocator(ip[0])
            val ret = vkGetSwapchainImagesKHR(device, swapchain, ip, images)
            if (ret != VK_SUCCESS) {
                io.github.ngoduyanh.vkb.SwapchainError.FAILED_GET_SWAPCHAIN_IMAGES.throwError(ret)
            }
            images
        }
    }

    fun getImageViews() = runCatching {
        stack {
            val pView = mallocLong(1)
            val images = getImages(this::mallocLong).getOrThrow()

            images.indices.map {
                val createInfo = VkImageViewCreateInfo.callocStack()
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(images[it])
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(imageFormat)
                    .components {
                        it.set(
                            VK_COMPONENT_SWIZZLE_IDENTITY,
                            VK_COMPONENT_SWIZZLE_IDENTITY,
                            VK_COMPONENT_SWIZZLE_IDENTITY,
                            VK_COMPONENT_SWIZZLE_IDENTITY
                        )
                    }
                    .subresourceRange {
                        it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1)
                    }

                vkCreateImageView(device, createInfo, allocationCallbacks, pView)
                pView[0]
            }
        }
    }
}

data class SurfaceFormat(val format: Int, val colorspace: Int) {
    fun set(struct: VkSurfaceFormatKHR) = struct.set(format, colorspace)
}

class SwapchainBuilder(
    val physicalDevice: VkPhysicalDevice,
    val device: VkDevice,
    val surface: VkSurfaceKHR,
    val graphicsQueueIndex: Int,
    val presentQueueIndex: Int
) : NativeResource {
    companion object {
        operator fun invoke(device: Device, surface: VkSurfaceKHR? = null): Result<SwapchainBuilder> {
            return runCatching {
                SwapchainBuilder(
                    device.physicalDevice.device,
                    device.device,
                    surface ?: device.surface,
                    device.getQueueIndex(QueueType.GRAPHICS).getOrThrow(),
                    device.getQueueIndex(QueueType.PRESENT).getOrThrow()
                )
            }
        }
    }

    val pNextChain = arrayListOf<VkBaseOutStructure>()
    var flags: VkSwapchainCreateFlagsKHR = 0
    val desiredFormats = ArrayList<SurfaceFormat>()
    var desiredWidth = 256
    var desiredHeight = 256
    var arrayLayerCount = 1
    var imageUsageFlags: VkImageUsageFlags = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
    var formatFeatureFlags: VkFormatFeatureFlags = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
    var preTransform = 0
    var compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
    val desiredPresentModes = ArrayList<VkPresentModeKHR>()
    var clipped = true
    var oldSwapchain: VkSwapchainKHR = VK_NULL_HANDLE
    var allocationCallbacks: VkAllocationCallbacks? = null

    fun setDesiredExtent(width: Int, height: Int) {
        desiredWidth = width
        desiredHeight = height
    }

    fun setDesiredFormat(format: SurfaceFormat) {
        desiredFormats.add(0, format)
    }

    fun addFallbackFormat(format: SurfaceFormat) {
        desiredFormats.add(format)
    }

    private fun addDefaultDesiredFormats(formats: ArrayList<SurfaceFormat>) {
        formats.add(SurfaceFormat(VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR))
        formats.add(SurfaceFormat(VK_FORMAT_R8G8B8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR))
    }

    fun useDefaultFormatSelection() {
        desiredFormats.clear()
        addDefaultDesiredFormats(desiredFormats)
    }

    fun setDesiredPresentMode(presentMode: VkPresentModeKHR) {
        desiredPresentModes.add(0, presentMode)
    }

    fun addDesiredPresentMode(presentMode: VkPresentModeKHR) {
        desiredPresentModes.add(presentMode)
    }

    private fun addDefaultDesiredPresentModes(modes: ArrayList<VkPresentModeKHR>) {
        modes.add(VK_PRESENT_MODE_MAILBOX_KHR)
        modes.add(VK_PRESENT_MODE_FIFO_KHR)
    }

    fun useDefaultPresentModeSelection() {
        desiredPresentModes.clear()
        addDefaultDesiredPresentModes(desiredPresentModes)
    }

    fun addImageUsageFlags(flag: VkImageUsageFlags) {
        imageUsageFlags = imageUsageFlags or flag
    }

    fun useDefaultImageUsageFlags() {
        imageUsageFlags = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
    }

    fun addFormatFeatureFlag(flag: VkFormatFeatureFlags) {
        formatFeatureFlags = formatFeatureFlags or flag
    }

    fun useDefaultFormatFeatureFlag() {
        formatFeatureFlags = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
    }

    fun build() = runCatching {
        stack {
            if (surface == VK_NULL_HANDLE) {
                io.github.ngoduyanh.vkb.SwapchainError.SURFACE_HANDLE_NOT_PROVIDED.throwError()
            }

            val desiredFormats = ArrayList(this@SwapchainBuilder.desiredFormats)
            if (desiredFormats.isEmpty()) addDefaultDesiredFormats(desiredFormats)

            val desiredPresentModes = ArrayList(this@SwapchainBuilder.desiredPresentModes)
            if (desiredPresentModes.isEmpty()) addDefaultDesiredPresentModes(desiredPresentModes)

            val surfaceSupportDetails = querySurfaceSupportDetails(physicalDevice, surface).getOrThrow()

            var imageCount = surfaceSupportDetails.capabilities.minImageCount() + 1
            if (surfaceSupportDetails.capabilities.maxImageCount() in 1 until imageCount) {
                imageCount = surfaceSupportDetails.capabilities.maxImageCount()
            }

            val surfaceFormat =
                findSurfaceFormat(physicalDevice, surfaceSupportDetails.formats, desiredFormats, formatFeatureFlags)
            val extent = findExtent(surfaceSupportDetails.capabilities, desiredWidth, desiredHeight)
            var imageArrayLayers = min(arrayLayerCount, surfaceSupportDetails.capabilities.maxImageArrayLayers())
            if (arrayLayerCount == 0) imageArrayLayers = 1

            val queueFamilyIndices = ints(graphicsQueueIndex, presentQueueIndex)

            val presentMode = findPresentMode(surfaceSupportDetails.presentModes, desiredPresentModes)

            val preTransform = this@SwapchainBuilder.preTransform.takeIf { it != 0 }
                ?: surfaceSupportDetails.capabilities.currentTransform()

            val createInfo = VkSwapchainCreateInfoKHR.callocStack()
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .flags(flags)
                .surface(surface)
                .minImageCount(imageCount)
                .imageFormat(surfaceFormat.format)
                .imageColorSpace(surfaceFormat.colorspace)
                .imageExtent(extent)
                .imageArrayLayers(imageArrayLayers)
                .imageUsage(imageUsageFlags)
                .preTransform(preTransform)
                .compositeAlpha(compositeAlpha)
                .presentMode(presentMode)
                .clipped(clipped)
                .oldSwapchain(oldSwapchain)

            setUpPNextChain(createInfo, pNextChain)
            assert(pNextChain.all { it.sType() != VK_STRUCTURE_TYPE_APPLICATION_INFO })

            if (graphicsQueueIndex == presentQueueIndex) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(queueFamilyIndices)
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }

            val pSwapchain = mallocLong(1)
            val ret = vkCreateSwapchainKHR(device, createInfo, allocationCallbacks, pSwapchain)
            if (ret != VK_SUCCESS) {
                io.github.ngoduyanh.vkb.SwapchainError.FAILED_CREATE_SWAPCHAIN.throwError(ret)
            }

            Swapchain(
                device,
                pSwapchain[0],
                imageCount,
                surfaceFormat.format,
                VkExtent2D.malloc().set(extent),
                allocationCallbacks
            )
        }
    }

    override fun free() {
    }
}

fun VkSurfaceFormatKHR.set(format: Int, colorspace: Int) = apply {
    memPutInt(address() + VkSurfaceFormatKHR.FORMAT, format)
    memPutInt(address() + VkSurfaceFormatKHR.COLORSPACE, colorspace)
}

inline fun buildSwapchain(device: Device, block: SwapchainBuilder.() -> Unit) = runCatching {
    val builder = SwapchainBuilder(device).getOrThrow()
    block(builder)
    val ret = builder.build()
    builder.free()
    ret.getOrThrow()
}
