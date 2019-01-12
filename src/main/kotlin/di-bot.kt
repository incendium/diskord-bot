package com.iamincendium.diskord

import com.jessecorbett.diskord.api.model.Message
import com.jessecorbett.diskord.api.websocket.DiscordWebSocket
import com.jessecorbett.diskord.api.websocket.EventListener
import com.jessecorbett.diskord.util.ClientStore
import com.jessecorbett.diskord.util.sendMessage
import kotlinx.coroutines.runBlocking
import org.koin.core.KoinProperties
import org.koin.dsl.module.module
import org.koin.java.standalone.KoinJavaStarter.startKoin
import org.koin.standalone.KoinComponent
import org.koin.standalone.getProperty
import org.koin.standalone.inject

private val BOT_TOKEN = try {
    ClassLoader.getSystemResource(".bot-token").readText()
} catch (error: Exception) {
    throw RuntimeException("Failed to load the bot token. Make sure a readable file named .bot-token exists on the classpath.", error)
}

interface Command<T> {
    suspend fun action(context: T)
}
object PingCommand : Command<MessageContext> {
    override suspend fun action(context: MessageContext) = context.with {
        if (content.startsWith("!ping")) {
            reply("Pong!")
        }
    }
}

class CommandRegistry {
    private val commandList = mutableListOf<Command<MessageContext>>()

    fun register(command: Command<MessageContext>) {
        commandList += command
    }

    suspend fun dispatch(message: Message, clients: ClientStore) {
        commandList.forEach { it.action(MessageContext(message, clients)) }
    }
}

class CommandDispatcher(private val clients: ClientStore, private val registry: CommandRegistry) : EventListener() {
    override suspend fun onMessageCreate(message: Message) = registry.dispatch(message, clients)
}

class MessageContext(val message: Message, val clients: ClientStore) {
    val content get() = message.content

    val channel get() = runBlocking { clients.channels[message.channelId].get() }

    val guild get() = runBlocking { channel.guildId?.let { clients.guilds[it].get() } }

    suspend fun reply(text: String) = clients.channels[message.channelId].sendMessage(text)

    suspend fun delete() = clients.channels[message.channelId].deleteMessage(message.id)

    suspend fun with(block: suspend MessageContext.() -> Unit) { block(this) }
}

object DiskordBot : KoinComponent {
    private val dispatcher: CommandDispatcher by inject()

    fun run() {
        DiscordWebSocket(getProperty("bot-token"), dispatcher)
    }
}

val botModule = module {
    single { ClientStore(getProperty("bot-token")) }
    single { CommandRegistry().apply {
        register(PingCommand)
    } }
    single { CommandDispatcher(get(), get()) }
}

fun main() {
    val properties = KoinProperties(extraProperties = mapOf("bot-token" to BOT_TOKEN))

    startKoin(listOf(botModule), properties)

    DiskordBot.run()
}
