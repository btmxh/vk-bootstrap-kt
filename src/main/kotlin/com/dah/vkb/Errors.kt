package com.dah.vkb

import org.lwjgl.vulkan.VK10.VK_SUCCESS
import java.lang.RuntimeException

interface VkbErrorEnum {
    val errorString: String
}

enum class InstanceError(override val errorString: String) : VkbErrorEnum {
    VULKAN_UNAVAILABLE("Vulkan Unavailable"),
    VULKAN_VERSION_UNAVAILABLE("Vulkan Version Unavailable"), // will not be thrown
    VULKAN_VERSION_1_1_UNAVAILABLE("Vulkan Version 1.1 Unavailable"),
    VULKAN_VERSION_1_2_UNAVAILABLE("Vulkan Version 1.2 Unavailable"),
    FAILED_CREATE_INSTANCE("Failed to create instance"),
    FAILED_CREATE_DEBUG_MESSENGER("Failed to create debug messenger"),
    REQUESTED_LAYERS_NOT_PRESENT("Requested layers not present"),
    REQUESTED_EXTENSIONS_NOT_PRESENT("Requested extensions not present"),
    WINDOWING_EXTENSIONS_NOT_PRESENT("Windowing extensions not present"),
}
enum class PhysicalDeviceError(override val errorString: String) : VkbErrorEnum {
    NO_SURFACE_PROVIDED("No surface provided"),
    FAILED_ENUMERATE_PHYSICAL_DEVICES("Failed to enumerate physical devices"),
    NO_PHYSICAL_DEVICES_FOUND("No physical devices found"),
    NO_SUITABLE_DEVICE("No suitable device"),
}
enum class QueueError(override val errorString: String) : VkbErrorEnum {
    PRESENT_UNAVAILABLE("Present queue unavailable"),
    GRAPHICS_UNAVAILABLE("Graphics queue unavailable"),
    COMPUTE_UNAVAILABLE("Compute queue unavailable"),
    TRANSFER_UNAVAILABLE("Transfer queue unavailable"),
    QUEUE_INDEX_OUT_OF_RANGE("Queue index out of range"),
    INVALID_QUEUE_FAMILY_INDEX("Invalid queue family index")
}
enum class DeviceError(override val errorString: String) : VkbErrorEnum {
    FAILED_CREATE_DEVICE("Failed to create device"),
}
enum class SwapchainError(override val errorString: String) : VkbErrorEnum {
    SURFACE_HANDLE_NOT_PROVIDED("Surface handle not provided"),
    FAILED_QUERY_SURFACE_SUPPORT_DETAILS("Failed to query surface support details"),
    FAILED_CREATE_SWAPCHAIN("Failed to create swapchain"),
    FAILED_GET_SWAPCHAIN_IMAGES("Failed to get swapchain images"),
    FAILED_CREATE_SWAPCHAIN_IMAGE_VIEWS("Failed to create swapchain image views"),
}

class VulkanException(val errorEnum: VkbErrorEnum, val vkResult: VkResult): RuntimeException(createExceptionMessage(errorEnum, vkResult))

fun createExceptionMessage(errorEnum: VkbErrorEnum, vkResult: VkResult): String {
    if(vkResult == VK_SUCCESS) {
        return errorEnum.errorString
    } else {
        return "${errorEnum.errorString}. Vulkan result: $vkResult"
    }
}

fun <T> T.throwError(vkResult: VkResult = VK_SUCCESS): Nothing where T : VkbErrorEnum = throw VulkanException(this, vkResult)
