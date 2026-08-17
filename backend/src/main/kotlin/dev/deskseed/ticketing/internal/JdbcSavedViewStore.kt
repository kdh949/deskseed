package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.SavedTicketView
import dev.deskseed.ticketing.SavedViewConflictException
import dev.deskseed.ticketing.SavedViewPreconditionFailedException
import dev.deskseed.ticketing.SavedViewDefinition
import dev.deskseed.ticketing.SavedViewDefinitionRules
import dev.deskseed.ticketing.SavedViewOrder
import dev.deskseed.ticketing.SavedViewScope
import dev.deskseed.ticketing.SavedViewStore
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class JdbcSavedViewStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : SavedViewStore {
    override fun listVisible(actorId: UUID): List<SavedTicketView> = jdbcTemplate.query(
        """
        select ${viewColumns("view")}
        from saved_ticket_views view
        left join saved_view_order_states state
          on state.scope = view.scope
         and state.owner_scope_key = case
             when view.scope = 'PERSONAL' then view.owner_staff_id
             when view.scope = 'SHARED' then :sharedOwnerScopeKey
             else null
         end
        where view.active
          and (view.scope in ('SYSTEM', 'SHARED') or view.owner_staff_id = :actorId)
        order by
            case view.scope when 'SYSTEM' then 0 when 'PERSONAL' then 1 else 2 end,
            view.sort_position,
            view.view_key
        """.trimIndent(),
        mapOf("actorId" to actorId, "sharedOwnerScopeKey" to SHARED_OWNER_SCOPE_KEY),
        ::mapView,
    )

    override fun findByKey(key: String): SavedTicketView? = jdbcTemplate.query(
        """
        select ${viewColumns("view")}
        from saved_ticket_views view
        left join saved_view_order_states state
          on state.scope = view.scope
         and state.owner_scope_key = case
             when view.scope = 'PERSONAL' then view.owner_staff_id
             when view.scope = 'SHARED' then :sharedOwnerScopeKey
             else null
         end
        where view.view_key = :key and view.active
        """.trimIndent(),
        mapOf("key" to key, "sharedOwnerScopeKey" to SHARED_OWNER_SCOPE_KEY),
        ::mapView,
    ).singleOrNull()

    override fun create(
        key: String,
        scope: SavedViewScope,
        ownerStaffId: UUID?,
        definition: SavedViewDefinition,
        createdAt: Instant,
    ): SavedTicketView {
        require(scope != SavedViewScope.SYSTEM) { "System saved views cannot be created interactively" }
        val ownerScopeKey = ownerScopeKey(scope, ownerStaffId)
        val currentOrderVersion = lockOrderState(scope, ownerScopeKey, createdAt)
        val position = jdbcTemplate.queryForObject(
            """
            select coalesce(max(sort_position), 0) + 1
            from saved_ticket_views
            where active and scope = :scope
              and ((:scope = 'PERSONAL' and owner_staff_id = :ownerStaffId)
                or (:scope = 'SHARED' and owner_staff_id is null))
            """.trimIndent(),
            mapOf("scope" to scope.name, "ownerStaffId" to ownerStaffId),
            Int::class.java,
        ) ?: 1
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into saved_ticket_views (
                id, view_key, scope, owner_staff_id, name, description, category, conditions_json, columns_json,
                sort, sort_position, active, definition_version, created_at, updated_at
            ) values (
                :id, :key, :scope, :ownerStaffId, :name, :description, :category, cast(:conditionsJson as jsonb),
                cast(:columnsJson as jsonb), :sort, :position, true, 1, :createdAt, :createdAt
            )
            """.trimIndent(),
            mapOf(
                "id" to id,
                "key" to key,
                "scope" to scope.name,
                "ownerStaffId" to ownerStaffId,
                "name" to definition.name.trim(),
                "description" to definition.description.trim(),
                "category" to category(scope),
                "conditionsJson" to objectMapper.writeValueAsString(definition.conditions),
                "columnsJson" to objectMapper.writeValueAsString(definition.columns),
                "sort" to definition.sort,
                "position" to position,
                "createdAt" to Timestamp.from(createdAt),
            ),
        )
        val orderVersion = advanceOrderState(scope, ownerScopeKey, currentOrderVersion, createdAt)
        return SavedTicketView(
            id = id,
            key = key,
            scope = scope,
            ownerStaffId = ownerStaffId,
            definition = definition.copy(name = definition.name.trim(), description = definition.description.trim()),
            active = true,
            definitionVersion = 1,
            orderVersion = orderVersion,
            categoryPath = listOf(category(scope)),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    override fun update(
        id: UUID,
        expectedVersion: Long,
        definition: SavedViewDefinition,
        updatedAt: Instant,
    ): SavedTicketView {
        val updated = jdbcTemplate.update(
            """
            update saved_ticket_views
            set name = :name,
                description = :description,
                conditions_json = cast(:conditionsJson as jsonb),
                columns_json = cast(:columnsJson as jsonb),
                sort = :sort,
                definition_version = definition_version + 1,
                updated_at = :updatedAt
            where id = :id and active and definition_version = :expectedVersion
            """.trimIndent(),
            mapOf(
                "id" to id,
                "expectedVersion" to expectedVersion,
                "name" to definition.name.trim(),
                "description" to definition.description.trim(),
                "conditionsJson" to objectMapper.writeValueAsString(definition.conditions),
                "columnsJson" to objectMapper.writeValueAsString(definition.columns),
                "sort" to definition.sort,
                "updatedAt" to Timestamp.from(updatedAt),
            ),
        )
        if (updated != 1) throw SavedViewConflictException()
        return checkNotNull(findById(id))
    }

    override fun delete(id: UUID, expectedVersion: Long, updatedAt: Instant): Boolean {
        // All mutable-order operations take the order-state lock before a view row.  In
        // particular, do not reverse this order here: reorder() first locks the state
        // and then locks its view rows, so reversing it would create a cross-path
        // deadlock under concurrent delete/reorder requests.
        val initial = findById(id) ?: return false
        if (initial.scope == SavedViewScope.SYSTEM) return false
        val ownerScopeKey = ownerScopeKey(initial.scope, initial.ownerStaffId)
        val currentOrderVersion = lockOrderState(initial.scope, ownerScopeKey, updatedAt)
        val view = findByIdForUpdate(id) ?: return false
        if (view.definitionVersion != expectedVersion) throw SavedViewPreconditionFailedException()
        if (view.scope == SavedViewScope.SYSTEM) return false
        check(view.scope == initial.scope && view.ownerStaffId == initial.ownerStaffId) {
            "Saved view ownership changed while deleting"
        }
        val deleted = jdbcTemplate.update(
            "delete from saved_ticket_views where id = :id and definition_version = :expectedVersion",
            mapOf("id" to id, "expectedVersion" to expectedVersion),
        ) == 1
        if (deleted) {
            advanceOrderState(view.scope, ownerScopeKey, currentOrderVersion, updatedAt)
        }
        return deleted
    }

    override fun reorder(
        scope: SavedViewScope,
        ownerStaffId: UUID?,
        expectedOrderVersion: Long,
        viewKeys: List<String>,
        updatedAt: Instant,
    ): SavedViewOrder {
        require(scope != SavedViewScope.SYSTEM) { "System saved views cannot be reordered" }
        val ownerScopeKey = ownerScopeKey(scope, ownerStaffId)
        val currentVersion = lockOrderState(scope, ownerScopeKey, updatedAt)
        if (currentVersion != expectedOrderVersion) throw SavedViewConflictException()
        val rows = jdbcTemplate.query(
            """
            select id, view_key
            from saved_ticket_views
            where active and scope = :scope
              and ((:scope = 'PERSONAL' and owner_staff_id = :ownerStaffId)
                or (:scope = 'SHARED' and owner_staff_id is null))
            order by sort_position, view_key
            for update
            """.trimIndent(),
            mapOf("scope" to scope.name, "ownerStaffId" to ownerStaffId),
        ) { result, _ -> result.getObject("id", UUID::class.java) to result.getString("view_key") }
        val actualKeys = rows.map { it.second }
        if (viewKeys.distinct().size != viewKeys.size || actualKeys.toSet() != viewKeys.toSet()) {
            throw IllegalArgumentException("Saved view reorder keys must exactly match the mutable visible views")
        }
        val idsByKey = rows.associate { it.second to it.first }
        viewKeys.forEachIndexed { index, key ->
            jdbcTemplate.update(
                "update saved_ticket_views set sort_position = :position, updated_at = :updatedAt where id = :id",
                mapOf("position" to index + 1, "updatedAt" to Timestamp.from(updatedAt), "id" to idsByKey.getValue(key)),
            )
        }
        val nextVersion = advanceOrderState(scope, ownerScopeKey, currentVersion, updatedAt)
        return SavedViewOrder(scope, nextVersion, viewKeys)
    }

    private fun findById(id: UUID): SavedTicketView? = jdbcTemplate.query(
        """
        select ${viewColumns("view")}
        from saved_ticket_views view
        left join saved_view_order_states state
          on state.scope = view.scope
         and state.owner_scope_key = case
             when view.scope = 'PERSONAL' then view.owner_staff_id
             when view.scope = 'SHARED' then :sharedOwnerScopeKey
             else null
         end
        where view.id = :id and view.active
        """.trimIndent(),
        mapOf("id" to id, "sharedOwnerScopeKey" to SHARED_OWNER_SCOPE_KEY),
        ::mapView,
    ).singleOrNull()

    private fun findByIdForUpdate(id: UUID): SavedTicketView? = jdbcTemplate.query(
        """
        select ${viewColumns("view")}
        from saved_ticket_views view
        left join saved_view_order_states state
          on state.scope = view.scope
         and state.owner_scope_key = case
             when view.scope = 'PERSONAL' then view.owner_staff_id
             when view.scope = 'SHARED' then :sharedOwnerScopeKey
             else null
         end
        where view.id = :id and view.active
        for update of view
        """.trimIndent(),
        mapOf("id" to id, "sharedOwnerScopeKey" to SHARED_OWNER_SCOPE_KEY),
        ::mapView,
    ).singleOrNull()

    private fun lockOrderState(scope: SavedViewScope, ownerScopeKey: UUID, updatedAt: Instant): Long {
        jdbcTemplate.update(
            """
            insert into saved_view_order_states (scope, owner_scope_key, order_version, updated_at)
            values (:scope, :ownerScopeKey, 1, :updatedAt)
            on conflict (scope, owner_scope_key) do nothing
            """.trimIndent(),
            mapOf("scope" to scope.name, "ownerScopeKey" to ownerScopeKey, "updatedAt" to Timestamp.from(updatedAt)),
        )
        return jdbcTemplate.queryForObject(
            """
            select order_version
            from saved_view_order_states
            where scope = :scope and owner_scope_key = :ownerScopeKey
            for update
            """.trimIndent(),
            mapOf("scope" to scope.name, "ownerScopeKey" to ownerScopeKey),
            Long::class.java,
        )!!
    }

    private fun advanceOrderState(
        scope: SavedViewScope,
        ownerScopeKey: UUID,
        currentVersion: Long,
        updatedAt: Instant,
    ): Long {
        val nextVersion = currentVersion + 1
        val updated = jdbcTemplate.update(
            """
            update saved_view_order_states
            set order_version = :nextVersion, updated_at = :updatedAt
            where scope = :scope
              and owner_scope_key = :ownerScopeKey
              and order_version = :currentVersion
            """.trimIndent(),
            mapOf(
                "scope" to scope.name,
                "ownerScopeKey" to ownerScopeKey,
                "currentVersion" to currentVersion,
                "nextVersion" to nextVersion,
                "updatedAt" to Timestamp.from(updatedAt),
            ),
        )
        check(updated == 1) { "Saved view order state changed without its lock" }
        return nextVersion
    }

    private fun mapView(result: ResultSet, rowIndex: Int): SavedTicketView {
        val scope = SavedViewScope.valueOf(result.getString("scope"))
        val conditions = objectMapper.readValue(result.getString("conditions_json"), dev.deskseed.ticketing.SavedViewConditions::class.java)
        val columns = objectMapper.readValue(result.getString("columns_json"), Array<dev.deskseed.ticketing.SavedViewColumn>::class.java).toList()
        val definition = SavedViewDefinition(
            name = result.getString("name"),
            description = result.getString("description"),
            conditions = conditions,
            columns = columns,
            sort = result.getString("sort"),
        )
        SavedViewDefinitionRules.validate(definition)
        return SavedTicketView(
            id = result.getObject("id", UUID::class.java),
            key = result.getString("view_key"),
            scope = scope,
            ownerStaffId = result.getObject("owner_staff_id", UUID::class.java),
            definition = definition,
            active = result.getBoolean("active"),
            definitionVersion = result.getLong("definition_version"),
            orderVersion = result.getLong("order_version"),
            categoryPath = listOf(result.getString("category")),
            createdAt = result.getTimestamp("created_at").toInstant(),
            updatedAt = result.getTimestamp("updated_at").toInstant(),
        )
    }

    private fun viewColumns(alias: String): String = """
        $alias.id, $alias.view_key, $alias.scope, $alias.owner_staff_id, $alias.name, $alias.description, $alias.category,
        $alias.conditions_json::text as conditions_json, $alias.columns_json::text as columns_json,
        $alias.sort, $alias.active, $alias.definition_version, $alias.created_at, $alias.updated_at,
        coalesce(state.order_version, 1) as order_version
    """.trimIndent()

    private fun ownerScopeKey(scope: SavedViewScope, ownerStaffId: UUID?): UUID = when (scope) {
        SavedViewScope.PERSONAL -> requireNotNull(ownerStaffId) { "Personal view owner is required" }
        SavedViewScope.SHARED -> SHARED_OWNER_SCOPE_KEY
        SavedViewScope.SYSTEM -> throw IllegalArgumentException("System views have no mutable order state")
    }

    private fun category(scope: SavedViewScope): String = when (scope) {
        SavedViewScope.PERSONAL -> "내 작업"
        SavedViewScope.SHARED -> "공유"
        SavedViewScope.SYSTEM -> "시스템"
    }

    private companion object {
        val SHARED_OWNER_SCOPE_KEY: UUID = UUID(0, 0)
    }
}
