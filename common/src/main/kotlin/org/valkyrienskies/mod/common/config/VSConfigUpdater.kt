package org.valkyrienskies.mod.common.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.config.ModConfig
import org.jetbrains.annotations.ApiStatus
import org.valkyrienskies.mod.api.config.VSConfigApi
import org.valkyrienskies.mod.api.config.VSConfigApi.buildForgeConfigSpec
import org.valkyrienskies.mod.api.config.VSConfigApi.update
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.hooks.VSGameEvents.ConfigUpdateEntry

object VSConfigUpdater {
    private const val CORE_SERVER_STABILIZATION_ALIAS_CATEGORY = "Valkyrien Skies Gameplay"
    private const val STARTUP_SHIP_STABILIZATION_SECONDS_KEY = "startupShipStabilizationSeconds"
    private const val STABILIZATION_DEBUG_MESSAGES_KEY = "stabilizationDebugMessages"
    private const val SERVER_STABILIZATION_CATEGORY = "Stabilization"
    private const val SERVER_STABILIZATION_SECONDS_PATH =
        "$SERVER_STABILIZATION_CATEGORY.$STARTUP_SHIP_STABILIZATION_SECONDS_KEY"
    private const val SERVER_STABILIZATION_DEBUG_MESSAGES_PATH =
        "$SERVER_STABILIZATION_CATEGORY.$STABILIZATION_DEBUG_MESSAGES_KEY"
    private const val CORE_SERVER_STABILIZATION_ALIAS_PATH =
        "$CORE_SERVER_STABILIZATION_ALIAS_CATEGORY.$STARTUP_SHIP_STABILIZATION_SECONDS_KEY"
    private const val CORE_SERVER_STABILIZATION_DEBUG_MESSAGES_ALIAS_PATH =
        "$CORE_SERVER_STABILIZATION_ALIAS_CATEGORY.$STABILIZATION_DEBUG_MESSAGES_KEY"
    private const val CORE_SERVER_FILE_NAME = "valkyrienskies/vs-core-server.toml"
    private const val CANONICAL_SERVER_FILE_NAME = "valkyrienskies/valkyrienskies-server.toml"

    @JvmStatic
    val forgeConfigValuesMap: HashMap<String, ForgeConfigSpec.ConfigValue<*>> = HashMap()

    private val configValueConsumer = { name: String, value: ForgeConfigSpec.ConfigValue<*> ->
        forgeConfigValuesMap[name] = value
    }

    private fun getLiveValueFromMap(key: String): Any? {
        return runCatching {
            forgeConfigValuesMap[key]?.get()
        }.getOrNull()
    }

    private val core_server_config = ValkyrienSkiesMod.vsCore.getServerConfig()
    private lateinit var coreServerStabilizationAliasValue: ForgeConfigSpec.IntValue
    private lateinit var coreServerStabilizationDebugMessagesAliasValue: ForgeConfigSpec.BooleanValue
    private var canonicalServerConfig: ModConfig? = null
    private var pendingCanonicalStabilizationSeconds: Int? = null
    private var pendingCanonicalDebugMessages: Boolean? = null
    val CORE_SERVER_SPEC: ForgeConfigSpec = run {
        val builder = buildForgeConfigSpec(
            configCategory = core_server_config.root,
            builder = ForgeConfigSpec.Builder(),
            forgeConfigValueConsumer = configValueConsumer
        )
        builder.push(CORE_SERVER_STABILIZATION_ALIAS_CATEGORY)
        builder.comment("Mirror of Valkyrien Skies startup/runtime ship stabilization delay in seconds.")
        coreServerStabilizationAliasValue = builder.defineInRange(
            STARTUP_SHIP_STABILIZATION_SECONDS_KEY,
            VSGameConfig.SERVER.startupShipStabilizationSeconds,
            0,
            Int.MAX_VALUE
        )
        coreServerStabilizationDebugMessagesAliasValue = builder.define(
            STABILIZATION_DEBUG_MESSAGES_KEY,
            VSGameConfig.SERVER.stabilizationDebugMessages
        )
        builder.pop()
        builder.build()
    }

    private val server_config = VSConfigApi.buildVSConfigModel(VSGameConfig.SERVER)
    val SERVER_SPEC: ForgeConfigSpec = buildForgeConfigSpec(
        configCategory = server_config.root,
        builder = ForgeConfigSpec.Builder(),
        forgeConfigValueConsumer = configValueConsumer
    ).build()

    private val common_config = VSConfigApi.buildVSConfigModel(VSGameConfig.COMMON)
    val COMMON_SPEC: ForgeConfigSpec = buildForgeConfigSpec(
        configCategory = common_config.root,
        builder = ForgeConfigSpec.Builder(),
        forgeConfigValueConsumer = configValueConsumer
    ).build()

    private val client_config = VSConfigApi.buildVSConfigModel(VSGameConfig.CLIENT)
    val CLIENT_SPEC: ForgeConfigSpec = buildForgeConfigSpec(
        configCategory = client_config.root,
        builder = ForgeConfigSpec.Builder(),
        forgeConfigValueConsumer = configValueConsumer
    ).build()

    /**
     * Call this from platform events when config is loaded or updated
     **/
    @ApiStatus.Internal
    fun update(config: ModConfig) {
        if (config.type == ModConfig.Type.SERVER && config.fileName == CANONICAL_SERVER_FILE_NAME) {
            canonicalServerConfig = config
        }

        val updatedEntries = mutableSetOf<ConfigUpdateEntry>()

        core_server_config.update(config, ConfigType.CORE_SERVER, updatedEntries)
        server_config.update(config, ConfigType.SERVER, updatedEntries)
        syncCoreServerStabilizationAlias(config, updatedEntries)
        applyPendingCanonicalStabilizationMirror(updatedEntries)
        common_config.update(config, ConfigType.COMMON, updatedEntries)
        client_config.update(config, ConfigType.CLIENT, updatedEntries)

        if (updatedEntries.isNotEmpty()) {
            VSGameEvents.configUpdated.emit(updatedEntries)
        }
    }

    @JvmStatic
    fun getLiveStartupShipStabilizationSeconds(): Int {
        val serverValue = when (val rawValue = getLiveValueFromMap(STARTUP_SHIP_STABILIZATION_SECONDS_KEY)) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull()
            else -> null
        }
        if (serverValue != null) {
            return serverValue.coerceAtLeast(0)
        }

        val aliasValue = runCatching {
            if (::coreServerStabilizationAliasValue.isInitialized) coreServerStabilizationAliasValue.get() else null
        }.getOrNull()
        if (aliasValue != null) {
            return aliasValue.coerceAtLeast(0)
        }

        return VSGameConfig.SERVER.startupShipStabilizationSeconds.coerceAtLeast(0)
    }

    @JvmStatic
    fun getLiveStabilizationDebugMessages(): Boolean {
        val serverValue = when (val rawValue = getLiveValueFromMap(STABILIZATION_DEBUG_MESSAGES_KEY)) {
            is Boolean -> rawValue
            is String -> rawValue.toBooleanStrictOrNull()
            else -> null
        }
        if (serverValue != null) {
            return serverValue
        }

        val aliasValue = runCatching {
            if (::coreServerStabilizationDebugMessagesAliasValue.isInitialized) coreServerStabilizationDebugMessagesAliasValue.get() else null
        }.getOrNull()
        if (aliasValue != null) {
            return aliasValue
        }

        return VSGameConfig.SERVER.stabilizationDebugMessages
    }

    private fun syncCoreServerStabilizationAlias(config: ModConfig, updatedEntries: MutableSet<ConfigUpdateEntry>) {
        if (config.type != ModConfig.Type.SERVER || config.fileName != CORE_SERVER_FILE_NAME) {
            return
        }

        val rawAliasValue = runCatching {
            config.configData.get<Any>(CORE_SERVER_STABILIZATION_ALIAS_PATH)
        }.getOrNull()
        val aliasValue = when (rawAliasValue) {
            is Number -> rawAliasValue.toInt()
            is String -> rawAliasValue.toIntOrNull()
            else -> null
        }

        if (aliasValue != null) {
            val sanitizedAliasValue = aliasValue.coerceAtLeast(0)
            if (VSGameConfig.SERVER.startupShipStabilizationSeconds != sanitizedAliasValue) {
                VSGameConfig.SERVER.startupShipStabilizationSeconds = sanitizedAliasValue
                updatedEntries.add(
                    ConfigUpdateEntry(
                        configType = ConfigType.SERVER,
                        category = listOf("Stabilization"),
                        name = STARTUP_SHIP_STABILIZATION_SECONDS_KEY
                    )
                )
            }
            pendingCanonicalStabilizationSeconds = sanitizedAliasValue
        }

        val rawDebugMessagesValue = runCatching {
            config.configData.get<Any>(CORE_SERVER_STABILIZATION_DEBUG_MESSAGES_ALIAS_PATH)
        }.getOrNull()
        val debugMessagesValue = when (rawDebugMessagesValue) {
            is Boolean -> rawDebugMessagesValue
            is String -> rawDebugMessagesValue.toBooleanStrictOrNull()
            else -> null
        }

        if (debugMessagesValue != null && VSGameConfig.SERVER.stabilizationDebugMessages != debugMessagesValue) {
            VSGameConfig.SERVER.stabilizationDebugMessages = debugMessagesValue
            updatedEntries.add(
                ConfigUpdateEntry(
                    configType = ConfigType.SERVER,
                    category = listOf("Stabilization"),
                    name = STABILIZATION_DEBUG_MESSAGES_KEY
                )
            )
        }
        if (debugMessagesValue != null) {
            pendingCanonicalDebugMessages = debugMessagesValue
        }
    }

    private fun applyPendingCanonicalStabilizationMirror(updatedEntries: MutableSet<ConfigUpdateEntry>) {
        val serverConfig = canonicalServerConfig ?: return
        if (serverConfig.type != ModConfig.Type.SERVER || serverConfig.fileName != CANONICAL_SERVER_FILE_NAME) {
            return
        }

        var changed = false

        pendingCanonicalStabilizationSeconds?.let { pendingSeconds ->
            val currentSeconds = runCatching {
                serverConfig.configData.get<Any>(SERVER_STABILIZATION_SECONDS_PATH)
            }.getOrNull().let { raw ->
                when (raw) {
                    is Number -> raw.toInt()
                    is String -> raw.toIntOrNull()
                    else -> null
                }
            }

            if (currentSeconds == pendingSeconds) {
                pendingCanonicalStabilizationSeconds = null
            } else {
                val applied = runCatching {
                    serverConfig.configData.set<Any>(SERVER_STABILIZATION_SECONDS_PATH, pendingSeconds)
                    true
                }.getOrDefault(false)
                if (applied) {
                    changed = true
                    updatedEntries.add(
                        ConfigUpdateEntry(
                            configType = ConfigType.SERVER,
                            category = listOf(SERVER_STABILIZATION_CATEGORY),
                            name = STARTUP_SHIP_STABILIZATION_SECONDS_KEY
                        )
                    )
                    pendingCanonicalStabilizationSeconds = null
                }
            }
        }

        pendingCanonicalDebugMessages?.let { pendingDebugMessages ->
            val currentDebugMessages = runCatching {
                serverConfig.configData.get<Any>(SERVER_STABILIZATION_DEBUG_MESSAGES_PATH)
            }.getOrNull().let { raw ->
                when (raw) {
                    is Boolean -> raw
                    is String -> raw.toBooleanStrictOrNull()
                    else -> null
                }
            }

            if (currentDebugMessages == pendingDebugMessages) {
                pendingCanonicalDebugMessages = null
            } else {
                val applied = runCatching {
                    serverConfig.configData.set<Any>(SERVER_STABILIZATION_DEBUG_MESSAGES_PATH, pendingDebugMessages)
                    true
                }.getOrDefault(false)
                if (applied) {
                    changed = true
                    updatedEntries.add(
                        ConfigUpdateEntry(
                            configType = ConfigType.SERVER,
                            category = listOf(SERVER_STABILIZATION_CATEGORY),
                            name = STABILIZATION_DEBUG_MESSAGES_KEY
                        )
                    )
                    pendingCanonicalDebugMessages = null
                }
            }
        }

        if (changed) {
            runCatching { serverConfig.save() }
        }
    }
}
