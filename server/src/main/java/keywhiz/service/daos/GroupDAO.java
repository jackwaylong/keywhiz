/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package keywhiz.service.daos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import keywhiz.api.model.Group;
import keywhiz.api.model.SecretSeries;
import keywhiz.jooq.tables.records.GroupsRecord;
import keywhiz.service.config.Readonly;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static keywhiz.jooq.tables.Accessgrants.ACCESSGRANTS;
import static keywhiz.jooq.tables.Groups.GROUPS;
import static keywhiz.jooq.tables.Memberships.MEMBERSHIPS;
import static keywhiz.jooq.tables.Secrets.SECRETS;

public class GroupDAO {
  private final DSLContext dslContext;
  private final GroupMapper groupMapper;
  private final ObjectMapper mapper;

  private GroupDAO(DSLContext dslContext, GroupMapper groupMapper, ObjectMapper mapper) {
    this.dslContext = dslContext;
    this.groupMapper = groupMapper;
    this.mapper = mapper;
  }

  public long createGroup(String name, String creator, String description, ImmutableMap<String, String> metadata) {
    GroupsRecord r = dslContext.newRecord(GROUPS);

    String jsonMetadata;
    try {
      jsonMetadata = mapper.writeValueAsString(metadata);
    } catch (JsonProcessingException e) {
      // Serialization of a Map<String, String> can never fail.
      throw Throwables.propagate(e);
    }

    long now = OffsetDateTime.now().toEpochSecond();

    r.setName(name);
    r.setCreatedby(creator);
    r.setCreatedat(now);
    r.setUpdatedby(creator);
    r.setUpdatedat(now);
    r.setDescription(description);
    r.setMetadata(jsonMetadata);
    r.store();

    return r.getId();
  }

  public void deleteGroup(Group group) {
    dslContext.transaction(configuration -> {
      DSL.using(configuration)
              .delete(GROUPS)
              .where(GROUPS.ID.eq(group.getId()))
              .execute();
      DSL.using(configuration)
              .delete(MEMBERSHIPS)
              .where(MEMBERSHIPS.GROUPID.eq(group.getId()))
              .execute();
      DSL.using(configuration)
              .delete(ACCESSGRANTS)
              .where(ACCESSGRANTS.GROUPID.eq(group.getId()))
              .execute();
    });
  }

  public Optional<Group> getGroup(String name) {
    GroupsRecord r = dslContext.fetchOne(GROUPS, GROUPS.NAME.eq(name));
    return Optional.ofNullable(r).map(groupMapper::map);
  }

  public Optional<Group> getGroupById(long id) {
    GroupsRecord r = dslContext.fetchOne(GROUPS, GROUPS.ID.eq(id));
    return Optional.ofNullable(r).map(groupMapper::map);
  }

  public Map<Long, List<Group>> getGroupsForSecrets(Set<Long> secretIdList) {
    List<Group> groups = dslContext.select().from(GROUPS)
        .join(ACCESSGRANTS).on(ACCESSGRANTS.GROUPID.eq(GROUPS.ID))
        .join(SECRETS).on(ACCESSGRANTS.SECRETID.eq(SECRETS.ID))
        .where(SECRETS.ID.in(secretIdList))
        .fetchInto(GROUPS).map(groupMapper);

    Map<Long, Group> groupMap = groups.stream().collect(Collectors.toMap(Group::getId, g -> g));

    Map<Long, List<Long>> secretsIdGroupsIdMap = dslContext.select().from(GROUPS)
        .join(ACCESSGRANTS).on(ACCESSGRANTS.GROUPID.eq(GROUPS.ID))
        .join(SECRETS).on(ACCESSGRANTS.SECRETID.eq(SECRETS.ID))
        .where(SECRETS.ID.in(secretIdList))
        .fetch().intoGroups(SECRETS.ID, GROUPS.ID);

    ImmutableMap.Builder<Long, List<Group>> builder = ImmutableMap.builder();
    for (Map.Entry<Long, List<Long>> entry : secretsIdGroupsIdMap.entrySet()) {
      List<Group> groupList = entry.getValue().stream().map(groupMap::get).collect(toList());
      builder.put(entry.getKey(), groupList);
    }
    return builder.build();
  }

  public ImmutableSet<Group> getGroups() {
    List<Group> r = dslContext.selectFrom(GROUPS).fetch().map(groupMapper);
    return ImmutableSet.copyOf(r);
  }

  public static class GroupDAOFactory implements DAOFactory<GroupDAO> {
    private final DSLContext jooq;
    private final DSLContext readonlyJooq;
    private final GroupMapper groupMapper;
    private final ObjectMapper mapper;

    @Inject public GroupDAOFactory(DSLContext jooq, @Readonly DSLContext readonlyJooq,
        GroupMapper groupMapper, ObjectMapper mapper) {
      this.jooq = jooq;
      this.readonlyJooq = readonlyJooq;
      this.groupMapper = groupMapper;
      this.mapper = mapper;
    }

    @Override public GroupDAO readwrite() {
      return new GroupDAO(jooq, groupMapper, mapper);
    }

    @Override public GroupDAO readonly() {
      return new GroupDAO(readonlyJooq, groupMapper, mapper);
    }

    @Override public GroupDAO using(Configuration configuration) {
      DSLContext dslContext = DSL.using(checkNotNull(configuration));
      return new GroupDAO(dslContext, groupMapper, mapper);
    }
  }
}
