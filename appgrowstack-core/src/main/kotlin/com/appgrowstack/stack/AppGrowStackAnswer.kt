package com.appgrowstack.stack

/**
 * How every connector reads the status of an answer to a request it sends (one table, so a
 * connector never keeps what another drops).
 *
 * | Status | Answer | What the connector does |
 * |---|---|---|
 * | 2xx | [Accepted] | done |
 * | 401, 403 | [RetryLater] | the key is unknown or the project is paused. That is fixed on the server, not on the device, so the same body passes later |
 * | 408, 429, 5xx | [RetryLater] | the server is busy or waiting on a store |
 * | any other 4xx (400, 404, 409, 413, 422, …) | [Rejected] | the body will never pass; sending it again on every launch would only add noise |
 *
 * A failed connection is [RetryLater] too. A connector that sends again keeps its own limit on how
 * many times (the attribution), so [RetryLater] never means "forever".
 */
public enum class AppGrowStackAnswer {
    Accepted,
    RetryLater,
    Rejected,
    ;

    public companion object {
        public fun of(status: Int): AppGrowStackAnswer = when (status) {
            in 200..299 -> Accepted
            401, 403, 408, 429 -> RetryLater
            in 400..499 -> Rejected
            else -> RetryLater
        }
    }
}
