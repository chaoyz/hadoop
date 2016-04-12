/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.timelineservice.storage.reader;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.BinaryPrefixComparator;
import org.apache.hadoop.hbase.filter.FamilyFilter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.FilterList.Operator;
import org.apache.hadoop.yarn.api.records.timelineservice.FlowRunEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineDataToRetrieve;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineEntityFilters;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineReaderContext;
import org.apache.hadoop.yarn.server.timelineservice.reader.filter.TimelineFilterList;
import org.apache.hadoop.yarn.server.timelineservice.reader.filter.TimelineFilterUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.TimelineReader.Field;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.BaseTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineStorageUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunColumnFamily;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunTable;

import com.google.common.base.Preconditions;

/**
 * Timeline entity reader for flow run entities that are stored in the flow run
 * table.
 */
class FlowRunEntityReader extends TimelineEntityReader {
  private static final FlowRunTable FLOW_RUN_TABLE = new FlowRunTable();

  public FlowRunEntityReader(TimelineReaderContext ctxt,
      TimelineEntityFilters entityFilters, TimelineDataToRetrieve toRetrieve) {
    super(ctxt, entityFilters, toRetrieve, true);
  }

  public FlowRunEntityReader(TimelineReaderContext ctxt,
      TimelineDataToRetrieve toRetrieve) {
    super(ctxt, toRetrieve);
  }

  /**
   * Uses the {@link FlowRunTable}.
   */
  @Override
  protected BaseTable<?> getTable() {
    return FLOW_RUN_TABLE;
  }

  @Override
  protected void validateParams() {
    Preconditions.checkNotNull(getContext(), "context shouldn't be null");
    Preconditions.checkNotNull(
        getDataToRetrieve(), "data to retrieve shouldn't be null");
    Preconditions.checkNotNull(getContext().getClusterId(),
        "clusterId shouldn't be null");
    Preconditions.checkNotNull(getContext().getUserId(),
        "userId shouldn't be null");
    Preconditions.checkNotNull(getContext().getFlowName(),
        "flowName shouldn't be null");
    if (isSingleEntityRead()) {
      Preconditions.checkNotNull(getContext().getFlowRunId(),
          "flowRunId shouldn't be null");
    }
  }

  @Override
  protected void augmentParams(Configuration hbaseConf, Connection conn) {
    // Add metrics to fields to retrieve if metricsToRetrieve is specified.
    getDataToRetrieve().addFieldsBasedOnConfsAndMetricsToRetrieve();
    if (!isSingleEntityRead()) {
      createFiltersIfNull();
    }
  }

  protected FilterList constructFilterListBasedOnFilters() throws IOException {
    FilterList listBasedOnFilters = new FilterList();
    // Filter based on created time range.
    Long createdTimeBegin = getFilters().getCreatedTimeBegin();
    Long createdTimeEnd = getFilters().getCreatedTimeEnd();
    if (createdTimeBegin != 0 || createdTimeEnd != Long.MAX_VALUE) {
      listBasedOnFilters.addFilter(
          TimelineFilterUtils.createSingleColValueFiltersByRange(
          FlowRunColumn.MIN_START_TIME, createdTimeBegin, createdTimeEnd));
    }
    // Filter based on metric filters.
    TimelineFilterList metricFilters = getFilters().getMetricFilters();
    if (metricFilters != null && !metricFilters.getFilterList().isEmpty()) {
      listBasedOnFilters.addFilter(
          TimelineFilterUtils.createHBaseFilterList(
              FlowRunColumnPrefix.METRIC, metricFilters));
    }
    return listBasedOnFilters;
  }

  /**
   * Add {@link QualifierFilter} filters to filter list for each column of
   * flow run table.
   *
   * @return filter list to which qualifier filters have been added.
   */
  private FilterList updateFixedColumns() {
    FilterList columnsList = new FilterList(Operator.MUST_PASS_ONE);
    for (FlowRunColumn column : FlowRunColumn.values()) {
      columnsList.addFilter(new QualifierFilter(CompareOp.EQUAL,
          new BinaryComparator(column.getColumnQualifierBytes())));
    }
    return columnsList;
  }

  @Override
  protected FilterList constructFilterListBasedOnFields() throws IOException {
    FilterList list = new FilterList(Operator.MUST_PASS_ONE);
    // By default fetch everything in INFO column family.
    FamilyFilter infoColumnFamily =
        new FamilyFilter(CompareOp.EQUAL,
           new BinaryComparator(FlowRunColumnFamily.INFO.getBytes()));
    TimelineDataToRetrieve dataToRetrieve = getDataToRetrieve();
    // If multiple entities have to be retrieved, check if metrics have to be
    // retrieved and if not, add a filter so that metrics can be excluded.
    // Metrics are always returned if we are reading a single entity.
    if (!isSingleEntityRead() && !TimelineStorageUtils.hasField(
        dataToRetrieve.getFieldsToRetrieve(), Field.METRICS)) {
      FilterList infoColFamilyList = new FilterList(Operator.MUST_PASS_ONE);
      infoColFamilyList.addFilter(infoColumnFamily);
      infoColFamilyList.addFilter(
          new QualifierFilter(CompareOp.NOT_EQUAL,
          new BinaryPrefixComparator(
              FlowRunColumnPrefix.METRIC.getColumnPrefixBytes(""))));
      list.addFilter(infoColFamilyList);
    } else {
      // Check if metricsToRetrieve are specified and if they are, create a
      // filter list for info column family by adding flow run tables columns
      // and a list for metrics to retrieve. Pls note that fieldsToRetrieve
      // will have METRICS added to it if metricsToRetrieve are specified
      // (in augmentParams()).
      TimelineFilterList metricsToRetrieve =
          dataToRetrieve.getMetricsToRetrieve();
      if (metricsToRetrieve != null &&
          !metricsToRetrieve.getFilterList().isEmpty()) {
        FilterList infoColFamilyList = new FilterList();
        infoColFamilyList.addFilter(infoColumnFamily);
        FilterList columnsList = updateFixedColumns();
        columnsList.addFilter(
            TimelineFilterUtils.createHBaseFilterList(
                FlowRunColumnPrefix.METRIC, metricsToRetrieve));
        infoColFamilyList.addFilter(columnsList);
        list.addFilter(infoColFamilyList);
      }
    }
    return list;
  }

  @Override
  protected Result getResult(Configuration hbaseConf, Connection conn,
      FilterList filterList) throws IOException {
    TimelineReaderContext context = getContext();
    byte[] rowKey =
        FlowRunRowKey.getRowKey(context.getClusterId(), context.getUserId(),
            context.getFlowName(), context.getFlowRunId());
    Get get = new Get(rowKey);
    get.setMaxVersions(Integer.MAX_VALUE);
    if (filterList != null && !filterList.getFilters().isEmpty()) {
      get.setFilter(filterList);
    }
    return getTable().getResult(hbaseConf, conn, get);
  }

  @Override
  protected ResultScanner getResults(Configuration hbaseConf,
      Connection conn, FilterList filterList) throws IOException {
    Scan scan = new Scan();
    TimelineReaderContext context = getContext();
    scan.setRowPrefixFilter(
        FlowRunRowKey.getRowKeyPrefix(context.getClusterId(),
            context.getUserId(), context.getFlowName()));
    FilterList newList = new FilterList();
    newList.addFilter(new PageFilter(getFilters().getLimit()));
    if (filterList != null && !filterList.getFilters().isEmpty()) {
      newList.addFilter(filterList);
    }
    scan.setFilter(newList);
    return getTable().getResultScanner(hbaseConf, conn, scan);
  }

  @Override
  protected TimelineEntity parseEntity(Result result) throws IOException {
    TimelineReaderContext context = getContext();
    FlowRunEntity flowRun = new FlowRunEntity();
    flowRun.setUser(context.getUserId());
    flowRun.setName(context.getFlowName());
    if (isSingleEntityRead()) {
      flowRun.setRunId(context.getFlowRunId());
    } else {
      FlowRunRowKey rowKey = FlowRunRowKey.parseRowKey(result.getRow());
      flowRun.setRunId(rowKey.getFlowRunId());
    }

    // read the start time
    Long startTime = (Long)FlowRunColumn.MIN_START_TIME.readResult(result);
    if (startTime != null) {
      flowRun.setStartTime(startTime.longValue());
    }

    // read the end time if available
    Long endTime = (Long)FlowRunColumn.MAX_END_TIME.readResult(result);
    if (endTime != null) {
      flowRun.setMaxEndTime(endTime.longValue());
    }

    // read the flow version
    String version = (String)FlowRunColumn.FLOW_VERSION.readResult(result);
    if (version != null) {
      flowRun.setVersion(version);
    }

    // read metrics if its a single entity query or if METRICS are part of
    // fieldsToRetrieve.
    if (isSingleEntityRead() || TimelineStorageUtils.hasField(
        getDataToRetrieve().getFieldsToRetrieve(), Field.METRICS)) {
      readMetrics(flowRun, result, FlowRunColumnPrefix.METRIC);
    }

    // set the id
    flowRun.setId(flowRun.getId());
    return flowRun;
  }
}
