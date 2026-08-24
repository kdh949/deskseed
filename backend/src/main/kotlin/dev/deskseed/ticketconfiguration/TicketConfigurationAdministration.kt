package dev.deskseed.ticketconfiguration

import dev.deskseed.foundation.RequestSource
import java.time.Instant
import java.util.UUID

enum class TicketCustomFieldType {
    CHECKBOX,
    SINGLE_SELECT,
    NUMBER,
    SHORT_TEXT,
    LONG_TEXT,
}

data class TicketConfigurationAdminActor(
    val staffId: UUID,
    val displayName: String,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
) {
    init {
        require(source == RequestSource.ADMIN_UI) { "Ticket configuration administration requires ADMIN_UI" }
    }
}

data class TicketFieldValidation(
    val minimum: Double? = null,
    val maximum: Double? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val regex: String? = null,
)

data class TicketFieldDefinitionDraft(
    val machineKey: String,
    val type: TicketCustomFieldType,
    val staffLabel: String,
    val staffDescription: String? = null,
    val customerLabel: String? = null,
    val customerDescription: String? = null,
    val customerVisible: Boolean = false,
    val customerEditable: Boolean = false,
    val agentVisible: Boolean = true,
    val agentEditable: Boolean = true,
    val searchable: Boolean = false,
    val analyticsEligible: Boolean = false,
    val sensitive: Boolean = false,
    val validation: TicketFieldValidation = TicketFieldValidation(),
) {
    init {
        require(MACHINE_KEY.matches(machineKey)) { "machineKey is invalid" }
        requireLabel(staffLabel, "staffLabel", 120)
        requireOptionalText(staffDescription, "staffDescription", 500)
        requireOptionalText(customerLabel, "customerLabel", 120)
        requireOptionalText(customerDescription, "customerDescription", 500)
        require(!customerEditable || customerVisible) { "customerEditable requires customerVisible" }
        require(!agentEditable || agentVisible) { "agentEditable requires agentVisible" }
        require(validation.precision == null || validation.precision in 1..18) { "precision must be 1..18" }
        require(validation.scale == null || validation.scale in 0..12) { "scale must be 0..12" }
        require(validation.precision == null || validation.scale == null || validation.scale <= validation.precision) {
            "scale must not exceed precision"
        }
        require(validation.minLength == null || validation.minLength in 0..10_000) { "minLength is invalid" }
        require(validation.maxLength == null || validation.maxLength in 1..10_000) { "maxLength is invalid" }
        require(validation.minLength == null || validation.maxLength == null || validation.minLength <= validation.maxLength) {
            "minLength must not exceed maxLength"
        }
        require(validation.regex == null || validation.regex.length <= 300 && validation.regex.none(Char::isISOControl)) {
            "regex is invalid"
        }
    }

    companion object {
        private val MACHINE_KEY = Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")

        internal fun requireLabel(value: String, field: String, max: Int) {
            require(value.trim().length in 1..max && value.none(Char::isISOControl)) { "$field is invalid" }
        }

        internal fun requireOptionalText(value: String?, field: String, max: Int) {
            require(value == null || value.length <= max && value.none(Char::isISOControl)) { "$field is invalid" }
        }
    }
}

data class TicketFieldDefinitionView(
    val id: UUID,
    val machineKey: String,
    val type: TicketCustomFieldType,
    val staffLabel: String,
    val staffDescription: String?,
    val customerLabel: String?,
    val customerDescription: String?,
    val active: Boolean,
    val customerVisible: Boolean,
    val customerEditable: Boolean,
    val agentVisible: Boolean,
    val agentEditable: Boolean,
    val searchable: Boolean,
    val analyticsEligible: Boolean,
    val sensitive: Boolean,
    val validation: TicketFieldValidation,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TicketFieldOptionDraft(
    val machineKey: String,
    val staffLabel: String,
    val customerLabel: String? = null,
    val order: Int,
) {
    init {
        require(OPTION_KEY.matches(machineKey)) { "machineKey is invalid" }
        TicketFieldDefinitionDraft.requireLabel(staffLabel, "staffLabel", 120)
        TicketFieldDefinitionDraft.requireOptionalText(customerLabel, "customerLabel", 120)
        require(order >= 0) { "order must be non-negative" }
    }

    companion object {
        private val OPTION_KEY = Regex("^[a-z][a-z0-9-]*$")
    }
}

data class TicketFieldOptionUpdate(
    val staffLabel: String,
    val customerLabel: String? = null,
    val active: Boolean,
) {
    init {
        TicketFieldDefinitionDraft.requireLabel(staffLabel, "staffLabel", 120)
        TicketFieldDefinitionDraft.requireOptionalText(customerLabel, "customerLabel", 120)
    }
}

data class TicketFieldOptionView(
    val id: UUID,
    val machineKey: String,
    val staffLabel: String,
    val customerLabel: String?,
    val active: Boolean,
    val order: Int,
    val version: Long,
)

interface TicketConfigurationAdministration {
    fun listFieldDefinitions(active: Boolean?): List<TicketFieldDefinitionView>

    fun getFieldDefinition(fieldId: UUID): TicketFieldDefinitionView

    fun createFieldDefinition(draft: TicketFieldDefinitionDraft, actor: TicketConfigurationAdminActor): TicketFieldDefinitionView

    fun updateFieldDefinition(
        fieldId: UUID,
        expectedVersion: Long,
        draft: TicketFieldDefinitionDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldDefinitionView

    fun setFieldDefinitionActivation(
        fieldId: UUID,
        expectedVersion: Long,
        active: Boolean,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldDefinitionView

    fun listFieldOptions(fieldId: UUID): List<TicketFieldOptionView>

    fun createFieldOption(fieldId: UUID, draft: TicketFieldOptionDraft, actor: TicketConfigurationAdminActor): TicketFieldOptionView

    fun updateFieldOption(
        fieldId: UUID,
        optionId: UUID,
        expectedVersion: Long,
        update: TicketFieldOptionUpdate,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldOptionView

    fun reorderFieldOptions(fieldId: UUID, ids: List<UUID>, actor: TicketConfigurationAdminActor): List<TicketFieldOptionView>
}

class TicketConfigurationNotFoundException : RuntimeException()

class TicketConfigurationConflictException(val code: String) : RuntimeException(code)

class TicketConfigurationPreconditionFailedException(val currentVersion: Long) : RuntimeException()

class TicketConfigurationValidationException(val code: String, message: String) : IllegalArgumentException(message)

class TicketConfigurationAuditUnavailableException(cause: Throwable) : RuntimeException(cause)
