package org.valkyrienskies.mod.common

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class MixinConfigTest {

    @Test
    fun trackedEntityAccessorIsRegistered() {
        val configPath = Paths.get("src/main/resources/valkyrienskies-common.mixins.json")
        val mixinConfig = JsonParser.parseString(Files.readString(configPath)).asJsonObject
        val mixins = mixinConfig.getAsJsonArray("mixins").map { it.asString }

        assertTrue(
            "TrackedEntityAccessor must be listed in valkyrienskies-common.mixins.json",
            mixins.contains("accessors.server.level.TrackedEntityAccessor")
        )
    }
}
