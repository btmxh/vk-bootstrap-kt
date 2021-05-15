package io.github.ngoduyanh

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPop
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.VK10.*
import java.nio.Buffer
import java.nio.LongBuffer

typealias VkResult = Int
typealias VkStructureType = Int
typealias VkDebugUtilsMessageSeverityFlagBitsEXT = Int
typealias VkDebugUtilsMessageSeverityFlagsEXT = Int
typealias VkDebugUtilsMessageTypeFlagsEXT = Int

typealias VkDebugUtilsMessengerEXT = Long
typealias VkDebugUtilsMessengerEXTReference = LongBuffer

typealias VkValidationCheckEXT = Int
typealias VkValidationFeatureEnableEXT = Int
typealias VkValidationFeatureDisableEXT = Int

typealias VkInstanceCreateFlags = Int
typealias VkDeviceCreateFlags = Int
typealias VkSwapchainCreateFlagsKHR = Int
typealias VkImageUsageFlags = Int
typealias VkFormatFeatureFlags = Int

typealias VkFormat = Int

typealias VkSurfaceKHR = Long
typealias VkSwapchainKHR = Long
typealias VkPresentModeKHR = Int
typealias VkQueue = Long

typealias VkVersion = Int

inline fun <R> stack(block: MemoryStack.() -> R): R {
    return block(stackPush()).also { stackPop() }
}

val Buffer.indices get() = position() until limit()

fun VkDebugUtilsMessageSeverityFlagBitsEXT.messageSeverityToString() = when (this) {
    VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT -> "ERROR"
    VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT -> "INFO"
    VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT -> "VERBOSE"
    VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT -> "WARNING"
    else -> "UNKNOWN"
}

fun VkDebugUtilsMessageTypeFlagsEXT.messageTypeToString() = when (this) {
    7 -> "General | Validation | Performance"
    6 -> "Validation | Performance"
    5 -> "General | Performance"
    4 /*VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT*/ -> "Performance"
    3 -> "General | Validation"
    2 /*VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT*/ -> "Validation"
    1 /*VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT*/ -> "General"
    else -> "Unknown"
}

