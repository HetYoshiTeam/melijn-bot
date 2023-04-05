package me.melijn.bot.database.manager

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.melijn.ap.injector.Inject
import me.melijn.gen.PrefixesData
import me.melijn.gen.database.manager.AbstractPrefixesManager
import me.melijn.kordkommons.database.DriverManager
import net.dv8tion.jda.api.entities.ISnowflake

@Inject
class PrefixManager(driverManager: DriverManager) : AbstractPrefixesManager(driverManager) {

    suspend fun getPrefixes(id: ISnowflake): List<PrefixesData> {
        return getCachedByIndex0(id.idLong)
    }

    private suspend fun getCachedByIndex0(id: Long): List<PrefixesData> {
        val key = "melijn:prefixes:${id}"
        driverManager.getCacheEntry(key, 5)?.run {
            return Json.decodeFromString(this)
        }
        val cachable = getByIndex0(id)
        val cachableStr = Json.encodeToString(cachable)
        driverManager.setCacheEntry(key, cachableStr, 5)
        return cachable
    }
}