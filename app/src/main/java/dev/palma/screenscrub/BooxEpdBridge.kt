package dev.palma.screenscrub

import android.content.Context
import android.view.View
import java.lang.reflect.Modifier
import java.lang.reflect.Method

class BooxEpdBridge {
    fun regalPolicyInventory(context: Context, packages: List<String>): List<String> {
        val entries = mutableListOf<String>()
        val helper = runCatching { Class.forName(EInkHelperClass) }.getOrElse {
            return listOf("REGAL_POLICY class=$EInkHelperClass unavailable=${describe(it)}")
        }
        entries += "REGAL_POLICY class=${helper.name} loader=${helper.classLoader ?: "bootstrap"}"
        entries += runCatching {
            "REGAL_POLICY isServiceReady=${helper.getMethod("isServiceReady").invoke(null)}"
        }.getOrElse { "REGAL_POLICY isServiceReady=unavailable=${describe(it)}" }
        val allowMethod = runCatching {
            helper.getMethod("allowUseRegalMode", Context::class.java, String::class.java)
        }.getOrElse {
            entries += "REGAL_POLICY method=allowUseRegalMode unavailable=${describe(it)}"
            null
        }
        packages.forEach { packageName ->
            val result = if (allowMethod == null) {
                "unavailable"
            } else {
                runCatching { allowMethod?.invoke(null, context, packageName)?.toString() ?: "null" }
                    .getOrElse { "error=${describe(it)}" }
            }
            entries += "REGAL_POLICY package=$packageName allowUseRegalMode=$result"
        }
        val allowSet = runCatching {
            helper.getDeclaredMethod("getAllowUseRegalModePkgSet").invoke(null)
        }.fold(
            onSuccess = { it?.toString() ?: "null" },
            onFailure = { "unavailable=${describe(it)}" },
        )
        entries += "REGAL_POLICY allowUseRegalModePkgSet=$allowSet"
        return entries
    }

    fun invalidateGc(view: View): ApiResult = runCatching {
        val controllerClass = Class.forName(EpdControllerClass)
        val updateModeClass = Class.forName(UpdateModeClass)
        val gcMode = updateModeClass.enumConstants?.firstOrNull {
            (it as Enum<*>).name == "GC"
        } ?: error("UpdateMode.GC is unavailable")
        val method = controllerClass.getMethod("invalidate", View::class.java, updateModeClass)
        method.invoke(null, view, gcMode)
        "EpdController.invalidate(view, UpdateMode.GC) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun applyManagerGc(view: View): ApiResult = runCatching {
        val managerClass = Class.forName(EpdDeviceManagerClass)
        val method: Method = managerClass.getMethod("applyGCUpdate", View::class.java)
        method.invoke(null, view)
        "EpdDeviceManager.applyGCUpdate(view) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun repaintEverythingGc(): ApiResult = runCatching {
        val controllerClass = Class.forName(EpdControllerClass)
        val updateModeClass = Class.forName(UpdateModeClass)
        val gcMode = gcMode(updateModeClass)
        controllerClass.getMethod("repaintEveryThing", updateModeClass).invoke(null, gcMode)
        "EpdController.repaintEveryThing(UpdateMode.GC) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun invalidateGcRect(view: View): ApiResult = runCatching {
        require(view.width > 0 && view.height > 0) { "View is not laid out yet" }
        val controllerClass = Class.forName(EpdControllerClass)
        val updateModeClass = Class.forName(UpdateModeClass)
        val gcMode = gcMode(updateModeClass)
        controllerClass.getMethod(
            "invalidate",
            View::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            updateModeClass,
        ).invoke(null, view, 0, 0, view.width, view.height, gcMode)
        "EpdController.invalidate(view, 0, 0, ${view.width}, ${view.height}, GC) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun refreshScreenGc(view: View): ApiResult = runCatching {
        val controllerClass = Class.forName(EpdControllerClass)
        val updateModeClass = Class.forName(UpdateModeClass)
        controllerClass.getMethod("refreshScreen", View::class.java, updateModeClass)
            .invoke(null, view, gcMode(updateModeClass))
        "EpdController.refreshScreen(view, UpdateMode.GC) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun refreshScreenRegionGc(view: View): ApiResult = runCatching {
        require(view.width > 0 && view.height > 0) { "View is not laid out yet" }
        val controllerClass = Class.forName(EpdControllerClass)
        val updateModeClass = Class.forName(UpdateModeClass)
        controllerClass.getMethod(
            "refreshScreenRegion",
            View::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            updateModeClass,
        ).invoke(null, view, 0, 0, view.width, view.height, gcMode(updateModeClass))
        "EpdController.refreshScreenRegion(view, 0, 0, ${view.width}, ${view.height}, UpdateMode.GC) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun refreshScreenNative(view: View, nativeMode: Int): ApiResult = runCatching {
        View::class.java.getMethod("refreshScreen", Int::class.javaPrimitiveType)
            .invoke(view, nativeMode)
        "View.refreshScreen($nativeMode) invoked directly"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun refreshScreenRegionNative(
        view: View,
        nativeMode: Int,
        left: Int = 0,
        top: Int = 0,
        width: Int = view.width,
        height: Int = view.height,
    ): ApiResult = runCatching {
        require(view.width > 0 && view.height > 0) { "View is not laid out yet" }
        View::class.java.getMethod(
            "refreshScreen",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(view, left, top, width, height, nativeMode)
        "View.refreshScreen($left, $top, $width, $height, $nativeMode) invoked directly"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun repaintEverythingNative(nativeMode: Int): ApiResult = runCatching {
        val deviceClass = Class.forName(DeviceClass)
        val device = deviceClass.getMethod("currentDevice").invoke(null)
            ?: error("Device.currentDevice() returned null")
        val cachedMethods = mutableListOf<Method>()
        var currentClass: Class<*>? = device.javaClass
        while (currentClass != null) {
            currentClass.declaredFields
                .filter { it.type == Method::class.java }
                .forEach { field ->
                    field.isAccessible = true
                    val receiver = if (Modifier.isStatic(field.modifiers)) null else device
                    (field.get(receiver) as? Method)?.let(cachedMethods::add)
                }
            currentClass = currentClass.superclass
        }
        val repaintMethod = cachedMethods.firstOrNull { method ->
            method.name == "repaintEverything" &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: error("Cached ViewUpdateHelper.repaintEverything(int) method is unavailable")
        repaintMethod.invoke(null, nativeMode)
        "ViewUpdateHelper.repaintEverything($nativeMode) invoked through SDK cache"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun applyControllerGcOnce(): ApiResult = runCatching {
        Class.forName(EpdControllerClass).getMethod("applyGCOnce").invoke(null)
        "EpdController.applyGCOnce() invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun applyCurrentDeviceGcOnce(): ApiResult = runCatching {
        val deviceClass = Class.forName(DeviceClass)
        val device = deviceClass.getMethod("currentDevice").invoke(null)
            ?: error("Device.currentDevice() returned null")
        device.javaClass.getMethod("applyGCOnce").invoke(device)
        "Device.currentDevice().applyGCOnce() invoked (${device.javaClass.simpleName})"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun refreshManagerGcInterval(view: View, enabled: Boolean): ApiResult = runCatching {
        Class.forName(EpdDeviceManagerClass).getMethod(
            "refreshScreenWithGCInterval",
            View::class.java,
            Boolean::class.javaPrimitiveType,
        ).invoke(null, view, enabled)
        "EpdDeviceManager.refreshScreenWithGCInterval(view, $enabled) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun refreshManagerGcIntervalWithoutRegal(view: View): ApiResult = runCatching {
        Class.forName(EpdDeviceManagerClass)
            .getMethod("refreshScreenWithGCIntervalWithoutRegal", View::class.java)
            .invoke(null, view)
        "EpdDeviceManager.refreshScreenWithGCIntervalWithoutRegal(view) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun refreshManagerGcIntervalWithRegal(view: View): ApiResult = runCatching {
        Class.forName(EpdDeviceManagerClass)
            .getMethod("refreshScreenWithGCIntervalWithRegal", View::class.java)
            .invoke(null, view)
        "EpdDeviceManager.refreshScreenWithGCIntervalWithRegal(view) invoked"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun relatedApiMethods(): List<String> {
        val terms = listOf(
            "gc", "full", "clear", "refresh", "repaint", "invalidate", "update", "wait", "finish",
            "complete", "busy", "sync", "post", "waveform", "mode", "region", "rect", "epd", "eink", "tcon", "init",
        )
        val classMethods = ApiClasses.flatMap { className ->
            runCatching {
                Class.forName(className).methods
                    .filter { method ->
                        method.declaringClass != Any::class.java &&
                            terms.any { term -> method.name.contains(term, ignoreCase = true) }
                    }
                    .map { method -> formatMethod(className, method) }
            }.getOrElse { listOf("$className unavailable: ${describe(it)}") }
        }
        val runtimeDeviceMethods = runCatching {
            val deviceClass = Class.forName(DeviceClass)
            val device = deviceClass.getMethod("currentDevice").invoke(null)
                ?: error("Device.currentDevice() returned null")
            listOf("Device.currentDevice() -> ${device.javaClass.name}") +
                device.javaClass.methods
                    .filter { method ->
                        method.declaringClass != Any::class.java &&
                            terms.any { term -> method.name.contains(term, ignoreCase = true) }
                    }
                    .map { method -> formatMethod("Device.currentDevice()", method) }
        }.getOrElse { listOf("Device.currentDevice() unavailable: ${describe(it)}") }
        return (classMethods + runtimeDeviceMethods).sorted()
    }

    fun firmwareMappingInventory(view: View): List<String> {
        val lines = mutableListOf(
            "MAPPING_POLICY readOnly=true refreshInvocations=0 settingsChanged=0 targetSdk=${view.context.applicationInfo.targetSdkVersion}",
        )
        fun collect(label: String, action: () -> List<String>) {
            lines += runCatching(action).getOrElse {
                listOf("MAPPING_ERROR section=$label error=${describe(it)}")
            }
        }

        val updateModeClass = runCatching { Class.forName(UpdateModeClass) }.getOrNull()
        val controllerClass = runCatching { Class.forName(EpdControllerClass) }.getOrNull()
        val helperClass = runCatching { Class.forName(ViewUpdateHelperClass) }.getOrNull()
        val device = runCatching {
            Class.forName(DeviceClass).getMethod("currentDevice").invoke(null)
                ?: error("Device.currentDevice() returned null")
        }.getOrNull()

        collect("class-origin") {
            listOfNotNull(updateModeClass, controllerClass, helperClass, device?.javaClass, View::class.java)
                .distinctBy { it.name }
                .map { clazz -> formatClassOrigin(clazz) }
        }
        collect("view-methods") {
            View::class.java.declaredMethods
                .filter { method ->
                    method.name.contains("refresh", ignoreCase = true) ||
                        method.name.contains("invalidate", ignoreCase = true) ||
                        method.name.contains("update", ignoreCase = true) ||
                        method.name.contains("waveform", ignoreCase = true)
                }
                .sortedWith(compareBy(Method::getName, Method::getParameterCount))
                .map { method -> "MAPPING_VIEW_METHOD ${formatMethodTarget(method)}" }
        }
        collect("helper-constants") {
            val clazz = helperClass ?: error("$ViewUpdateHelperClass unavailable")
            val prefixes = listOf(
                "EINK_WAVEFORM_MODE_",
                "EINK_UPDATE_MODE_",
                "EINK_WAIT_MODE_",
                "EINK_ONYX_",
                "EINK_AUTO_MODE_",
                "EINK_REAGL_",
            )
            val fields = clazz.declaredFields
                .filter { field ->
                    Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType &&
                        prefixes.any { prefix -> field.name.startsWith(prefix) }
                }
                .sortedBy { it.name }
            listOf(
                "MAPPING_CONSTANT_SUMMARY class=${clazz.name} declaredFields=${clazz.declaredFields.size} " +
                    "publicFields=${clazz.fields.size} matchingIntFields=${fields.size}",
            ) + fields.map { field ->
                    field.isAccessible = true
                    "MAPPING_CONSTANT ${field.name}=${field.getInt(null)}"
                }
        }
        collect("expected-helper-fields") {
            val clazz = helperClass ?: error("$ViewUpdateHelperClass unavailable")
            listOf(
                "EINK_WAVEFORM_MODE_DU",
                "EINK_WAVEFORM_MODE_GU",
                "EINK_WAVEFORM_MODE_GC",
                "EINK_WAVEFORM_MODE_ANIM",
                "EINK_WAVEFORM_MODE_REAGL",
                "EINK_UPDATE_MODE_PARTIAL",
                "EINK_WAIT_MODE_NOWAIT",
            ).flatMap { name ->
                listOf(
                    explicitFieldLookup(clazz, name, declared = true),
                    explicitFieldLookup(clazz, name, declared = false),
                )
            }
        }
        collect("helper-shape") {
            val clazz = helperClass ?: error("$ViewUpdateHelperClass unavailable")
            listOf(
                "MAPPING_HELPER_SHAPE class=${clazz.name} declaredMethods=${clazz.declaredMethods.size} " +
                    "publicMethods=${clazz.methods.size} declaredClasses=${clazz.declaredClasses.joinToString(",") { it.name }}",
            ) + clazz.declaredMethods
                .filter { method ->
                    listOf("waveform", "update", "refresh", "wait", "mode")
                        .any { term -> method.name.contains(term, ignoreCase = true) }
                }
                .sortedBy { it.name }
                .map { method -> "MAPPING_HELPER_METHOD ${formatMethodTarget(method)}" }
        }
        collect("other-platform-constants") {
            listOfNotNull(
                View::class.java,
                runCatching { Class.forName(EInkHelperClass) }.getOrNull(),
            ).flatMap { clazz ->
                clazz.declaredFields
                    .filter { field ->
                        Modifier.isStatic(field.modifiers) &&
                            listOf("EINK", "WAVEFORM", "UPDATE_MODE", "WAIT_MODE", "REAGL")
                                .any { term -> field.name.contains(term, ignoreCase = true) }
                    }
                    .sortedBy { it.name }
                    .map { field ->
                        val value = runCatching {
                            field.isAccessible = true
                            field.get(null)
                        }.fold(onSuccess = { it?.toString() ?: "null" }, onFailure = { "error:${describe(it)}" })
                        "MAPPING_PLATFORM_CONSTANT class=${clazz.name} field=${field.name} type=${field.type.name} value=$value"
                    }
            }
        }
        collect("sdk-method-cache") {
            val runtimeDevice = device ?: error("Device.currentDevice() unavailable")
            runtimeDevice.javaClass.declaredFields
                .filter { field ->
                    Modifier.isStatic(field.modifiers) && Method::class.java.isAssignableFrom(field.type)
                }
                .mapNotNull { field ->
                    field.isAccessible = true
                    val method = field.get(null) as? Method ?: return@mapNotNull null
                    val relevant = listOf("refresh", "invalidate", "repaint", "wait", "update", "waveform")
                        .any { term -> method.name.contains(term, ignoreCase = true) }
                    if (!relevant) null else "MAPPING_METHOD_CACHE field=${field.name} target=${formatMethodTarget(method)}"
                }
                .sorted()
        }
        collect("enum-translation") {
            val clazz = updateModeClass ?: error("$UpdateModeClass unavailable")
            val runtimeDevice = device ?: error("Device.currentDevice() unavailable")
            val translator = runtimeDevice.javaClass.getDeclaredMethod("a", clazz).apply {
                require(returnType == Int::class.javaPrimitiveType) { "Unexpected translator return type: $returnType" }
                isAccessible = true
            }
            clazz.enumConstants?.map { constant ->
                val enumValue = constant as Enum<*>
                val nativeValue = translator.invoke(runtimeDevice, constant)
                "MAPPING_ENUM name=${enumValue.name} ordinal=${enumValue.ordinal} sdkNative=$nativeValue translator=${translator.name}"
            } ?: error("$UpdateModeClass has no enum constants")
        }
        collect("sdk-int-cache") {
            val runtimeDevice = device ?: error("Device.currentDevice() unavailable")
            runtimeDevice.javaClass.declaredFields
                .filter { field -> Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType }
                .sortedBy { it.name }
                .map { field ->
                    field.isAccessible = true
                    "MAPPING_SDK_INT_CACHE field=${field.name} value=${field.getInt(null)}"
                }
        }
        collect("current-defaults") {
            val clazz = controllerClass ?: error("$EpdControllerClass unavailable")
            val runtimeDevice = device ?: error("Device.currentDevice() unavailable")
            listOf(
                "MAPPING_DEFAULT system=${clazz.getMethod("getSystemDefaultUpdateMode").invoke(null)}",
                "MAPPING_DEFAULT view=${clazz.getMethod("getViewDefaultUpdateMode", View::class.java).invoke(null, view)}",
                "MAPPING_DEFAULT shouldVerifyUpdateModel=${runtimeDevice.javaClass.getMethod("shouldVerifyUpdateModel").invoke(runtimeDevice)}",
            )
        }
        collect("native-current-values") {
            val viewClass = View::class.java
            val eInkHelperClass = Class.forName(EInkHelperClass)
            listOf(
                "MAPPING_NATIVE_CURRENT viewDefault=${viewClass.getDeclaredMethod("getDefaultUpdateMode").invoke(view)}",
                "MAPPING_NATIVE_CURRENT global=${viewClass.getDeclaredMethod("getGlobalUpdateMode").invoke(null)}",
                "MAPPING_NATIVE_CURRENT firstDraw=${viewClass.getDeclaredMethod("getFirstDrawUpdateMode").invoke(null)}",
                "MAPPING_NATIVE_CURRENT appScopeRefreshMode=${eInkHelperClass.getDeclaredMethod("getAppScopeRefreshMode").invoke(null)}",
            )
        }
        return lines
    }

    fun panelSize(): ApiResult = runCatching {
        val controllerClass = Class.forName(EpdControllerClass)
        val width = controllerClass.getMethod("getEpdWidth").invoke(null)
        val height = controllerClass.getMethod("getEpdHeight").invoke(null)
        "EpdController panel metrics: ${width}x${height}"
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    fun waitForUpdateFinished(target: WaitTarget): ApiResult = runCatching {
        when (target) {
            WaitTarget.CONTROLLER -> {
                Class.forName(EpdControllerClass).getMethod("waitForUpdateFinished").invoke(null)
                "EpdController.waitForUpdateFinished() returned"
            }
            WaitTarget.CURRENT_DEVICE -> {
                val deviceClass = Class.forName(DeviceClass)
                val device = deviceClass.getMethod("currentDevice").invoke(null)
                    ?: error("Device.currentDevice() returned null")
                device.javaClass.getMethod("waitForUpdateFinished").invoke(device)
                "Device.currentDevice().waitForUpdateFinished() returned (${device.javaClass.simpleName})"
            }
        }
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Unavailable(describe(it)) },
    )

    private fun gcMode(updateModeClass: Class<*>): Any = updateModeClass.enumConstants?.firstOrNull {
        (it as Enum<*>).name == "GC"
    } ?: error("UpdateMode.GC is unavailable")

    private fun formatMethod(className: String, method: Method): String {
        val receiver = if (Modifier.isStatic(method.modifiers)) "static" else "instance"
        val visibility = when {
            Modifier.isPublic(method.modifiers) -> "public"
            Modifier.isProtected(method.modifiers) -> "protected"
            Modifier.isPrivate(method.modifiers) -> "private"
            else -> "package"
        }
        val arguments = method.parameterTypes.joinToString(", ") { it.name }
        return "$className declaring=${method.declaringClass.name} $visibility $receiver " +
            "${method.name}($arguments) -> ${method.returnType.name} synthetic=${method.isSynthetic} bridge=${method.isBridge} invocation=public-reflection"
    }

    private fun formatMethodTarget(method: Method): String {
        val receiver = if (Modifier.isStatic(method.modifiers)) "static" else "instance"
        val arguments = method.parameterTypes.joinToString(",") { it.name }
        return "${method.declaringClass.name}.${method.name}($arguments)->${method.returnType.name} $receiver"
    }

    private fun formatClassOrigin(clazz: Class<*>): String {
        val loader = clazz.classLoader?.toString() ?: "bootstrap"
        val resource = runCatching {
            clazz.getResource("/${clazz.name.replace('.', '/')}.class")?.toString()
        }.getOrNull() ?: "unavailable"
        return "MAPPING_CLASS name=${clazz.name} loader=$loader resource=$resource"
    }

    private fun explicitFieldLookup(clazz: Class<*>, name: String, declared: Boolean): String {
        val lookup = if (declared) "getDeclaredField" else "getField"
        return runCatching {
            val field = if (declared) clazz.getDeclaredField(name) else clazz.getField(name)
            field.isAccessible = true
            "MAPPING_FIELD_LOOKUP class=${clazz.name} lookup=$lookup name=$name type=${field.type.name} value=${field.get(null)}"
        }.getOrElse {
            "MAPPING_FIELD_LOOKUP class=${clazz.name} lookup=$lookup name=$name error=${describe(it)}"
        }
    }

    private fun describe(error: Throwable): String {
        val cause = error.cause ?: error
        return "${cause.javaClass.simpleName}: ${cause.message ?: "API not present"}"
    }

    sealed interface ApiResult {
        data class Success(val message: String) : ApiResult
        data class Unavailable(val reason: String) : ApiResult
    }

    enum class WaitTarget(val label: String) {
        CONTROLLER("EpdController.waitForUpdateFinished"),
        CURRENT_DEVICE("Device.currentDevice().waitForUpdateFinished"),
    }

    private companion object {
        const val EpdControllerClass = "com.onyx.android.sdk.api.device.epd.EpdController"
        const val UpdateModeClass = "com.onyx.android.sdk.api.device.epd.UpdateMode"
        const val EpdDeviceManagerClass = "com.onyx.android.sdk.api.device.EpdDeviceManager"
        const val DeviceClass = "com.onyx.android.sdk.device.Device"
        const val ViewUpdateHelperClass = "android.onyx.ViewUpdateHelper"
        const val EInkHelperClass = "android.onyx.optimization.EInkHelper"
        val ApiClasses = listOf(DeviceClass, EpdControllerClass, EpdDeviceManagerClass)
    }
}
