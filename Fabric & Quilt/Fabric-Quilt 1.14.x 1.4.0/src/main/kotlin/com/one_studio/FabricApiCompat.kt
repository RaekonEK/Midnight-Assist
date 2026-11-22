package com.one_studio

import net.minecraft.client.options.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier

/**
 * The 1.14 line has two incompatible Fabric API generations installed in the wild:
 *  - 0.28.5+1.14 exposes KeyBindingHelper (v1 API, only really works on 1.14.4)
 *  - 0.2.7 / 0.3.0 expose FabricKeyBinding + KeyBindingRegistry (v0 API) and are
 *    the only builds that boot on 1.14.0 - 1.14.3
 *
 * Both are addressed reflectively so a single jar keeps working no matter which
 * Fabric API generation the player has installed.
 */
internal object FabricApiCompat {
    private const val HELPER_V1 = "net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper"
    private const val REGISTRY_V0 = "net.fabricmc.fabric.api.client.keybinding.KeyBindingRegistry"
    private const val FABRIC_KEY_BINDING_V0 = "net.fabricmc.fabric.api.client.keybinding.FabricKeyBinding"

    private val helperV1: Class<*>? = try {
        Class.forName(HELPER_V1)
    } catch (t: Throwable) {
        null
    }

    fun registerKeyBinding(id: String, defaultKey: Int, category: String): KeyBinding {
        val vanilla = KeyBinding(id, defaultKey, category)

        val helper = helperV1
        if (helper != null) {
            try {
                helper.getMethod("registerKeyBinding", KeyBinding::class.java).invoke(null, vanilla)
                return vanilla
            } catch (t: Throwable) {
                // fall through to the v0 API
            }
        }

        try {
            val builderClass = Class.forName("$FABRIC_KEY_BINDING_V0\$Builder")
            val builder = builderClass.getMethod(
                "create",
                Identifier::class.java,
                InputUtil.Type::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            ).invoke(null, Identifier(id), InputUtil.Type.KEYSYM, defaultKey, category)
            val binding = builderClass.getMethod("build").invoke(builder) as KeyBinding

            val registryClass = Class.forName(REGISTRY_V0)
            val fabricKeyBindingClass = Class.forName(FABRIC_KEY_BINDING_V0)
            val registry = registryClass.getField("INSTANCE").get(null)
            registryClass.getMethod("addCategory", String::class.java).invoke(registry, category)
            registryClass.getMethod("register", fabricKeyBindingClass).invoke(registry, binding)
            return binding
        } catch (t: Throwable) {
            return vanilla
        }
    }
}
