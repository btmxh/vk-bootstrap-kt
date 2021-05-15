package io.github.ngoduyanh

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR

fun main() {
    Configuration.DEBUG.set(true)
    Configuration.DEBUG_MEMORY_ALLOCATOR.set(true)

    glfwInit()

    run {
        val window = glfwCreateWindow(100, 100, "aayay", NULL, NULL)
        glfwMakeContextCurrent(window)
    }

    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
    val window = glfwCreateWindow(300, 300, "Window", NULL, NULL)

    val instance = buildInstance {
        appName = "Example Vulkan Application"
        requestValidationLayers = true
        useDefaultDebugMessenger()
    }.getOrThrow()

    val surface = stack {
        val lp = mallocLong(1)
        glfwCreateWindowSurface(instance.instance, window, null, lp)
        lp[0]
    }

    val physicalDevice = instance.selectPhysicalDevice {
        this.surface = surface
        setMinimumVersion(1, 1, 0)
        requireDedicatedTransferQueue = true
    }.getOrThrow()
    println("Physical Device name: ${physicalDevice.properties.deviceNameString()}")

    val device = physicalDevice.buildDevice {}.getOrThrow()

    val graphicsQueue = device.getQueue(QueueType.GRAPHICS).getOrThrow()
    println("Graphics Queue Handle: $graphicsQueue")

    val swapchain = device.buildSwapchain {
//        this.surface = surface
    }.getOrThrow()
    println("Swapchain Handle: ${swapchain.swapchain}")

    swapchain.free()
    device.free()
    physicalDevice.free()
    vkDestroySurfaceKHR(instance.instance, surface, null)
    instance.free()

    glfwDestroyWindow(window)
    glfwTerminate()
}
