package dev.deskseed.ticketing

import java.time.Instant
import java.util.UUID

/** First-party intake values; no staff fields, priority, or internal ticket kind are accepted. */
data class CustomerRequestFormValues(
    val formId: UUID? = null,
    val formVersion: Int? = null,
    val fieldValues: Map<String, TicketConfigurationFieldValue> = emptyMap(),
) {
    override fun toString(): String = "[PROTECTED CUSTOMER FORM VALUES]"
}

interface CustomerRequestFormBinding {
    /** Historical normalization is used only to compare an already committed receipt. */
    fun normalize(input: CustomerRequestFormValues, requireCurrent: Boolean = true): CustomerRequestFormValues
    fun bind(ticketId: UUID, input: CustomerRequestFormValues, occurredAt: Instant): List<TicketConfigurationAuditChange>
}

class CustomerFormValidationException : RuntimeException()
class CustomerFormUnavailableException : RuntimeException()
class CustomerFormVersionConflictException : RuntimeException()
class CustomerRequestConfigurationConflictException : RuntimeException()
