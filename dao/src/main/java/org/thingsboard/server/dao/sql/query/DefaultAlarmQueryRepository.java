/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.sql.query;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.alarm.AlarmSearchStatus;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.alarm.AlarmStatus;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.AlarmData;
import org.thingsboard.server.common.data.query.AlarmDataPageLink;
import org.thingsboard.server.common.data.query.AlarmDataQuery;
import org.thingsboard.server.common.data.query.EntityDataSortOrder;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@SqlDao
@Repository
@Slf4j
public class DefaultAlarmQueryRepository implements AlarmQueryRepository {

    private static final Map<String, String> alarmFieldColumnMap = new HashMap<>();

    static {
        alarmFieldColumnMap.put("createdTime", ModelConstants.CREATED_TIME_PROPERTY);
        alarmFieldColumnMap.put("ackTs", ModelConstants.ALARM_ACK_TS_PROPERTY);
        alarmFieldColumnMap.put("ackTime", ModelConstants.ALARM_ACK_TS_PROPERTY);
        alarmFieldColumnMap.put("clearTs", ModelConstants.ALARM_CLEAR_TS_PROPERTY);
        alarmFieldColumnMap.put("clearTime", ModelConstants.ALARM_CLEAR_TS_PROPERTY);
        alarmFieldColumnMap.put("details", ModelConstants.ADDITIONAL_INFO_PROPERTY);
        alarmFieldColumnMap.put("endTs", ModelConstants.ALARM_END_TS_PROPERTY);
        alarmFieldColumnMap.put("endTime", ModelConstants.ALARM_END_TS_PROPERTY);
        alarmFieldColumnMap.put("startTs", ModelConstants.ALARM_START_TS_PROPERTY);
        alarmFieldColumnMap.put("startTime", ModelConstants.ALARM_START_TS_PROPERTY);
        alarmFieldColumnMap.put("status", ModelConstants.ALARM_STATUS_PROPERTY);
        alarmFieldColumnMap.put("type", ModelConstants.ALARM_TYPE_PROPERTY);
        alarmFieldColumnMap.put("severity", ModelConstants.ALARM_SEVERITY_PROPERTY);
        alarmFieldColumnMap.put("originator_id", ModelConstants.ALARM_ORIGINATOR_ID_PROPERTY);
        alarmFieldColumnMap.put("originator_type", ModelConstants.ALARM_ORIGINATOR_TYPE_PROPERTY);
        alarmFieldColumnMap.put("originator", "originator_name");
    }

    private static final String SELECT_ORIGINATOR_NAME = " CASE" +
            " WHEN a.originator_type = " + EntityType.TENANT.ordinal() +
            " THEN (select title from tenant where id = a.originator_id)" +
            " WHEN a.originator_type = " + EntityType.CUSTOMER.ordinal() +
            " THEN (select title from customer where id = a.originator_id)" +
            " WHEN a.originator_type = " + EntityType.USER.ordinal() +
            " THEN (select email from tb_user where id = a.originator_id)" +
            " WHEN a.originator_type = " + EntityType.DASHBOARD.ordinal() +
            " THEN (select title from dashboard where id = a.originator_id)" +
            " WHEN a.originator_type = " + EntityType.ASSET.ordinal() +
            " THEN (select name from asset where id = a.originator_id)" +
            " WHEN a.originator_type = " + EntityType.DEVICE.ordinal() +
            " THEN (select name from device where id = a.originator_id)" +
            " WHEN a.originator_type = " + EntityType.ENTITY_VIEW.ordinal() +
            " THEN (select name from entity_view where id = a.originator_id)" +
            " END as originator_name";

    private static final String FIELDS_SELECTION = "select a.id as id," +
            " a.created_time as created_time," +
            " a.ack_ts as ack_ts," +
            " a.clear_ts as clear_ts," +
            " a.additional_info as additional_info," +
            " a.end_ts as end_ts," +
            " a.originator_id as originator_id," +
            " a.originator_type as originator_type," +
            " a.propagate as propagate," +
            " a.severity as severity," +
            " a.start_ts as start_ts," +
            " a.status as status, " +
            " a.tenant_id as tenant_id, " +
            " a.propagate_relation_types as propagate_relation_types, " +
            " a.type as type," + SELECT_ORIGINATOR_NAME + ", ";

    private static final String JOIN_RELATIONS = "left join relation r on r.relation_type_group = 'ALARM' and r.relation_type = 'ANY' and a.id = r.to_id and r.from_id in (:entity_ids)";

    protected final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DefaultAlarmQueryRepository(NamedParameterJdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public PageData<AlarmData> findAlarmDataByQueryForEntities(TenantId tenantId, CustomerId customerId,
                                                               AlarmDataQuery query, Collection<EntityId> orderedEntityIds) {
        return transactionTemplate.execute(status -> {
            AlarmDataPageLink pageLink = query.getPageLink();
            QueryContext ctx = new QueryContext(new QuerySecurityContext(tenantId, customerId, EntityType.ALARM));
            ctx.addUuidListParameter("entity_ids", orderedEntityIds.stream().map(EntityId::getId).collect(Collectors.toList()));

            StringBuilder selectPart = new StringBuilder(FIELDS_SELECTION);
            StringBuilder fromPart = new StringBuilder(" from alarm a ");
            StringBuilder wherePart = new StringBuilder(" where ");
            StringBuilder sortPart = new StringBuilder(" order by ");
            boolean addAnd = false;
            if (pageLink.isSearchPropagatedAlarms()) {
                selectPart.append(" CASE WHEN r.from_id IS NULL THEN a.originator_id ELSE r.from_id END as entity_id ");
                fromPart.append(JOIN_RELATIONS);
                wherePart.append(buildPermissionsQuery(tenantId, customerId, ctx));
                addAnd = true;
            } else {
                selectPart.append(" a.originator_id as entity_id ");
            }
            EntityDataSortOrder sortOrder = pageLink.getSortOrder();
            if (sortOrder != null && sortOrder.getKey().getType().equals(EntityKeyType.ALARM_FIELD)) {
                String sortOrderKey = sortOrder.getKey().getKey();
                sortPart.append(alarmFieldColumnMap.getOrDefault(sortOrderKey, sortOrderKey))
                        .append(" ").append(sortOrder.getDirection().name());
                if (pageLink.isSearchPropagatedAlarms()) {
                    wherePart.append(" and (a.originator_id in (:entity_ids) or r.from_id IS NOT NULL)");
                } else {
                    addAndIfNeeded(wherePart, addAnd);
                    addAnd = true;
                    wherePart.append(" a.originator_id in (:entity_ids)");
                }
            } else {
                fromPart.append(" left join (select * from (VALUES");
                int entityIdIdx = 0;
                int lastEntityIdIdx = orderedEntityIds.size() - 1;
                for (EntityId entityId : orderedEntityIds) {
                    fromPart.append("(uuid('").append(entityId.getId().toString()).append("'), ").append(entityIdIdx).append(")");
                    if (entityIdIdx != lastEntityIdIdx) {
                        fromPart.append(",");
                    } else {
                        fromPart.append(")");
                    }
                    entityIdIdx++;
                }
                fromPart.append(" as e(id, priority)) e ");
                if (pageLink.isSearchPropagatedAlarms()) {
                    fromPart.append("on (r.from_id IS NULL and a.originator_id = e.id) or (r.from_id IS NOT NULL and r.from_id = e.id)");
                } else {
                    fromPart.append("on a.originator_id = e.id");
                }
                sortPart.append("e.priority");
            }

            long startTs;
            long endTs;
            if (pageLink.getTimeWindow() > 0) {
                endTs = System.currentTimeMillis();
                startTs = endTs - pageLink.getTimeWindow();
            } else {
                startTs = pageLink.getStartTs();
                endTs = pageLink.getEndTs();
            }

            if (startTs > 0) {
                addAndIfNeeded(wherePart, addAnd);
                addAnd = true;
                ctx.addLongParameter("startTime", startTs);
                wherePart.append("a.created_time >= :startTime");
            }

            if (endTs > 0) {
                addAndIfNeeded(wherePart, addAnd);
                addAnd = true;
                ctx.addLongParameter("endTime", endTs);
                wherePart.append("a.created_time <= :endTime");
            }

            if (pageLink.getTypeList() != null && !pageLink.getTypeList().isEmpty()) {
                addAndIfNeeded(wherePart, addAnd);
                addAnd = true;
                ctx.addStringListParameter("alarmTypes", pageLink.getTypeList());
                wherePart.append("a.type in (:alarmTypes)");
            }

            if (pageLink.getSeverityList() != null && !pageLink.getSeverityList().isEmpty()) {
                addAndIfNeeded(wherePart, addAnd);
                addAnd = true;
                ctx.addStringListParameter("alarmSeverities", pageLink.getSeverityList().stream().map(AlarmSeverity::name).collect(Collectors.toList()));
                wherePart.append("a.severity in (:alarmSeverities)");
            }

            if (pageLink.getStatusList() != null && !pageLink.getStatusList().isEmpty()) {
                Set<AlarmStatus> statusSet = toStatusSet(pageLink.getStatusList());
                if (!statusSet.isEmpty()) {
                    addAndIfNeeded(wherePart, addAnd);
                    addAnd = true;
                    ctx.addStringListParameter("alarmStatuses", statusSet.stream().map(AlarmStatus::name).collect(Collectors.toList()));
                    wherePart.append(" a.status in (:alarmStatuses)");
                }
            }

            String textSearchQuery = buildTextSearchQuery(ctx, query.getAlarmFields(), pageLink.getTextSearch());
            String mainQuery = selectPart.toString() + fromPart.toString() + wherePart.toString();
            if (!textSearchQuery.isEmpty()) {
                mainQuery = String.format("select * from (%s) a WHERE %s", mainQuery, textSearchQuery);
            }
            String countQuery = mainQuery;
            int totalElements = jdbcTemplate.queryForObject(String.format("select count(*) from (%s) result", countQuery), ctx, Integer.class);

            String dataQuery = mainQuery + sortPart;

            int startIndex = pageLink.getPageSize() * pageLink.getPage();
            if (pageLink.getPageSize() > 0) {
                dataQuery = String.format("%s limit %s offset %s", dataQuery, pageLink.getPageSize(), startIndex);
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataQuery, ctx);
            return AlarmDataAdapter.createAlarmData(pageLink, rows, totalElements, orderedEntityIds);
        });
    }

    private String buildTextSearchQuery(QueryContext ctx, List<EntityKey> selectionMapping, String searchText) {
        if (!StringUtils.isEmpty(searchText) && selectionMapping != null && !selectionMapping.isEmpty()) {
            String lowerSearchText = searchText.toLowerCase() + "%";
            List<String> searchPredicates = selectionMapping.stream()
                    .map(mapping -> alarmFieldColumnMap.get(mapping.getKey()))
                    .filter(Objects::nonNull)
                    .map(mapping -> {
                                String paramName = mapping + "_lowerSearchText";
                                ctx.addStringParameter(paramName, lowerSearchText);
                                return String.format("LOWER(cast(%s as varchar)) LIKE concat('%%', :%s, '%%')", mapping, paramName);
                            }
                    ).collect(Collectors.toList());
            return String.format("%s", String.join(" or ", searchPredicates));
        } else {
            return "";
        }
    }

    private String buildPermissionsQuery(TenantId tenantId, CustomerId customerId, QueryContext ctx) {
        StringBuilder permissionsQuery = new StringBuilder();
        ctx.addUuidParameter("permissions_tenant_id", tenantId.getId());
        permissionsQuery.append(" a.tenant_id = :permissions_tenant_id ");
        if (customerId != null && !customerId.isNullUid()) {
            ctx.addUuidParameter("permissions_customer_id", customerId.getId());
            ctx.addUuidParameter("permissions_device_customer_id", customerId.getId());
            ctx.addUuidParameter("permissions_asset_customer_id", customerId.getId());
            ctx.addUuidParameter("permissions_user_customer_id", customerId.getId());
            ctx.addUuidParameter("permissions_entity_view_customer_id", customerId.getId());
            permissionsQuery.append(" and (");
            permissionsQuery.append("(a.originator_type = '").append(EntityType.DEVICE.ordinal()).append("' and exists (select 1 from device cd where cd.id = a.originator_id and cd.customer_id = :permissions_device_customer_id))");
            permissionsQuery.append(" or ");
            permissionsQuery.append("(a.originator_type = '").append(EntityType.ASSET.ordinal()).append("' and exists (select 1 from asset ca where ca.id = a.originator_id and ca.customer_id = :permissions_device_customer_id))");
            permissionsQuery.append(" or ");
            permissionsQuery.append("(a.originator_type = '").append(EntityType.CUSTOMER.ordinal()).append("' and exists (select 1 from customer cc where cc.id = a.originator_id and cc.id = :permissions_customer_id))");
            permissionsQuery.append(" or ");
            permissionsQuery.append("(a.originator_type = '").append(EntityType.USER.ordinal()).append("' and exists (select 1 from tb_user cu where cu.id = a.originator_id and cu.customer_id = :permissions_user_customer_id))");
            permissionsQuery.append(" or ");
            permissionsQuery.append("(a.originator_type = '").append(EntityType.ENTITY_VIEW.ordinal()).append("' and exists (select 1 from entity_view cv where cv.id = a.originator_id and cv.customer_id = :permissions_entity_view_customer_id))");
            permissionsQuery.append(")");
        }
        return permissionsQuery.toString();
    }

    private Set<AlarmStatus> toStatusSet(List<AlarmSearchStatus> statusList) {
        Set<AlarmStatus> result = new HashSet<>();
        for (AlarmSearchStatus searchStatus : statusList) {
            switch (searchStatus) {
                case ACK:
                    result.add(AlarmStatus.ACTIVE_ACK);
                    result.add(AlarmStatus.CLEARED_ACK);
                    break;
                case UNACK:
                    result.add(AlarmStatus.ACTIVE_UNACK);
                    result.add(AlarmStatus.CLEARED_UNACK);
                    break;
                case CLEARED:
                    result.add(AlarmStatus.CLEARED_ACK);
                    result.add(AlarmStatus.CLEARED_UNACK);
                    break;
                case ACTIVE:
                    result.add(AlarmStatus.ACTIVE_ACK);
                    result.add(AlarmStatus.ACTIVE_UNACK);
                    break;
                default:
                    break;
            }
            if (searchStatus == AlarmSearchStatus.ANY || result.size() == AlarmStatus.values().length) {
                result.clear();
                return result;
            }
        }
        return result;
    }

    private void addAndIfNeeded(StringBuilder wherePart, boolean addAnd) {
        if (addAnd) {
            wherePart.append(" and ");
        }
    }
}
