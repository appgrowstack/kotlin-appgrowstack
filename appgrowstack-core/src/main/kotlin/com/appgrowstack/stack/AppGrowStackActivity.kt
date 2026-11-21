package com.appgrowstack.stack

import java.time.Instant

/**
 * The last events fired and the last in-app campaigns shown, newest first. The analytics and
 * campaign connectors write it; the support connector attaches it to a request. Only names, typed
 * values and times are kept, never text the person typed.
 */
public class AppGrowStackActivity {
    public companion object {
        public const val EVENT_LIMIT: Int = 20
        public const val CAMPAIGN_LIMIT: Int = 10
    }

    public data class Event(val name: String, val at: Instant, val props: Map<String, Value> = emptyMap())

    public data class ShownCampaign(val campaign: String, val setVersion: Int, val at: Instant)

    /** An event property, typed as the analytics SDK fired it. */
    public sealed interface Value {
        public data class Text(val value: String) : Value
        public data class Integer(val value: Long) : Value
        public data class Decimal(val value: Double) : Value
        public data class Flag(val value: Boolean) : Value
    }

    private val lock = Any()
    private val eventList = ArrayDeque<Event>()
    private val campaignList = ArrayDeque<ShownCampaign>()

    /** Newest first, at most [EVENT_LIMIT]. */
    public val events: List<Event> get() = synchronized(lock) { eventList.toList() }

    /** Newest first, at most [CAMPAIGN_LIMIT]. */
    public val campaigns: List<ShownCampaign> get() = synchronized(lock) { campaignList.toList() }

    public fun record(event: Event): Unit = synchronized(lock) {
        eventList.addFirst(event)
        while (eventList.size > EVENT_LIMIT) eventList.removeLast()
    }

    public fun record(campaign: ShownCampaign): Unit = synchronized(lock) {
        campaignList.addFirst(campaign)
        while (campaignList.size > CAMPAIGN_LIMIT) campaignList.removeLast()
    }
}
