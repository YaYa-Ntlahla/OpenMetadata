/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.jdbi3;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toUnmodifiableList;
import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.Entity.DATABASE_SCHEMA;
import static org.openmetadata.service.Entity.FIELD_DESCRIPTION;
import static org.openmetadata.service.Entity.FIELD_DISPLAY_NAME;
import static org.openmetadata.service.Entity.FIELD_FOLLOWERS;
import static org.openmetadata.service.Entity.FIELD_OWNER;
import static org.openmetadata.service.Entity.FIELD_TAGS;
import static org.openmetadata.service.Entity.LOCATION;
import static org.openmetadata.service.Entity.TABLE;
import static org.openmetadata.service.util.EntityUtil.getColumnField;
import static org.openmetadata.service.util.LambdaExceptionUtil.ignoringComparator;
import static org.openmetadata.service.util.LambdaExceptionUtil.rethrowFunction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.api.data.CreateTableProfile;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.tests.CustomMetric;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnJoin;
import org.openmetadata.schema.type.ColumnProfile;
import org.openmetadata.schema.type.ColumnProfilerConfig;
import org.openmetadata.schema.type.DailyCount;
import org.openmetadata.schema.type.DataModel;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.JoinedWith;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.type.SQLQuery;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.schema.type.TableData;
import org.openmetadata.schema.type.TableJoins;
import org.openmetadata.schema.type.TableProfile;
import org.openmetadata.schema.type.TableProfilerConfig;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.resources.databases.DatabaseUtil;
import org.openmetadata.service.resources.databases.TableResource;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;
import org.openmetadata.service.util.RestUtil;
import org.openmetadata.service.util.ResultList;

@Slf4j
public class TableRepository extends EntityRepository<Table> {

  // Table fields that can be patched in a PATCH request
  static final String TABLE_PATCH_FIELDS = "owner,tags,tableConstraints,tablePartition,extension";
  // Table fields that can be updated in a PUT request
  static final String TABLE_UPDATE_FIELDS = "owner,tags,tableConstraints,tablePartition,dataModel,extension";

  public static final String FIELD_RELATION_COLUMN_TYPE = "table.columns.column";
  public static final String FIELD_RELATION_TABLE_TYPE = "table";
  public static final String TABLE_PROFILE_EXTENSION = "table.tableProfile";
  public static final String TABLE_COLUMN_PROFILE_EXTENSION = "table.columnProfile";

  public static final String TABLE_SAMPLE_DATA_EXTENSION = "table.sampleData";
  public static final String TABLE_PROFILER_CONFIG_EXTENSION = "table.tableProfilerConfig";
  public static final String TABLE_COLUMN_EXTENSION = "table.column.";
  public static final String CUSTOM_METRICS_EXTENSION = ".customMetrics";

  public TableRepository(CollectionDAO daoCollection) {
    super(
        TableResource.COLLECTION_PATH,
        TABLE,
        Table.class,
        daoCollection.tableDAO(),
        daoCollection,
        TABLE_PATCH_FIELDS,
        TABLE_UPDATE_FIELDS);
  }

  @Override
  public Table setFields(Table table, Fields fields) throws IOException {
    setDefaultFields(table);
    table.setTableConstraints(fields.contains("tableConstraints") ? table.getTableConstraints() : null);
    table.setFollowers(fields.contains(FIELD_FOLLOWERS) ? getFollowers(table) : null);
    table.setUsageSummary(
        fields.contains("usageSummary") ? EntityUtil.getLatestUsage(daoCollection.usageDAO(), table.getId()) : null);
    getColumnTags(fields.contains(FIELD_TAGS), table.getColumns());
    table.setJoins(fields.contains("joins") ? getJoins(table) : null);
    table.setSampleData(fields.contains("sampleData") ? getSampleData(table) : null);
    table.setViewDefinition(fields.contains("viewDefinition") ? table.getViewDefinition() : null);
    table.setProfile(fields.contains("profile") ? getTableProfile(table) : null);
    getColumnProfile(fields.contains("profile"), table.getColumns());
    table.setTableProfilerConfig(fields.contains("tableProfilerConfig") ? getTableProfilerConfig(table) : null);
    table.setLocation(fields.contains("location") ? getLocation(table) : null);
    table.setTableQueries(fields.contains("tableQueries") ? getQueries(table) : null);
    getCustomMetrics(fields.contains("customMetrics"), table);
    return table;
  }

  private void setDefaultFields(Table table) throws IOException {
    EntityReference schemaRef = getContainer(table.getId());
    DatabaseSchema schema = Entity.getEntity(schemaRef, Fields.EMPTY_FIELDS, Include.ALL);
    table.withDatabaseSchema(schemaRef).withDatabase(schema.getDatabase()).withService(schema.getService());
  }

  @Override
  public void restorePatchAttributes(Table original, Table updated) {
    // Patch can't make changes to following fields. Ignore the changes.
    updated
        .withFullyQualifiedName(original.getFullyQualifiedName())
        .withName(original.getName())
        .withDatabase(original.getDatabase())
        .withService(original.getService())
        .withId(original.getId());
  }

  @Override
  public void setFullyQualifiedName(Table table) {
    table.setFullyQualifiedName(
        FullyQualifiedName.add(table.getDatabaseSchema().getFullyQualifiedName(), table.getName()));
  }

  @Transaction
  public Table addJoins(UUID tableId, TableJoins joins) throws IOException {
    // Validate the request content
    Table table = dao.findEntityById(tableId);

    if (!CommonUtil.dateInRange(RestUtil.DATE_FORMAT, joins.getStartDate(), 0, 30)) {
      throw new IllegalArgumentException("Date range can only include past 30 days starting today");
    }

    // Validate joined columns
    for (ColumnJoin join : joins.getColumnJoins()) {
      validateColumn(table, join.getColumnName());
      validateColumnFQNs(join.getJoinedWith());
    }

    // Validate direct table joins
    for (JoinedWith join : joins.getDirectTableJoins()) {
      validateTableFQN(join.getFullyQualifiedName());
    }

    // With all validation done, add new joins
    for (ColumnJoin join : joins.getColumnJoins()) {
      String columnFQN = FullyQualifiedName.add(table.getFullyQualifiedName(), join.getColumnName());
      addJoinedWith(joins.getStartDate(), columnFQN, FIELD_RELATION_COLUMN_TYPE, join.getJoinedWith());
    }

    addJoinedWith(
        joins.getStartDate(), table.getFullyQualifiedName(), FIELD_RELATION_TABLE_TYPE, joins.getDirectTableJoins());

    return table.withJoins(getJoins(table));
  }

  @Transaction
  public Table addSampleData(UUID tableId, TableData tableData) throws IOException {
    // Validate the request content
    Table table = dao.findEntityById(tableId);

    // Validate all the columns
    for (String columnName : tableData.getColumns()) {
      validateColumn(table, columnName);
    }
    // Make sure each row has number values for all the columns
    for (List<Object> row : tableData.getRows()) {
      if (row.size() != tableData.getColumns().size()) {
        throw new IllegalArgumentException(
            String.format(
                "Number of columns is %d but row has %d sample values", tableData.getColumns().size(), row.size()));
      }
    }

    daoCollection
        .entityExtensionDAO()
        .insert(tableId.toString(), TABLE_SAMPLE_DATA_EXTENSION, "tableData", JsonUtils.pojoToJson(tableData));
    setFieldsInternal(table, Fields.EMPTY_FIELDS);
    return table.withSampleData(tableData);
  }

  @Transaction
  public TableProfilerConfig getTableProfilerConfig(Table table) throws IOException {
    return JsonUtils.readValue(
        daoCollection.entityExtensionDAO().getExtension(table.getId().toString(), TABLE_PROFILER_CONFIG_EXTENSION),
        TableProfilerConfig.class);
  }

  @Transaction
  public Table addTableProfilerConfig(UUID tableId, TableProfilerConfig tableProfilerConfig) throws IOException {
    // Validate the request content
    Table table = dao.findEntityById(tableId);

    // Validate all the columns
    if (tableProfilerConfig.getExcludeColumns() != null) {
      for (String columnName : tableProfilerConfig.getExcludeColumns()) {
        validateColumn(table, columnName);
      }
    }

    if (tableProfilerConfig.getIncludeColumns() != null) {
      for (ColumnProfilerConfig columnProfilerConfig : tableProfilerConfig.getIncludeColumns()) {
        validateColumn(table, columnProfilerConfig.getColumnName());
      }
    }

    daoCollection
        .entityExtensionDAO()
        .insert(
            tableId.toString(),
            TABLE_PROFILER_CONFIG_EXTENSION,
            "tableProfilerConfig",
            JsonUtils.pojoToJson(tableProfilerConfig));
    setFieldsInternal(table, Fields.EMPTY_FIELDS);
    return table.withTableProfilerConfig(tableProfilerConfig);
  }

  @Transaction
  public Table deleteTableProfilerConfig(UUID tableId) throws IOException {
    // Validate the request content
    Table table = dao.findEntityById(tableId);

    daoCollection.entityExtensionDAO().delete(tableId.toString(), TABLE_PROFILER_CONFIG_EXTENSION);
    setFieldsInternal(table, Fields.EMPTY_FIELDS);
    return table;
  }

  @Transaction
  public Table addTableProfileData(UUID tableId, CreateTableProfile createTableProfile) throws IOException {
    // Validate the request content
    Table table = dao.findEntityById(tableId);
    TableProfile storedTableProfile =
        JsonUtils.readValue(
            daoCollection
                .entityExtensionTimeSeriesDao()
                .getExtensionAtTimestamp(
                    table.getFullyQualifiedName(),
                    TABLE_PROFILE_EXTENSION,
                    createTableProfile.getTableProfile().getTimestamp()),
            TableProfile.class);
    if (storedTableProfile != null) {
      daoCollection
          .entityExtensionTimeSeriesDao()
          .update(
              table.getFullyQualifiedName(),
              TABLE_PROFILE_EXTENSION,
              JsonUtils.pojoToJson(createTableProfile.getTableProfile()),
              createTableProfile.getTableProfile().getTimestamp());
    } else {
      daoCollection
          .entityExtensionTimeSeriesDao()
          .insert(
              table.getFullyQualifiedName(),
              TABLE_PROFILE_EXTENSION,
              "tableProfile",
              JsonUtils.pojoToJson(createTableProfile.getTableProfile()));
    }

    for (ColumnProfile columnProfile : createTableProfile.getColumnProfile()) {
      // Validate all the columns
      Column column =
          table.getColumns().stream().filter(c -> c.getName().equals(columnProfile.getName())).findFirst().orElse(null);
      if (column == null) {
        throw new IllegalArgumentException("Invalid column name " + columnProfile.getName());
      }
      ColumnProfile storedColumnProfile =
          JsonUtils.readValue(
              daoCollection
                  .entityExtensionTimeSeriesDao()
                  .getExtensionAtTimestamp(
                      column.getFullyQualifiedName(), TABLE_COLUMN_PROFILE_EXTENSION, columnProfile.getTimestamp()),
              ColumnProfile.class);

      if (storedColumnProfile != null) {
        daoCollection
            .entityExtensionTimeSeriesDao()
            .update(
                column.getFullyQualifiedName(),
                TABLE_COLUMN_PROFILE_EXTENSION,
                JsonUtils.pojoToJson(columnProfile),
                storedColumnProfile.getTimestamp());
      } else {
        daoCollection
            .entityExtensionTimeSeriesDao()
            .insert(
                column.getFullyQualifiedName(),
                TABLE_COLUMN_PROFILE_EXTENSION,
                "columnProfile",
                JsonUtils.pojoToJson(columnProfile));
      }
    }
    setFieldsInternal(table, Fields.EMPTY_FIELDS);
    return table.withProfile(createTableProfile.getTableProfile());
  }

  @Transaction
  public void deleteTableProfile(String fqn, String entityType, Long timestamp) throws IOException {
    // Validate the request content
    String extension;
    if (entityType.equalsIgnoreCase(Entity.TABLE)) {
      extension = TABLE_PROFILE_EXTENSION;
    } else if (entityType.equalsIgnoreCase("column")) {
      extension = TABLE_COLUMN_PROFILE_EXTENSION;
    } else {
      throw new IllegalArgumentException("entityType must be table or column");
    }

    TableProfile storedTableProfile =
        JsonUtils.readValue(
            daoCollection.entityExtensionTimeSeriesDao().getExtensionAtTimestamp(fqn, extension, timestamp),
            TableProfile.class);
    if (storedTableProfile == null) {
      throw new EntityNotFoundException(String.format("Failed to find table profile for %s at %s", fqn, timestamp));
    }
    daoCollection.entityExtensionTimeSeriesDao().deleteAtTimestamp(fqn, extension, timestamp);
  }

  @Transaction
  public Table addLocation(UUID tableId, UUID locationId) throws IOException {
    Table table = dao.findEntityById(tableId);
    EntityReference location = daoCollection.locationDAO().findEntityReferenceById(locationId);
    // A table has only one location.
    deleteFrom(tableId, TABLE, Relationship.HAS, LOCATION);
    addRelationship(tableId, locationId, TABLE, LOCATION, Relationship.HAS);
    setFieldsInternal(table, Fields.EMPTY_FIELDS);
    return table.withLocation(location);
  }

  @Transaction
  public Table addQuery(UUID tableId, SQLQuery query) throws IOException {
    // Validate the request content
    try {
      byte[] checksum = MessageDigest.getInstance("MD5").digest(query.getQuery().getBytes());
      query.setChecksum(Hex.encodeHexString(checksum));
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
    Table table = dao.findEntityById(tableId);
    List<SQLQuery> storedQueries = getQueries(table);
    Map<String, SQLQuery> storedMapQueries = new HashMap<>();
    if (storedQueries != null) {
      for (SQLQuery q : storedQueries) {
        storedMapQueries.put(q.getChecksum(), q);
      }
    }
    SQLQuery oldQuery = storedMapQueries.get(query.getChecksum());
    if (oldQuery != null && query.getUsers() != null) {
      // Merge old and new users
      List<EntityReference> userList = query.getUsers();
      userList.addAll(oldQuery.getUsers());
      HashSet<EntityReference> userSet = new HashSet<>(userList);
      query.setUsers(new ArrayList<>(userSet));
    }
    storedMapQueries.put(query.getChecksum(), query);
    List<SQLQuery> updatedQueries = new ArrayList<>(storedMapQueries.values());
    daoCollection
        .entityExtensionDAO()
        .insert(tableId.toString(), "table.tableQueries", "sqlQuery", JsonUtils.pojoToJson(updatedQueries));
    setFieldsInternal(table, Fields.EMPTY_FIELDS);
    return table.withTableQueries(getQueries(table));
  }

  @Transaction
  public Table addCustomMetric(UUID tableId, CustomMetric customMetric) throws IOException {
    // Validate the request content
    Table table = dao.findEntityById(tableId);
    String columnName = customMetric.getColumnName();
    validateColumn(table, columnName);

    // Override any custom metric definition with the same name
    List<CustomMetric> storedCustomMetrics = getCustomMetrics(table, columnName);
    Map<String, CustomMetric> storedMapCustomMetrics = new HashMap<>();
    if (storedCustomMetrics != null) {
      for (CustomMetric cm : storedCustomMetrics) {
        storedMapCustomMetrics.put(cm.getName(), cm);
      }
    }

    // existing metric use the previous UUID
    if (storedMapCustomMetrics.containsKey(customMetric.getName())) {
      CustomMetric prevMetric = storedMapCustomMetrics.get(customMetric.getName());
      customMetric.setId(prevMetric.getId());
    }

    storedMapCustomMetrics.put(customMetric.getName(), customMetric);
    List<CustomMetric> updatedMetrics = new ArrayList<>(storedMapCustomMetrics.values());
    String extension = TABLE_COLUMN_EXTENSION + columnName + CUSTOM_METRICS_EXTENSION;
    daoCollection
        .entityExtensionDAO()
        .insert(table.getId().toString(), extension, "customMetric", JsonUtils.pojoToJson(updatedMetrics));
    setFieldsInternal(table, Fields.EMPTY_FIELDS);
    // return the newly created/updated custom metric only
    for (Column column : table.getColumns()) {
      if (column.getName().equals(columnName)) {
        column.setCustomMetrics(List.of(customMetric));
      }
    }
    return table;
  }

  @Transaction
  public Table deleteCustomMetric(UUID tableId, String columnName, String metricName) throws IOException {
    // Validate the request content
    Table table = dao.findEntityById(tableId);
    validateColumn(table, columnName);

    // Override any custom metric definition with the same name
    List<CustomMetric> storedCustomMetrics = getCustomMetrics(table, columnName);
    Map<String, CustomMetric> storedMapCustomMetrics = new HashMap<>();
    if (storedCustomMetrics != null) {
      for (CustomMetric cm : storedCustomMetrics) {
        storedMapCustomMetrics.put(cm.getName(), cm);
      }
    }

    if (!storedMapCustomMetrics.containsKey(metricName)) {
      throw new EntityNotFoundException(String.format("Failed to find %s for %s", metricName, table.getName()));
    }

    CustomMetric deleteCustomMetric = storedMapCustomMetrics.get(metricName);
    storedMapCustomMetrics.remove(metricName);
    List<CustomMetric> updatedMetrics = new ArrayList<>(storedMapCustomMetrics.values());
    String extension = TABLE_COLUMN_EXTENSION + columnName + CUSTOM_METRICS_EXTENSION;
    daoCollection
        .entityExtensionDAO()
        .insert(table.getId().toString(), extension, "customMetric", JsonUtils.pojoToJson(updatedMetrics));
    // return the newly created/updated custom metric test only
    for (Column column : table.getColumns()) {
      if (column.getName().equals(columnName)) {
        column.setCustomMetrics(List.of(deleteCustomMetric));
      }
    }
    return table;
  }

  @Transaction
  public Table addDataModel(UUID tableId, DataModel dataModel) throws IOException {
    Table table = dao.findEntityById(tableId);
    table.withDataModel(dataModel);

    // Carry forward the table owner from the model to table entity, if empty
    if (table.getOwner() == null) {
      storeOwner(table, dataModel.getOwner());
    }

    table.setTags(dataModel.getTags());
    applyTags(table);

    // Carry forward the column description from the model to table columns, if empty
    for (Column modelColumn : listOrEmpty(dataModel.getColumns())) {
      Column stored =
          table.getColumns().stream()
              .filter(c -> EntityUtil.columnNameMatch.test(c, modelColumn))
              .findAny()
              .orElse(null);
      if (stored == null) {
        continue;
      }
      stored.setTags(modelColumn.getTags());
    }
    applyTags(table.getColumns());
    dao.update(table.getId(), JsonUtils.pojoToJson(table));

    setFieldsInternal(table, new Fields(List.of(FIELD_OWNER), FIELD_OWNER));
    setFieldsInternal(table, new Fields(List.of(FIELD_TAGS), FIELD_TAGS));

    return table;
  }

  @Transaction
  public void deleteLocation(UUID tableId) {
    deleteFrom(tableId, TABLE, Relationship.HAS, LOCATION);
  }

  private void setColumnFQN(String parentFQN, List<Column> columns) {
    columns.forEach(
        c -> {
          String columnFqn = FullyQualifiedName.add(parentFQN, c.getName());
          c.setFullyQualifiedName(columnFqn);
          if (c.getChildren() != null) {
            setColumnFQN(columnFqn, c.getChildren());
          }
        });
  }

  private void addDerivedColumnTags(List<Column> columns) {
    if (nullOrEmpty(columns)) {
      return;
    }

    for (Column column : columns) {
      column.setTags(addDerivedTags(column.getTags()));
      if (column.getChildren() != null) {
        addDerivedColumnTags(column.getChildren());
      }
    }
  }

  @Override
  public void prepare(Table table) throws IOException {
    DatabaseSchema schema = Entity.getEntity(table.getDatabaseSchema(), Fields.EMPTY_FIELDS, Include.ALL);
    table.setDatabaseSchema(schema.getEntityReference());
    table.setDatabase(schema.getDatabase());
    table.setService(schema.getService());
    table.setServiceType(schema.getServiceType());
    setFullyQualifiedName(table);

    setColumnFQN(table.getFullyQualifiedName(), table.getColumns());

    // Validate table tags and add derived tags to the list
    table.setTags(addDerivedTags(table.getTags()));

    // Validate column tags
    addDerivedColumnTags(table.getColumns());
  }

  private EntityReference getLocation(Table table) throws IOException {
    return getToEntityRef(table.getId(), Relationship.HAS, LOCATION, false);
  }

  @Override
  public void storeEntity(Table table, boolean update) throws IOException {
    // Relationships and fields such as href are derived and not stored as part of json
    EntityReference owner = table.getOwner();
    List<TagLabel> tags = table.getTags();
    EntityReference service = table.getService();

    // Don't store owner, database, href and tags as JSON. Build it on the fly based on relationships
    table.withOwner(null).withHref(null).withTags(null).withService(null);

    // Don't store column tags as JSON but build it on the fly based on relationships
    List<Column> columnWithTags = table.getColumns();
    table.setColumns(cloneWithoutTags(columnWithTags));
    table.getColumns().forEach(column -> column.setTags(null));

    store(table, update);

    // Restore the relationships
    table.withOwner(owner).withTags(tags).withColumns(columnWithTags).withService(service);
  }

  @Override
  public void storeRelationships(Table table) {
    // Add relationship from database to table
    addRelationship(table.getDatabaseSchema().getId(), table.getId(), DATABASE_SCHEMA, TABLE, Relationship.CONTAINS);

    // Add table owner relationship
    storeOwner(table, table.getOwner());

    // Add tag to table relationship
    applyTags(table);
  }

  @Override
  public EntityUpdater getUpdater(Table original, Table updated, Operation operation) {
    return new TableUpdater(original, updated, operation);
  }

  List<Column> cloneWithoutTags(List<Column> columns) {
    if (nullOrEmpty(columns)) {
      return columns;
    }
    List<Column> copy = new ArrayList<>();
    columns.forEach(c -> copy.add(cloneWithoutTags(c)));
    return copy;
  }

  private Column cloneWithoutTags(Column column) {
    List<Column> children = cloneWithoutTags(column.getChildren());
    return new Column()
        .withDescription(column.getDescription())
        .withName(column.getName())
        .withDisplayName(column.getDisplayName())
        .withFullyQualifiedName(column.getFullyQualifiedName())
        .withArrayDataType(column.getArrayDataType())
        .withConstraint(column.getConstraint())
        .withDataTypeDisplay(column.getDataTypeDisplay())
        .withDataType(column.getDataType())
        .withDataLength(column.getDataLength())
        .withPrecision(column.getPrecision())
        .withScale(column.getScale())
        .withOrdinalPosition(column.getOrdinalPosition())
        .withChildren(children);
  }

  private void applyTags(List<Column> columns) {
    // Add column level tags by adding tag to column relationship
    for (Column column : columns) {
      applyTags(column.getTags(), column.getFullyQualifiedName());
      if (column.getChildren() != null) {
        applyTags(column.getChildren());
      }
    }
  }

  @Override
  public void applyTags(Table table) {
    // Add table level tags by adding tag to table relationship
    super.applyTags(table);
    applyTags(table.getColumns());
  }

  private void getColumnTags(boolean setTags, List<Column> columns) {
    for (Column c : listOrEmpty(columns)) {
      c.setTags(setTags ? getTags(c.getFullyQualifiedName()) : null);
      getColumnTags(setTags, c.getChildren());
    }
  }

  private void getColumnProfile(boolean setProfile, List<Column> columns) throws IOException {
    if (setProfile) {
      for (Column c : listOrEmpty(columns)) {
        c.setProfile(
            JsonUtils.readValue(
                daoCollection
                    .entityExtensionTimeSeriesDao()
                    .getLatestExtension(c.getFullyQualifiedName(), TABLE_COLUMN_PROFILE_EXTENSION),
                ColumnProfile.class));
      }
    }
  }

  private void validateTableFQN(String fqn) {
    try {
      dao.existsByName(fqn);
    } catch (EntityNotFoundException e) {
      throw new IllegalArgumentException("Invalid table name " + fqn, e);
    }
  }

  // Validate if a given column exists in the table
  public static void validateColumn(Table table, String columnName) {
    boolean validColumn = table.getColumns().stream().anyMatch(col -> col.getName().equals(columnName));
    if (!validColumn) {
      throw new IllegalArgumentException("Invalid column name " + columnName);
    }
  }

  // Validate if a given column exists in the table
  public static void validateColumnFQN(Table table, String columnFQN) {
    boolean validColumn = false;
    for (Column column : table.getColumns()) {
      if (column.getFullyQualifiedName().equals(columnFQN)) {
        validColumn = true;
        break;
      }
    }
    if (!validColumn) {
      throw new IllegalArgumentException(CatalogExceptionMessage.invalidColumnFQN(columnFQN));
    }
  }

  private void validateColumnFQNs(List<JoinedWith> joinedWithList) {
    for (JoinedWith joinedWith : joinedWithList) {
      // Validate table
      String tableFQN = FullyQualifiedName.getTableFQN(joinedWith.getFullyQualifiedName());
      Table joinedWithTable = dao.findEntityByName(tableFQN);

      // Validate column
      validateColumnFQN(joinedWithTable, joinedWith.getFullyQualifiedName());
    }
  }

  /**
   * Updates join data in the database for an entity and a relation type. Currently, used pairs of ({@code entityFQN},
   * {@code entityRelationType}) are ({@link Table#getFullyQualifiedName()}, "table") and ({@link
   * Column#getFullyQualifiedName()}, "table.columns.column").
   *
   * <p>If for a field relation (any relation between {@code entityFQN} and a FQN from {@code joinedWithList}), after
   * combining the existing list of {@link DailyCount} with join data from {@code joinedWithList}, there are multiple
   * {@link DailyCount} with the {@link DailyCount#getDate()}, these will <bold>NOT</bold> be merged - the value of
   * {@link JoinedWith#getJoinCount()} will override the current value.
   */
  private void addJoinedWith(String date, String entityFQN, String entityRelationType, List<JoinedWith> joinedWithList)
      throws IOException {
    // Use the column that comes alphabetically first as the from field and the other as to field.
    // This helps us keep the bidirectional relationship to a single row instead one row for
    // capturing relationship in each direction.
    //
    // One row like this     - fromColumn <--- joinedWith --> toColumn
    // Instead of additional - toColumn <--- joinedWith --> fromColumn
    for (JoinedWith joinedWith : joinedWithList) {
      String fromEntityFQN;
      String toEntityFQN;
      if (entityFQN.compareTo(joinedWith.getFullyQualifiedName()) < 0) {
        fromEntityFQN = entityFQN;
        toEntityFQN = joinedWith.getFullyQualifiedName();
      } else {
        fromEntityFQN = joinedWith.getFullyQualifiedName();
        toEntityFQN = entityFQN;
      }

      List<DailyCount> currentDailyCounts =
          Optional.ofNullable(
                  daoCollection
                      .fieldRelationshipDAO()
                      .find(
                          fromEntityFQN,
                          toEntityFQN,
                          entityRelationType,
                          entityRelationType,
                          Relationship.JOINED_WITH.ordinal()))
              .map(rethrowFunction(j -> JsonUtils.readObjects(j, DailyCount.class)))
              .orElse(List.of());

      DailyCount receivedDailyCount = new DailyCount().withCount(joinedWith.getJoinCount()).withDate(date);

      List<DailyCount> newDailyCounts = aggregateAndFilterDailyCounts(currentDailyCounts, receivedDailyCount);

      daoCollection
          .fieldRelationshipDAO()
          .upsert(
              fromEntityFQN,
              toEntityFQN,
              entityRelationType,
              entityRelationType,
              Relationship.JOINED_WITH.ordinal(),
              "dailyCount",
              JsonUtils.pojoToJson(newDailyCounts));
    }
  }

  public ResultList<TableProfile> getTableProfiles(String fqn, Long startTs, Long endTs) throws IOException {
    List<TableProfile> tableProfiles;
    tableProfiles =
        JsonUtils.readObjects(
            daoCollection
                .entityExtensionTimeSeriesDao()
                .listBetweenTimestamps(fqn, TABLE_PROFILE_EXTENSION, startTs, endTs),
            TableProfile.class);
    return new ResultList<>(tableProfiles, startTs.toString(), endTs.toString(), tableProfiles.size());
  }

  public ResultList<ColumnProfile> getColumnProfiles(String fqn, Long startTs, Long endTs) throws IOException {
    List<ColumnProfile> columnProfiles;
    columnProfiles =
        JsonUtils.readObjects(
            daoCollection
                .entityExtensionTimeSeriesDao()
                .listBetweenTimestamps(fqn, TABLE_COLUMN_PROFILE_EXTENSION, startTs, endTs),
            ColumnProfile.class);
    return new ResultList<>(columnProfiles, startTs.toString(), endTs.toString(), columnProfiles.size());
  }

  /**
   * Pure function that creates a new list of {@link DailyCount} by either adding the {@code newDailyCount} to the list
   * or, if there is already data for the date {@code newDailyCount.getDate()}, replace older count with the new one.
   * Ensures the following properties: all elements in the list have unique dates, all dates are not older than 30 days
   * from today, the list is ordered by date.
   */
  private List<DailyCount> aggregateAndFilterDailyCounts(
      List<DailyCount> currentDailyCounts, DailyCount newDailyCount) {
    Map<String, List<DailyCount>> joinCountByDay =
        Streams.concat(currentDailyCounts.stream(), Stream.of(newDailyCount)).collect(groupingBy(DailyCount::getDate));

    return joinCountByDay.entrySet().stream()
        .map(
            e -> {
              if (e.getKey().equals(newDailyCount.getDate())) return newDailyCount;
              else
                return new DailyCount()
                    .withDate(e.getKey())
                    .withCount(
                        e.getValue().stream()
                            .findFirst()
                            .orElseThrow(
                                () -> new IllegalStateException("Collector.groupingBy created an empty grouping"))
                            .getCount());
            })
        .filter(inLast30Days())
        .sorted(ignoringComparator((dc1, dc2) -> RestUtil.compareDates(dc1.getDate(), dc2.getDate())))
        .collect(Collectors.toList());
  }

  private TableJoins getJoins(Table table) {
    String today = RestUtil.DATE_FORMAT.format(new Date());
    String todayMinus30Days = CommonUtil.getDateStringByOffset(RestUtil.DATE_FORMAT, today, -30);
    return new TableJoins()
        .withStartDate(todayMinus30Days)
        .withDayCount(30)
        .withColumnJoins(getColumnJoins(table))
        .withDirectTableJoins(getDirectTableJoins(table));
  }

  private List<JoinedWith> getDirectTableJoins(Table table) {
    // Pair<toTableFQN, List<DailyCount>>
    List<Pair<String, List<DailyCount>>> entityRelations =
        daoCollection.fieldRelationshipDAO()
            .listBidirectional(
                table.getFullyQualifiedName(),
                FIELD_RELATION_TABLE_TYPE,
                FIELD_RELATION_TABLE_TYPE,
                Relationship.JOINED_WITH.ordinal())
            .stream()
            .map(rethrowFunction(er -> Pair.of(er.getMiddle(), JsonUtils.readObjects(er.getRight(), DailyCount.class))))
            .collect(toUnmodifiableList());

    return entityRelations.stream()
        .map(
            er ->
                new JoinedWith()
                    .withFullyQualifiedName(er.getLeft())
                    .withJoinCount(er.getRight().stream().filter(inLast30Days()).mapToInt(DailyCount::getCount).sum()))
        .collect(Collectors.toList());
  }

  private List<ColumnJoin> getColumnJoins(Table table) {
    // Triple<fromRelativeColumnName, toFQN, List<DailyCount>>
    List<Triple<String, String, List<DailyCount>>> entityRelations =
        daoCollection.fieldRelationshipDAO()
            .listBidirectionalByPrefix(
                table.getFullyQualifiedName(),
                FIELD_RELATION_COLUMN_TYPE,
                FIELD_RELATION_COLUMN_TYPE,
                Relationship.JOINED_WITH.ordinal())
            .stream()
            .map(
                rethrowFunction(
                    er ->
                        Triple.of(
                            FullyQualifiedName.getColumnName(er.getLeft()),
                            er.getMiddle(),
                            JsonUtils.readObjects(er.getRight(), DailyCount.class))))
            .collect(toUnmodifiableList());

    return entityRelations.stream()
        .collect(groupingBy(Triple::getLeft))
        .entrySet()
        .stream()
        .map(
            e ->
                new ColumnJoin()
                    .withColumnName(e.getKey())
                    .withJoinedWith(
                        e.getValue().stream()
                            .map(
                                er ->
                                    new JoinedWith()
                                        .withFullyQualifiedName(er.getMiddle())
                                        .withJoinCount(
                                            er.getRight().stream()
                                                .filter(inLast30Days())
                                                .mapToInt(DailyCount::getCount)
                                                .sum()))
                            .collect(toUnmodifiableList())))
        .collect(toUnmodifiableList());
  }

  private Predicate<DailyCount> inLast30Days() {
    return dc -> CommonUtil.dateInRange(RestUtil.DATE_FORMAT, dc.getDate(), 0, 30);
  }

  private TableData getSampleData(Table table) throws IOException {
    return JsonUtils.readValue(
        daoCollection.entityExtensionDAO().getExtension(table.getId().toString(), TABLE_SAMPLE_DATA_EXTENSION),
        TableData.class);
  }

  private TableProfile getTableProfile(Table table) throws IOException {
    return JsonUtils.readValue(
        daoCollection
            .entityExtensionTimeSeriesDao()
            .getLatestExtension(table.getFullyQualifiedName(), TABLE_PROFILE_EXTENSION),
        TableProfile.class);
  }

  private List<SQLQuery> getQueries(Table table) throws IOException {
    List<SQLQuery> tableQueries =
        JsonUtils.readObjects(
            daoCollection.entityExtensionDAO().getExtension(table.getId().toString(), "table.tableQueries"),
            SQLQuery.class);
    if (tableQueries != null) {
      tableQueries.sort(Comparator.comparing(SQLQuery::getVote, Comparator.reverseOrder()));
    }
    return tableQueries;
  }

  private List<CustomMetric> getCustomMetrics(Table table, String columnName) throws IOException {
    String extension = TABLE_COLUMN_EXTENSION + columnName + CUSTOM_METRICS_EXTENSION;
    return JsonUtils.readObjects(
        daoCollection.entityExtensionDAO().getExtension(table.getId().toString(), extension), CustomMetric.class);
  }

  private void getCustomMetrics(boolean setMetrics, Table table) throws IOException {
    // Add custom metrics info to columns if requested
    List<Column> columns = table.getColumns();
    for (Column c : listOrEmpty(columns)) {
      c.setCustomMetrics(setMetrics ? getCustomMetrics(table, c.getName()) : null);
    }
  }

  /** Handles entity updated from PUT and POST operation. */
  public class TableUpdater extends EntityUpdater {
    public TableUpdater(Table original, Table updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() throws IOException {
      Table origTable = original;
      Table updatedTable = updated;
      DatabaseUtil.validateColumns(updatedTable);
      recordChange("tableType", origTable.getTableType(), updatedTable.getTableType());
      updateConstraints(origTable, updatedTable);
      updateColumns("columns", origTable.getColumns(), updated.getColumns(), EntityUtil.columnMatch);
    }

    private void updateConstraints(Table origTable, Table updatedTable) throws JsonProcessingException {
      List<TableConstraint> origConstraints = listOrEmpty(origTable.getTableConstraints());
      List<TableConstraint> updatedConstraints = listOrEmpty(updatedTable.getTableConstraints());

      origConstraints.sort(EntityUtil.compareTableConstraint);
      origConstraints.stream().map(TableConstraint::getColumns).forEach(Collections::sort);

      updatedConstraints.sort(EntityUtil.compareTableConstraint);
      updatedConstraints.stream().map(TableConstraint::getColumns).forEach(Collections::sort);

      List<TableConstraint> added = new ArrayList<>();
      List<TableConstraint> deleted = new ArrayList<>();
      recordListChange(
          "tableConstraints", origConstraints, updatedConstraints, added, deleted, EntityUtil.tableConstraintMatch);
    }

    private void updateColumns(
        String fieldName,
        List<Column> origColumns,
        List<Column> updatedColumns,
        BiPredicate<Column, Column> columnMatch)
        throws IOException {
      List<Column> deletedColumns = new ArrayList<>();
      List<Column> addedColumns = new ArrayList<>();
      recordListChange(fieldName, origColumns, updatedColumns, addedColumns, deletedColumns, columnMatch);
      // carry forward tags and description if deletedColumns matches added column
      Map<String, Column> addedColumnMap =
          addedColumns.stream().collect(Collectors.toMap(Column::getName, Function.identity()));

      for (Column deleted : deletedColumns) {
        if (addedColumnMap.containsKey(deleted.getName())) {
          Column addedColumn = addedColumnMap.get(deleted.getName());
          if (nullOrEmpty(addedColumn.getDescription()) && nullOrEmpty(deleted.getDescription())) {
            addedColumn.setDescription(deleted.getDescription());
          }
          if (nullOrEmpty(addedColumn.getTags()) && nullOrEmpty(deleted.getTags())) {
            addedColumn.setTags(deleted.getTags());
          }
        }
      }

      // Delete tags related to deleted columns
      deletedColumns.forEach(
          deleted -> daoCollection.tagUsageDAO().deleteTagsByTarget(deleted.getFullyQualifiedName()));

      // Add tags related to newly added columns
      for (Column added : addedColumns) {
        applyTags(added.getTags(), added.getFullyQualifiedName());
      }

      // Carry forward the user generated metadata from existing columns to new columns
      for (Column updated : updatedColumns) {
        // Find stored column matching name, data type and ordinal position
        Column stored = origColumns.stream().filter(c -> columnMatch.test(c, updated)).findAny().orElse(null);
        if (stored == null) { // New column added
          continue;
        }

        updateColumnDescription(stored, updated);
        updateColumnDisplayName(stored, updated);
        updateColumnDataLength(stored, updated);
        updateColumnPrecision(stored, updated);
        updateColumnScale(stored, updated);
        updateTags(
            stored.getFullyQualifiedName(),
            EntityUtil.getFieldName(fieldName, updated.getName(), FIELD_TAGS),
            stored.getTags(),
            updated.getTags());
        updateColumnConstraint(stored, updated);

        if (updated.getChildren() != null && stored.getChildren() != null) {
          String childrenFieldName = EntityUtil.getFieldName(fieldName, updated.getName());
          updateColumns(childrenFieldName, stored.getChildren(), updated.getChildren(), columnMatch);
        }
      }

      majorVersionChange = majorVersionChange || !deletedColumns.isEmpty();
    }

    private void updateColumnDescription(Column origColumn, Column updatedColumn) throws JsonProcessingException {
      if (operation.isPut() && !nullOrEmpty(origColumn.getDescription()) && updatedByBot()) {
        // Revert the non-empty task description if being updated by a bot
        updatedColumn.setDescription(origColumn.getDescription());
        return;
      }
      String columnField = getColumnField(original, origColumn, FIELD_DESCRIPTION);
      recordChange(columnField, origColumn.getDescription(), updatedColumn.getDescription());
    }

    private void updateColumnDisplayName(Column origColumn, Column updatedColumn) throws JsonProcessingException {
      if (operation.isPut() && !nullOrEmpty(origColumn.getDescription()) && updatedByBot()) {
        // Revert the non-empty task description if being updated by a bot
        updatedColumn.setDisplayName(origColumn.getDisplayName());
        return;
      }
      String columnField = getColumnField(original, origColumn, FIELD_DISPLAY_NAME);
      recordChange(columnField, origColumn.getDisplayName(), updatedColumn.getDisplayName());
    }

    private void updateColumnConstraint(Column origColumn, Column updatedColumn) throws JsonProcessingException {
      String columnField = getColumnField(original, origColumn, "constraint");
      recordChange(columnField, origColumn.getConstraint(), updatedColumn.getConstraint());
    }

    protected void updateColumnDataLength(Column origColumn, Column updatedColumn) throws JsonProcessingException {
      String columnField = getColumnField(original, origColumn, "dataLength");
      boolean updated = recordChange(columnField, origColumn.getDataLength(), updatedColumn.getDataLength());
      if (updated
          && (origColumn.getDataLength() == null || updatedColumn.getDataLength() < origColumn.getDataLength())) {
        // The data length of a column was reduced or added. Treat it as backward-incompatible change
        majorVersionChange = true;
      }
    }

    private void updateColumnPrecision(Column origColumn, Column updatedColumn) throws JsonProcessingException {
      String columnField = getColumnField(original, origColumn, "precision");
      boolean updated = recordChange(columnField, origColumn.getPrecision(), updatedColumn.getPrecision());
      if (origColumn.getPrecision() != null
          && updated
          && updatedColumn.getPrecision() < origColumn.getPrecision()) { // Previously precision was set
        // The precision was reduced. Treat it as backward-incompatible change
        majorVersionChange = true;
      }
    }

    private void updateColumnScale(Column origColumn, Column updatedColumn) throws JsonProcessingException {
      String columnField = getColumnField(original, origColumn, "scale");
      boolean updated = recordChange(columnField, origColumn.getScale(), updatedColumn.getScale());
      if (origColumn.getScale() != null
          && updated
          && updatedColumn.getScale() < origColumn.getScale()) { // Previously scale was set
        // The scale was reduced. Treat it as backward-incompatible change
        majorVersionChange = true;
      }
    }
  }
}
