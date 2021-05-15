package com.dah.vkb

import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import java.io.PrintStream

val defaultCallbackStream: PrintStream = System.err

fun vulkanDebugCallback(stream: PrintStream): VkDebugUtilsMessengerCallbackEXTI {
    return VkDebugUtilsMessengerCallbackEXTI { messageSeverity, messageTypes, pCallbackData, pUserData ->
        val severity = messageSeverity.messageSeverityToString()
        val types = messageTypes.messageTypeToString()
        val message = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData).pMessageString()
        stream.println("[VKBKT] Vulkan Debug Message")
        stream.println("\tSeverity: $severity")
        stream.println("\tTypes: $types")
        stream.println("\tMessage: $message")

        VK10.VK_FALSE
    }
}

val defaultCallback = vulkanDebugCallback(defaultCallbackStream)

internal fun createDebugUtilsMessenger(
    instance: VkInstance,
    callback: VkDebugUtilsMessengerCallbackEXTI?,
    severity: VkDebugUtilsMessageSeverityFlagBitsEXT,
    type: VkDebugUtilsMessageTypeFlagsEXT,
    pMessenger: VkDebugUtilsMessengerEXTReference,
    alloccb: VkAllocationCallbacks? = null
): VkResult = stack {
    val cb = callback ?: defaultCallback
    val ci = VkDebugUtilsMessengerCreateInfoEXT.callocStack()
        .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
        .messageSeverity(severity)
        .messageType(type)
        .pfnUserCallback(cb)
    if (instance.capabilities.vkCreateDebugUtilsMessengerEXT == MemoryUtil.NULL) {
        VK10.VK_ERROR_EXTENSION_NOT_PRESENT
    } else {
        EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, ci, alloccb, pMessenger)
        VK10.VK_SUCCESS
    }
}

internal fun destroyDebugUtilsMessenger(
    instance: VkInstance,
    messenger: VkDebugUtilsMessengerEXT,
    alloccb: VkAllocationCallbacks? = null
) {
    if (instance.capabilities.vkDestroyDebugUtilsMessengerEXT != MemoryUtil.NULL) {
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, alloccb)
    }
}
