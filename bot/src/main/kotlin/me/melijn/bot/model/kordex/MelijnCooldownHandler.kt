package me.melijn.bot.model.kordex

import com.kotlindiscord.kord.extensions.commands.CommandContext
import com.kotlindiscord.kord.extensions.commands.events.ApplicationCommandInvocationEvent
import com.kotlindiscord.kord.extensions.commands.events.ChatCommandInvocationEvent
import com.kotlindiscord.kord.extensions.commands.events.CommandInvocationEvent
import com.kotlindiscord.kord.extensions.usagelimits.DiscriminatingContext
import com.kotlindiscord.kord.extensions.usagelimits.cooldowns.CooldownType
import com.kotlindiscord.kord.extensions.usagelimits.cooldowns.DefaultCooldownHandler
import com.kotlindiscord.kord.extensions.usagelimits.ratelimits.UsageHistory
import kotlin.time.Duration

class MelijnCooldownHandler : DefaultCooldownHandler() {

    private fun getContext(invocationEvent: CommandInvocationEvent<*, *>): DiscriminatingContext {
        val context = when (invocationEvent) {
            is ApplicationCommandInvocationEvent -> DiscriminatingContext(invocationEvent)
            is ChatCommandInvocationEvent -> DiscriminatingContext(invocationEvent)
        }
        return context
    }

    override suspend fun checkCommandOnCooldown(context: DiscriminatingContext): Boolean {
        val hitCooldowns = ArrayList<Triple<CooldownType, UsageHistory, Long>>()
        val currentTime = System.currentTimeMillis()
        val encapsulateStart = currentTime - backOffTime.inWholeMilliseconds
        var shouldSendMessage = true

        for (type in PersistentUsageLimitType.values()) {
            val until = type.getCooldown(context)
            val usageHistory = type.getUsageHistory(context)

            // keeps only the crossedCooldowns which are in the cooldowns range.
            usageHistory.removeExpiredCrossedCooldowns(encapsulateStart)

            if (until > currentTime) {
                if (!shouldSendMessage(until, usageHistory, type)) shouldSendMessage = false
                usageHistory.addCrossedCooldown(currentTime)

                hitCooldowns.add(Triple(type, usageHistory, until))
            }

            type.setUsageHistory(context, usageHistory)
        }

        if (shouldSendMessage) {
            val (maxType, maxUsageHistory, maxUntil) = hitCooldowns.maxByOrNull {
                it.third
            } ?: return false
            sendCooldownMessage(context, maxType, maxUsageHistory, maxUntil)
        }

        return hitCooldowns.isNotEmpty()
    }

    /**
     * Executed after a command execution. Stores the longest cooldown for each configured [CooldownType].
     */
    override suspend fun onExecCooldownUpdate(
        commandContext: CommandContext,
        invocationEvent: CommandInvocationEvent<*, *>,
        success: Boolean
    ) {
        val context = getContext(invocationEvent)
        return onExecCooldownUpdate(commandContext, context, success)
    }

    override suspend fun getMessage(
        context: DiscriminatingContext,
        discordTimeStamp: String,
        type: CooldownType,
    ): String {
        val locale = context.locale()
        val translationsProvider = context.event.command.translationsProvider
        val commandName = context.event.command.getFullName(locale)
        return when (type) {
            PersistentUsageLimitType.USER_COMMAND -> translationsProvider.translate(
                "cooldown.notifier.commandUser",
                locale,
                replacements = arrayOf(discordTimeStamp, commandName)
            )

            PersistentUsageLimitType.USER -> translationsProvider.translate(
                "cooldown.notifier.globalUser",
                locale,
                replacements = arrayOf(discordTimeStamp)
            )

            PersistentUsageLimitType.GUILD_USER -> translationsProvider.translate(
                "cooldown.notifier.globalUserGuild",
                locale,
                replacements = arrayOf(discordTimeStamp)
            )

            else -> super.getMessage(context, discordTimeStamp, type)
        }
    }

    override suspend fun onExecCooldownUpdate(
        commandContext: CommandContext,
        context: DiscriminatingContext,
        success: Boolean,
    ) {
        if (!success) return
        for (t in PersistentUsageLimitType.values()) {
            val u = commandContext.command.cooldownMap[t]
            val commandDuration = u?.let { it(context) } ?: Duration.ZERO
            val providedCooldown = cooldownProvider.getCooldown(context, t)
            val progressiveCommandDuration = commandContext.cooldownCounters[t] ?: Duration.ZERO

            val cooldowns = arrayOf(commandDuration, providedCooldown, progressiveCommandDuration)
            val longestDuration = cooldowns.max()
            if (longestDuration == Duration.ZERO) continue

            t.setCooldown(context, System.currentTimeMillis() + longestDuration.inWholeMilliseconds)
        }
    }
}