@file:Suppress("UNCHECKED_CAST")

package me.melijn.bot.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import me.melijn.kordkommons.async.DeferredNKTRunnable
import me.melijn.kordkommons.async.TaskManager
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngine
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngineFactory
import javax.script.ScriptEngine

object CodeEvalUtil {

    private val engine: ScriptEngine = KotlinJsr223JvmLocalScriptEngineFactory().scriptEngine
    private val standardImports = """
                import com.kotlindiscord.kord.extensions.commands.chat.ChatCommandContext
                import me.melijn.bot.commands.EvalCommand
                import me.melijn.bot.utils.CodeEvalUtil
                import dev.kord.core.behavior.channel.createMessage
                import dev.kord.core.behavior.edit
                import org.koin.core.component.inject
                import kotlinx.coroutines.Deferred
                import java.io.File
                import javax.imageio.ImageIO
                import kotlinx.coroutines.*""".trimIndent()

    suspend fun runCode(innerCode: String, paramStr: String, vararg params: Any?): String {
        val suppliedImports = innerCode.lines()
            .takeWhile { it.startsWith("import ") || it.startsWith("\nimport ") }
            .joinToString("\n")
        val script = innerCode.lines().dropWhile { suppliedImports.contains(it) }
            .joinToString("\n${"\t".repeat(5)}")
            .replace("return ", "return@evalTaskValueNAsync ")
        val functionName = "exec"
        val functionDefinition = "fun $functionName($paramStr): Deferred<Pair<Any?, String>> {"
        val code = """
                $standardImports
                $suppliedImports
                $functionDefinition
                    return CodeEvalUtil.evalTaskValueNAsync {
                        $script
                    }
                }""".trimIndent()

        return evalScript(code, functionName, params)
    }

    private suspend fun evalScript(code: String, functionName: String, vararg params: Any?): String {
        return try {
            engine.eval(code)
            val se = engine as KotlinJsr223JvmLocalScriptEngine
            val resp = se.invokeFunction(functionName, params) as Deferred<Pair<Any?, String>>

            val (result, error) = resp.await()
            result?.toString() ?: "ERROR:\n```${error}```"
        } catch (t: Throwable) {
            "ERROR:\n```${t.message}```"
        }
    }

    class EvalDeferredNTask<T>(private val func: suspend () -> T?) : DeferredNKTRunnable<Pair<T?, String>> {
        override suspend fun run(): Pair<T?, String> {
            return try {
                func() to ""
            } catch (t: Throwable) {
                null to (t.message ?: "unknown")
            }
        }
    }

    fun <T> evalTaskValueNAsync(block: suspend CoroutineScope.() -> T?): Deferred<Pair<T?, String>> = TaskManager.coroutineScope.async {
        EvalDeferredNTask {
            block.invoke(this)
        }.run()
    }
}