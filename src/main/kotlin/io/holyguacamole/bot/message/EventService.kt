package io.holyguacamole.bot.message

import io.holyguacamole.bot.AVOCADO_TEXT
import io.holyguacamole.bot.controller.EventCallback
import io.holyguacamole.bot.controller.MessageEvent
import io.holyguacamole.bot.controller.UserChangeEvent
import io.holyguacamole.bot.slack.SlackUser
import io.holyguacamole.bot.slack.toUser
import io.holyguacamole.bot.user.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class EventService(val repository: AvocadoReceiptRepository, val slackClient: SlackClient, val userService: UserService) {

    private val log = LoggerFactory.getLogger(this.javaClass)

    fun process(eventCallback: EventCallback): Boolean =
            when (eventCallback.event.type) {
                "app_mention" -> processAppMentionEvent(eventCallback.event as MessageEvent)
                "message" -> processMessageEvent(eventCallback.eventId, eventCallback.event as MessageEvent)
                "user_change" -> processUserChangeEvent((eventCallback.event as UserChangeEvent).user)
                else -> false
            }

    private fun processMessageEvent(eventId: String, event: MessageEvent): Boolean {
        val mentions = event.findMentionedPeople()
        val count = event.countGuacamoleIngredients()

        if (count == 0 || mentions.isEmpty()) return false

        val sender = userService.findByUserIdOrGetFromSlack(event.user)
        if (sender == null || sender.isBot) return false

        val avocadosSentToday = repository.findBySenderToday(sender.userId).size
        if ((avocadosSentToday + count) > 5) return false

        if (repository.findByEventId(eventId).isNotEmpty()) return false

        mentions.filter {
            userService.findByUserIdOrGetFromSlack(it)?.isBot == false
        }.flatMap { mention ->
            mapUntil(count) {
                AvocadoReceipt(
                        eventId = eventId,
                        sender = event.user,
                        receiver = mention,
                        timestamp = event.ts.toDouble().toLong())
            }
        }.executeIfNotEmpty {
            it.save()
            sendReceiptMessage(event.channel, event.user, avocadosSentToday, it)
        }
        return true
    }

    private fun <T> List<T>.executeIfNotEmpty(fn: (List<T>) -> Unit): List<T> {
        if (this.isNotEmpty()) fn(this)
        return this
    }

    private fun List<AvocadoReceipt>.save() {
        if (this.isNotEmpty()) {
            repository.saveAll(this)
            log.info("Avocado sent")
        }
    }

    private fun sendReceiptMessage(channel: String, sender: String, avocadosSentToday: Int, avocadoReceipts: List<AvocadoReceipt>) {

        val uniqueReceivers = avocadoReceipts.map { it.receiver }.distinct()

        slackClient.postSentAvocadoMessage(
                channel = channel,
                sender = sender,
                avocadosEach = avocadoReceipts.size / uniqueReceivers.size,
                receivers = uniqueReceivers,
                remainingAvocados = 5 - avocadosSentToday - uniqueReceivers.size
        )
    }

    private fun processAppMentionEvent(event: MessageEvent): Boolean {
        if (event.text.toLowerCase().contains("leaderboard")) {
            slackClient.postLeaderboard(event.channel, repository.getLeaderboard().map {
                Pair(userService.findByUserIdOrGetFromSlack(it.receiver)?.name ?: it.receiver, it.count)
            }.toMap())
        }
        return true
    }

    private fun processUserChangeEvent(slackUser: SlackUser): Boolean {
        userService.replace(slackUser.toUser())
        return true
    }
}

fun <T> mapUntil(end: Int, fn: () -> T): List<T> = (0 until end).map { fn() }

fun MessageEvent.countGuacamoleIngredients(): Int = this.text.split(AVOCADO_TEXT).size - 1
fun MessageEvent.findMentionedPeople(): List<String> = Regex("<@([0-9A-Z]*?)>")
        .findAll(this.text)
        .mapNotNull { it.groups[1]?.value }
        .filter { it != this.user }
        .toList()
