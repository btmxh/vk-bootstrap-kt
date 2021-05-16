package io.github.ngoduyanh.vkb

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack.stackGet
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Struct
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures.*
import java.nio.IntBuffer

fun PointerBuffer.asUTF8StringStream(): Sequence<String> {
    return (position() until limit()).asSequence().map { getStringUTF8(it) }
}

fun checkLayerSupported(availableLayers: VkLayerProperties.Buffer, layerName: String): Boolean {
    return availableLayers.any { p -> p.layerNameString() == layerName }
}

fun checkLayersSupported(availableLayers: VkLayerProperties.Buffer, layers: PointerBuffer): Boolean {
    return layers.asUTF8StringStream().all { checkLayerSupported(availableLayers, it) }
}

fun checkExtensionSupported(availableExtensions: VkExtensionProperties.Buffer, extensionName: String): Boolean {
    return availableExtensions.any { e -> e.extensionNameString() == extensionName }
}

fun checkExtensionsSupported(availableExtensions: VkExtensionProperties.Buffer, extensions: PointerBuffer): Boolean {
    return extensions.asUTF8StringStream().all { checkExtensionSupported(availableExtensions, it) }
}

//UNSAFE GENERIC, USE WITH CAUTION
internal fun <T : Struct> setUpPNextChain(structure: T, structs: List<VkBaseOutStructure>) {
    val baseOutStructure = VkBaseOutStructure.create(structure.address())
    baseOutStructure.pNext(null)
    if (structs.isEmpty()) return
    val it = structs.iterator()
    var first = it.next()
    baseOutStructure.pNext(first)
    while (it.hasNext()) {
        val second = it.next()
        first.pNext(second)
        first = second
    }
}

internal fun checkDeviceExtensionSupport(device: VkPhysicalDevice, desiredExtensions: List<String>): List<String> =
    stack {
        val ip = mallocInt(1)
        vkEnumerateDeviceExtensionProperties(device, null as ByteBuffer?, ip, null)
        val extensions = VkExtensionProperties.mallocStack(ip[0])
        val ret = vkEnumerateDeviceExtensionProperties(device, null as ByteBuffer?, ip, extensions)
        if (ret != VK_SUCCESS) {
            listOf()
        } else {
            desiredExtensions.filter {
                extensions.any { e -> e.extensionNameString() == it }
            }
        }
    }

val VK_PHYSICAL_DEVICE_FEATURES_OFFSET = intArrayOf(
    ROBUSTBUFFERACCESS,
    FULLDRAWINDEXUINT32,
    IMAGECUBEARRAY,
    INDEPENDENTBLEND,
    GEOMETRYSHADER,
    TESSELLATIONSHADER,
    SAMPLERATESHADING,
    DUALSRCBLEND,
    LOGICOP,
    MULTIDRAWINDIRECT,
    DRAWINDIRECTFIRSTINSTANCE,
    DEPTHCLAMP,
    DEPTHBIASCLAMP,
    FILLMODENONSOLID,
    DEPTHBOUNDS,
    WIDELINES,
    LARGEPOINTS,
    ALPHATOONE,
    MULTIVIEWPORT,
    SAMPLERANISOTROPY,
    TEXTURECOMPRESSIONETC2,
    TEXTURECOMPRESSIONASTC_LDR,
    TEXTURECOMPRESSIONBC,
    OCCLUSIONQUERYPRECISE,
    PIPELINESTATISTICSQUERY,
    VERTEXPIPELINESTORESANDATOMICS,
    FRAGMENTSTORESANDATOMICS,
    SHADERTESSELLATIONANDGEOMETRYPOINTSIZE,
    SHADERIMAGEGATHEREXTENDED,
    SHADERSTORAGEIMAGEEXTENDEDFORMATS,
    SHADERSTORAGEIMAGEMULTISAMPLE,
    SHADERSTORAGEIMAGEREADWITHOUTFORMAT,
    SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT,
    SHADERUNIFORMBUFFERARRAYDYNAMICINDEXING,
    SHADERSAMPLEDIMAGEARRAYDYNAMICINDEXING,
    SHADERSTORAGEBUFFERARRAYDYNAMICINDEXING,
    SHADERSTORAGEIMAGEARRAYDYNAMICINDEXING,
    SHADERCLIPDISTANCE,
    SHADERCULLDISTANCE,
    SHADERFLOAT64,
    SHADERINT64,
    SHADERINT16,
    SHADERRESOURCERESIDENCY,
    SHADERRESOURCEMINLOD,
    SPARSEBINDING,
    SPARSERESIDENCYBUFFER,
    SPARSERESIDENCYIMAGE2D,
    SPARSERESIDENCYIMAGE3D,
    SPARSERESIDENCY2SAMPLES,
    SPARSERESIDENCY4SAMPLES,
    SPARSERESIDENCY8SAMPLES,
    SPARSERESIDENCY16SAMPLES,
    SPARSERESIDENCYALIASED,
    VARIABLEMULTISAMPLERATE,
    INHERITEDQUERIES
)

internal fun supportsFeatures(
    supported: VkPhysicalDeviceFeatures,
    requested: VkPhysicalDeviceFeatures,
    extensionsSupported: GenericFeaturesPNextNode.Buffer?,
    extensionsRequested: ArrayList<GenericFeaturesPNextNode>
): Boolean {

    for (offset in VK_PHYSICAL_DEVICE_FEATURES_OFFSET) {
        if (memGetInt(requested.address() + offset) != 0 && memGetInt(supported.address() + offset) == 0) {
            return false
        }
    }

    val nnExtensionsSupported = extensionsSupported ?: GenericFeaturesPNextNode.create(-1L, 0)

    for (i in extensionsRequested.indices) {
        if (!GenericFeaturesPNextNode.match(extensionsRequested[i], nnExtensionsSupported[i])) {
            return false
        }
    }

    return true
}

data class SurfaceSupportDetails(
    val capabilities: VkSurfaceCapabilitiesKHR,
    val formats: VkSurfaceFormatKHR.Buffer,
    val presentModes: IntBuffer
)

// must be called in a stack
fun querySurfaceSupportDetails(device: VkPhysicalDevice, surface: VkSurfaceKHR) = runCatching {
    stackGet().run {
        val capabilities = VkSurfaceCapabilitiesKHR.mallocStack()
        var ret = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, capabilities)
        if (ret != VK_SUCCESS) {
            capabilities.free()
            SwapchainError.FAILED_QUERY_SURFACE_SUPPORT_DETAILS.throwError(ret)
        }

        val ip = mallocInt(1)
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, ip, null)
        val formats = VkSurfaceFormatKHR.mallocStack(ip[0])
        ret = vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, ip, formats)
        if (ret != VK_SUCCESS) {
            capabilities.free()
            formats.free()
            SwapchainError.FAILED_QUERY_SURFACE_SUPPORT_DETAILS.throwError(ret)
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, ip, null)
        val presentModes = mallocInt(ip[0])
        ret = vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, ip, presentModes)
        if (ret != VK_SUCCESS) {
            capabilities.free()
            formats.free()
            memFree(presentModes)
            SwapchainError.FAILED_QUERY_SURFACE_SUPPORT_DETAILS.throwError(ret)
        }

        SurfaceSupportDetails(capabilities, formats, presentModes)
    }
}

fun findSurfaceFormat(
    physicalDevice: VkPhysicalDevice,
    availableFormats: VkSurfaceFormatKHR.Buffer,
    desiredFormats: List<SurfaceFormat>,
    featureFlags: VkFormatFeatureFlags
): SurfaceFormat = stack {
    val formatProperties = VkFormatProperties.mallocStack()
    return desiredFormats.firstOrNull { format ->
        availableFormats.any {
            if (it.format() == format.format && it.colorSpace() == format.colorspace) {
                vkGetPhysicalDeviceFormatProperties(physicalDevice, it.format(), formatProperties)
                featureFlags == featureFlags and formatProperties.optimalTilingFeatures()
            } else false
        }
    } ?: availableFormats.first().run { SurfaceFormat(format(), colorSpace()) }
}

fun findPresentMode(
    availablePresentMode: IntBuffer,
    desiredPresentModes: List<VkPresentModeKHR>
): VkPresentModeKHR {
    return desiredPresentModes.firstOrNull { mode ->
        availablePresentMode.indices.asSequence().map { availablePresentMode.get(it) }
            .any { mode == it }
    } ?: VK_PRESENT_MODE_FIFO_KHR
}

// will malloc on MemoryStack.stackGet() if needed
fun findExtent(capabilities: VkSurfaceCapabilitiesKHR, desiredWidth: Int, desiredHeight: Int): VkExtent2D {
    return if (capabilities.currentExtent().width() != UInt.MAX_VALUE.toInt()) {
        capabilities.currentExtent()
    } else {
        VkExtent2D.mallocStack()
            .width(desiredWidth.coerceIn(capabilities.minImageExtent().width(), capabilities.maxImageExtent().width()))
            .height(
                desiredHeight.coerceIn(
                    capabilities.minImageExtent().height(),
                    capabilities.maxImageExtent().height()
                )
            )
    }
}
