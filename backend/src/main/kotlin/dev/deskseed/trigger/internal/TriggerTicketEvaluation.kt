package dev.deskseed.trigger.internal

import dev.deskseed.trigger.*
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketPriority
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

internal data class TriggerTicketFacts(val id: UUID, val version: Long, val status: String, val priority: String, val groupId: UUID?, val assigneeId: UUID?, val kind: TicketKind, val formId: UUID?, val tagIds: Set<UUID>)

/** One small evaluator shared by dry run and worker; no expression language. */
@Component
internal class TriggerTicketEvaluation(private val jdbc: JdbcTemplate) {
    fun load(number: Long, lock: Boolean = false): TriggerTicketFacts? = jdbc.query(
        """select t.id, t.version, t.status, t.priority, t.group_id, t.assignee_id, t.kind,
            (select form_id from ticket_customer_form_bindings binding where binding.ticket_id = t.id) as form_id,
            array(select tag_definition_id from ticket_tag_assignments tag where tag.ticket_id = t.id order by tag_definition_id) as tag_ids
            from tickets t where t.ticket_number = ? ${if (lock) "for update of t" else ""}""",
        { rs, _ -> TriggerTicketFacts(rs.getObject("id", UUID::class.java), rs.getLong("version"), rs.getString("status"), rs.getString("priority"), rs.getObject("group_id", UUID::class.java), rs.getObject("assignee_id", UUID::class.java), TicketKind.valueOf(rs.getString("kind")), rs.getObject("form_id", UUID::class.java), (rs.getArray("tag_ids").array as Array<*>).map { UUID.fromString(it.toString()) }.toSet()) }, number,
    ).singleOrNull()

    fun outcomes(conditions: List<TriggerConditionDefinition>, event: TriggerEventType, ticket: TriggerTicketFacts): List<Boolean> = conditions.map { condition ->
        val matches = when (condition.field) {
            TriggerConditionField.EVENT -> condition.value == event.name || (condition.value == "TICKET_UPDATED" && event == TriggerEventType.CUSTOMER_REPLIED)
            TriggerConditionField.PRIORITY -> condition.value == ticket.priority
            TriggerConditionField.GROUP -> condition.value == ticket.groupId?.toString()
            TriggerConditionField.ASSIGNEE -> condition.value == ticket.assigneeId?.toString()
            TriggerConditionField.TAG -> condition.value?.let(UUID::fromString) in ticket.tagIds
            TriggerConditionField.FORM -> condition.value == ticket.formId?.toString()
        }
        when (condition.operator) {
            TriggerConditionOperator.IS -> matches
            TriggerConditionOperator.IS_NOT -> !matches
            TriggerConditionOperator.PRESENT -> if (condition.field == TriggerConditionField.GROUP) ticket.groupId != null else ticket.assigneeId != null
            TriggerConditionOperator.NOT_PRESENT -> if (condition.field == TriggerConditionField.GROUP) ticket.groupId == null else ticket.assigneeId == null
        }
    }
    fun matched(conditions: List<TriggerConditionDefinition>, outcomes: List<Boolean>): Boolean {
        val all = conditions.indices.filter { conditions[it].group == TriggerConditionGroup.ALL }
        val any = conditions.indices.filter { conditions[it].group == TriggerConditionGroup.ANY }
        return all.all(outcomes::get) && (any.isEmpty() || any.any(outcomes::get))
    }
    fun activeGroup(id: UUID) = jdbc.queryForObject("select exists(select 1 from support_groups where id = ? and status = 'ACTIVE')", Boolean::class.java, id) == true
    fun activeStaff(id: UUID) = jdbc.queryForObject("select exists(select 1 from staff_accounts where id = ? and status = 'ACTIVE')", Boolean::class.java, id) == true
    fun activeMember(groupId: UUID, staffId: UUID) = jdbc.queryForObject("select exists(select 1 from group_memberships membership join staff_accounts staff on staff.id = membership.staff_id join support_groups support on support.id = membership.group_id where membership.group_id = ? and membership.staff_id = ? and membership.status = 'ACTIVE' and staff.status = 'ACTIVE' and support.status = 'ACTIVE')", Boolean::class.java, groupId, staffId) == true
    fun failures(actions: List<TriggerActionDefinition>, ticket: TriggerTicketFacts): List<String> = buildList {
        if (ticket.status == "CLOSED") add("TICKET_CLOSED")
        val group = actions.filterIsInstance<TriggerSetGroupAction>().singleOrNull()?.groupId ?: ticket.groupId
        val assignment = actions.filterIsInstance<TriggerSetAssigneeAction>().singleOrNull()
        val assignee = if (assignment == null) ticket.assigneeId else assignment.assigneeId
        if (group != null && !activeGroup(group)) add("TARGET_GROUP_INACTIVE")
        if (assignee != null && (group == null || !activeMember(group, assignee))) add("TARGET_ASSIGNEE_NOT_ACTIVE_MEMBER")
        if (actions.any { it is TriggerNotifyUnassignedGroupAction } && (group == null || assignee != null)) add("UNASSIGNED_GROUP_REQUIRED")
    }
}

internal fun triggerAction(mapper: ObjectMapper, type: String, json: String): TriggerActionDefinition {
    val node = mapper.readTree(json)
    return when (TriggerActionType.valueOf(type)) {
        TriggerActionType.SET_GROUP -> TriggerSetGroupAction(UUID.fromString(node["groupId"].asText()))
        TriggerActionType.SET_PRIORITY -> TriggerSetPriorityAction(TicketPriority.valueOf(node["priority"].asText()))
        TriggerActionType.SET_ASSIGNEE -> TriggerSetAssigneeAction(node["assigneeId"]?.takeUnless { it.isNull }?.asText()?.let(UUID::fromString))
        TriggerActionType.NOTIFY_UNASSIGNED_GROUP -> TriggerNotifyUnassignedGroupAction()
        TriggerActionType.ENQUEUE_WEBHOOK -> TriggerWebhookAction(node["eventType"].asText())
    }
}
internal fun triggerActionConfiguration(action: TriggerActionDefinition): Map<String, String?> = when (action) {
    is TriggerSetGroupAction -> mapOf("groupId" to action.groupId.toString())
    is TriggerSetPriorityAction -> mapOf("priority" to action.priority.name)
    is TriggerSetAssigneeAction -> mapOf("assigneeId" to action.assigneeId?.toString())
    is TriggerNotifyUnassignedGroupAction -> emptyMap()
    is TriggerWebhookAction -> mapOf("eventType" to action.eventType)
}
