#ifndef VK_LOADER_H
#define VK_LOADER_H
/*
 * Loads either a custom Adreno/Turnip driver through adrenotools or Android's system Vulkan
 * loader (the Mali-first path), then resolves every entry point through vkGetInstanceProcAddr.
 * Device-init code probes the external-memory/modifier capabilities instead of assuming a vendor.
 * All compositor Vulkan calls go through g_vk.* .
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define VK_GLOBAL_FUNCS(X) X(CreateInstance) X(EnumerateInstanceExtensionProperties) \
    X(EnumerateInstanceVersion)

#define VK_INSTANCE_FUNCS(X) \
    X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) \
    X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceSurfaceSupportKHR) \
    X(GetPhysicalDeviceSurfaceCapabilitiesKHR) X(GetPhysicalDeviceSurfaceFormatsKHR) \
    X(CreateAndroidSurfaceKHR) X(DestroySurfaceKHR) X(CreateDevice) \
    X(GetDeviceProcAddr) X(EnumerateDeviceExtensionProperties) \
    X(GetPhysicalDeviceMemoryProperties) X(GetPhysicalDeviceFormatProperties2) \
    X(GetPhysicalDeviceImageFormatProperties2)

#define VK_DEVICE_FUNCS(X) \
    X(GetDeviceQueue) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) \
    X(CreateCommandPool) X(AllocateCommandBuffers) X(CreateSemaphore) X(CreateFence) \
    X(DeviceWaitIdle) X(CreateImage) X(GetImageMemoryRequirements) X(AllocateMemory) \
    X(BindImageMemory) X(DestroyImage) X(FreeMemory) X(AcquireNextImageKHR) \
    X(ResetCommandBuffer) X(BeginCommandBuffer) X(CmdPipelineBarrier) X(CmdBlitImage) \
    X(EndCommandBuffer) X(ResetFences) X(QueueSubmit) X(QueuePresentKHR) X(WaitForFences) \
    X(QueueWaitIdle) X(GetMemoryFdPropertiesKHR) \
    X(GetImageSubresourceLayout) X(MapMemory) X(UnmapMemory) X(CmdClearColorImage) \
    /* screen-effect chain (effects_chain.c): graphics pipelines over full-screen quads */ \
    X(CreateShaderModule) X(DestroyShaderModule) X(CreatePipelineLayout) X(DestroyPipelineLayout) \
    X(CreateGraphicsPipelines) X(DestroyPipeline) X(CreateRenderPass) X(DestroyRenderPass) \
    X(CreateFramebuffer) X(DestroyFramebuffer) X(CreateImageView) X(DestroyImageView) \
    X(CreateSampler) X(DestroySampler) X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) \
    X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(FreeDescriptorSets) \
    X(UpdateDescriptorSets) X(CmdBeginRenderPass) X(CmdEndRenderPass) X(CmdBindPipeline) \
    X(CmdBindDescriptorSets) X(CmdPushConstants) X(CmdDraw) X(CmdSetViewport) X(CmdSetScissor) \
    /* frame generation (framegen_bridge.c / vk_present.c per-present sync slots) */ \
    X(DestroySemaphore)

struct vk_api {
#define X(n) PFN_vk##n n;
    VK_GLOBAL_FUNCS(X) VK_INSTANCE_FUNCS(X) VK_DEVICE_FUNCS(X)
#undef X
};
extern struct vk_api g_vk;

/* Open a custom Adreno driver via adrenotools when all three args are supplied; otherwise open
 * Android's system libvulkan. Load global entry points and return 0 on success. */
int vk_loader_open(const char *driver_path, const char *library_name,
                   const char *native_lib_dir);
void vk_loader_load_instance(VkInstance instance);
void vk_loader_load_device(VkDevice device);
/* The driver's vkGetInstanceProcAddr (NULL before vk_loader_open): for modules that resolve their
 * own entry points, e.g. the frame-generation engines (framegen_engine.cpp). */
PFN_vkGetInstanceProcAddr vk_loader_gipa(void);

#endif
