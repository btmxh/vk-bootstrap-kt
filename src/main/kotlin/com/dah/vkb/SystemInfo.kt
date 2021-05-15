package com.dah.vkb

import org.lwjgl.system.NativeResource
import org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkExtensionProperties
import org.lwjgl.vulkan.VkLayerProperties
import java.nio.ByteBuffer

class SystemInfo internal constructor() : NativeResource {

    companion object {
        const val VALIDATION_LAYER_NAME = "VK_LAYER_KHRONOS_validation"
    }

    val availableLayers = stack {
        val ip = mallocInt(1)
        vkEnumerateInstanceLayerProperties(ip, null)
        val layers = VkLayerProperties.malloc(ip[0])
        if (vkEnumerateInstanceLayerProperties(ip, layers) != VK_SUCCESS) {
            layers.clear()
        }
        layers
    }

    val validationLayerAvailable = checkLayerSupported(availableLayers, VALIDATION_LAYER_NAME)

    val availableExtensions = stack {
        val pSize = mallocInt(1)
        vkEnumerateInstanceExtensionProperties(null as ByteBuffer?, pSize, null)
        val availableExtensions = VkExtensionProperties.malloc(pSize[0])
        if (vkEnumerateInstanceExtensionProperties(null as ByteBuffer?, pSize, availableExtensions) != VK_SUCCESS) {
            availableExtensions.clear()
        }
        availableExtensions
    }

    val debugUtilsAvailable = run {
        checkExtensionSupported(
            availableExtensions,
            VK_EXT_DEBUG_UTILS_EXTENSION_NAME
        ) || availableLayers.any { layer ->
            stack {
                val pSize = mallocInt(1)
                vkEnumerateInstanceExtensionProperties(layer.layerName(), pSize, null)
                val extensions = VkExtensionProperties.mallocStack(pSize[0])
                val ret = vkEnumerateInstanceExtensionProperties(layer.layerName(), pSize, extensions)
                if (ret != VK_SUCCESS) {
                    if (checkExtensionSupported(extensions, VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
                        return@stack true
                }
                ret != VK_SUCCESS /* not sure why, should be == */
                        && checkExtensionSupported(extensions, VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
            }
        }
    }

    fun checkExtensionAvailable(name: String) = checkExtensionSupported(availableExtensions, name)
    fun checkLayerAvailable(name: String) = checkLayerSupported(availableLayers, name)

    override fun free() {
        availableExtensions.free()
        availableLayers.free()
    }


}

fun getSystemInfo(): Result<SystemInfo> {
    return runCatching { SystemInfo() }
}

