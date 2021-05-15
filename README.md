# vk-bootstrap-kt
[vk-bootstrap](https://github.com/charles-lunarg/vk-bootstrap) for Kotlin (JVM) + LWJGL

A utility library that jump starts initialization of Vulkan, which simplifies the tedious process of:
* Instance creation
* Physical Device selection
* Device creation
* Getting queues
* Swapchain creation

and several conveniences

More info in the C++ version, head there for the [Getting Started](https://github.com/charles-lunarg/vk-bootstrap/blob/master/docs/getting_started.md) guide

## Basic Usage

```kotlin
fun initVulkan(): Boolean {
    val instanceRet = buildInstance {
        appName = "Example Vulkan Application"
        requestValidationLayers = true
        useDefaultDebugMessenger()
    }

    if(instanceRet.isFailure) {
        System.err.println("Failed to create Vulkan instance. Error: ${instanceRet.exceptionOrNull()!!.message}")
        return false
    }

    val instance = instanceRet.getOrNull()!!

    val physRet = instance.selectPhysicalDevice {
        surface = /* from user created window */ 0L
        setMinimumVersion(1, 1) // require a vulkan 1.1 capable device
        requireDedicatedTransferQueue = true
    }

    if(physRet.isFailure) {
        System.err.println("Failed to select a Vulkan Physical Device. Error: ${physRet.exceptionOrNull()!!.message}")
        instance.free() // no RAII in Kotlin, you should go for a more-Kotlin way to do this
        return false
    }

    val deviceRet = physRet.getOrNull()!!.buildDevice {}
    // automatically propagate needed data from instance & physical device

    if(deviceRet.isFailure) {
        System.err.println("Failed to create a Vulkan device. Error: ${deviceRet.exceptionOrNull()!!.message}")
        physRet.getOrNull()?.free()
        instance.free()
        return false
    }

    // Get the VkDevice handle used in the rest of a vulkan application
    val device = deviceRet.getOrNull()!!

    // Get the graphics queue with a helper function
    val graphicsQueueRet = device.getQueue(QueueType.GRAPHICS)
    if(graphicsQueueRet.isFailure) {
        System.err.println("Failed to get graphics queue. Error: ${graphicsQueueRet.exceptionOrNull()!!.message}")
        device.free()
        physRet.getOrNull()?.free()
        instance.free()
        return false
    }

    // Turned 400-500 lines of boilerplate into less than fifty (in C++, a little more in Kotlin)
    val graphicsQueue = graphicsQueueRet.getOrNull()!!

    return true
}
```

## Setting up vk-bootstrap-kt

idk lmao, clone this and use subprojects maybe???
