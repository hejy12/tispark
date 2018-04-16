/*
 *
 * Copyright 2018 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.pingcap.tispark.statistics

import com.google.common.cache.CacheBuilder
import com.pingcap.tikv.TiSession
import com.pingcap.tikv.meta.{TiColumnInfo, TiDAGRequest, TiIndexInfo, TiTableInfo}
import com.pingcap.tikv.row.Row
import com.pingcap.tikv.statistics._
import com.pingcap.tikv.types.{DataType, IntegerType, MySQLType}
import com.pingcap.tispark.statistics.StatisticsHelper.shouldUpdateHistogram
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.mutable

private[statistics] case class StatisticsDTO(colId: Long,
                                             isIndex: Int,
                                             distinct: Long,
                                             version: Long,
                                             nullCount: Long,
                                             dataType: DataType,
                                             rawCMSketch: Array[Byte],
                                             idxInfo: TiIndexInfo,
                                             colInfo: TiColumnInfo)

private[statistics] case class StatisticsResult(histId: Long,
                                                histogram: Histogram,
                                                cMSketch: CMSketch,
                                                idxInfo: TiIndexInfo,
                                                colInfo: TiColumnInfo) {
  def hasIdxInfo: Boolean = idxInfo != null

  def hasColInfo: Boolean = colInfo != null
}

/**
 * Manager class for maintaining table statistics information cache.
 *
 * Statistics information is useful for index selection and broadcast join support in TiSpark currently,
 * and these are arranged follows:
 * `statisticsMap` contains `tableId`->[[TableStatistics]] data, each table(id) will have a [[TableStatistics]]
 * if you have loaded statistics information successfully.
 *
 * @param tiSession TiSession used for selecting statistics information from TiKV
 */
class StatisticsManager(tiSession: TiSession) {
  private lazy val snapshot = tiSession.createSnapshot()
  private lazy val catalog = tiSession.getCatalog
  // Statistics information table columns explanation:
  // stats_meta:
  //       Version       | A time stamp assigned by pd, updates along with DDL updates.
  //       Count         | Number of rows in the table, if equals to -1, that means this table may had been removed.
  //       Modify_count  | Indicates the count lose during update procedure, which shows the `healthiness` of the table.表示Table在更新过程中损失的Count，表示表的“健康度”
  // stats_histograms:
  //       Version       | Indicate version of this column's histogram.
  //       IsIndex       | Indicate whether this column is index.
  //       HistID        | Index id or column id, related to `IsIndex` above.
  //       Null Count    | The number of `NULL`.
  //       Distinct Count| Distinct value count.
  //       Modify Count  | Modification count, not used currently.
  // stats_buckets:
  //       TableID IsIndex HistID BucketID | Intuitive columns.
  //       Count         | The number of all the values that falls on the bucket and the previous buckets.
  //       Lower_Bound   | Minimal value of this bucket.
  //       Upper_Bound   | Maximal value of this bucket.
  //       Repeats       | The repeat count of maximal value.
  //
  // More explanation could be found here
  // https://github.com/pingcap/docs/blob/master/sql/statistics.md
  private[statistics] lazy val metaTable = catalog.getTable("mysql", "stats_meta")
  private[statistics] lazy val histTable = catalog.getTable("mysql", "stats_histograms")
  private[statistics] lazy val bucketTable = catalog.getTable("mysql", "stats_buckets")
  private final lazy val logger = LoggerFactory.getLogger(getClass.getName)
  private final val statisticsMap = CacheBuilder
    .newBuilder()
    .build[Object, Object]

  /**
   * Load statistics information maintained by TiDB to TiSpark.
   *
   * @param table   The table whose statistics info is needed.
   * @param columns Concerning columns for `table`, only these columns' statistics information
   *                will be loaded, if empty, all columns' statistics info will be loaded
   */
  def loadStatisticsInfo(table: TiTableInfo, columns: String*): Unit = synchronized {
    require(table != null, "TableInfo should not be null")
    if (!StatisticsHelper.isManagerReady(this)) {
      logger.warn(
        "Some of the statistics information table are not loaded properly, " +
          "make sure you have executed analyze table command before these information could be used by TiSpark."
      )
      return
    }

    val tblId = table.getId
    val tblCols = table.getColumns
    val loadAll = columns == null || columns.isEmpty
    var neededColIds = mutable.ArrayBuffer[Long]()
    if (!loadAll) {
      // check whether input column could be found in the table
      columns.distinct.foreach((col: String) => {
        val isColValid = tblCols.exists(_.matchName(col))
        if (!isColValid) {
          throw new RuntimeException(s"Column $col cannot be found in table ${table.getName}")
        } else {
          neededColIds += tblCols.find(_.matchName(col)).get.getId
        }
      })
    }

    // use cached one for incremental update
    val tblStatistic = if (statisticsMap.asMap.containsKey(tblId)) {
      statisticsMap.getIfPresent(tblId).asInstanceOf[TableStatistics]
    } else {
      new TableStatistics(tblId)
    }

    loadStatsFromStorage(tblId, tblStatistic, table, loadAll, neededColIds)
  }

  private[statistics] def readDAGRequest(req: TiDAGRequest): Iterator[Row] = snapshot.tableRead(req)

  private def loadStatsFromStorage(tblId: Long,
                                   tblStatistic: TableStatistics,
                                   table: TiTableInfo,
                                   loadAll: Boolean,
                                   neededColIds: mutable.ArrayBuffer[Long]): Unit = {
    // load count, modify_count, version info
    loadMetaToTblStats(tblId, tblStatistic)
    val req = StatisticsHelper
      .buildHistogramsRequest(histTable, tblId, snapshot.getTimestamp.getVersion)

    val rows = readDAGRequest(req)
    if (rows.isEmpty) return

    val requests = rows
      .map { StatisticsHelper.extractStatisticsDTO(_, table, loadAll, neededColIds, histTable) }
      .filter { _ != null }
    val results = statisticsResultFromStorage(tblId, requests.toSeq)

    // Update cache
    results.foreach { putOrUpdateTblStats(tblStatistic, _) }

    statisticsMap.put(tblId.asInstanceOf[Object], tblStatistic.asInstanceOf[Object])
  }

  private def putOrUpdateTblStats(tblStatistic: TableStatistics, result: StatisticsResult): Unit =
    if (result.hasIdxInfo) {
      val oldIdxSts = tblStatistic.getIndexHistMap.putIfAbsent(
        result.histId,
        new IndexStatistics(result.histogram, result.cMSketch, result.idxInfo)
      )
      if (shouldUpdateHistogram(oldIdxSts, result)) {
        oldIdxSts.setHistogram { result.histogram }
        oldIdxSts.setCmSketch { result.cMSketch }
        oldIdxSts.setIndexInfo { result.idxInfo }
      }
    } else if (result.hasColInfo) {
      val oldColSts = tblStatistic.getColumnsHistMap
        .putIfAbsent(
          result.histId,
          new ColumnStatistics(
            result.histogram,
            result.cMSketch,
            result.histogram.totalRowCount.toLong,
            result.colInfo
          )
        )
      if (shouldUpdateHistogram(oldColSts, result)) {
        oldColSts.setHistogram { result.histogram }
        oldColSts.setCmSketch { result.cMSketch }
        oldColSts.setColumnInfo { result.colInfo }
      }
    }

  private def loadMetaToTblStats(tableId: Long, tableStatistics: TableStatistics): Unit = {
    val req =
      StatisticsHelper.buildMetaRequest(metaTable, tableId, snapshot.getTimestamp.getVersion)

    val rows = readDAGRequest(req)
    if (rows.isEmpty) return

    val row = rows.next()
    tableStatistics.setCount { row.getLong(1) }
    tableStatistics.setModifyCount { row.getLong(2) }
    tableStatistics.setVersion { row.getLong(3) }
  }

  private def statisticsResultFromStorage(tableId: Long,
                                          requests: Seq[StatisticsDTO]): Seq[StatisticsResult] = {
    val req =
      StatisticsHelper.buildBucketRequest(bucketTable, tableId, snapshot.getTimestamp.getVersion)

    val rows = readDAGRequest(req)
    if (rows.isEmpty) return Nil
    // Group by hist_id(column_id)
    rows.toList
      .groupBy { _.getLong(7) }
      .flatMap { (t: (Long, List[Row])) =>
        val histId = t._1
        val rowsById = t._2
        // split bucket rows into index rows / non-index rows
        val (idxRows, colRows) = rowsById.partition { _.getLong(6) > 0 }
        val (idxReq, colReq) = requests.partition { _.isIndex > 0 }
        Array(
          StatisticsHelper.extractStatisticResult(histId, idxRows.iterator, idxReq),
          StatisticsHelper.extractStatisticResult(histId, colRows.iterator, colReq)
        )
      }
      .filter { _ != null }
      .toSeq
  }

  def getTableStatistics(id: Long): TableStatistics = {
    statisticsMap.getIfPresent(id).asInstanceOf[TableStatistics]
  }

  def getTableCount(id: Long): Long = {
    val tbStst = getTableStatistics(id)
    if (tbStst != null) {
      tbStst.getCount
    } else {
      Long.MaxValue
    }
  }

  import MySQLType._
  /**
    * Estimate table size in bytes.
    * Refer to
    * https://pingcap.com/docs/sql/datatype/#tidb-data-type
    * and
    * https://dev.mysql.com/doc/refman/5.7/en/storage-requirements.html
    *
    * @param table table to estimate
    * @return estimated table size in bytes
    *
    */
  def estimateTableSize(table: TiTableInfo): Long = {
    // Magic number used for estimating table size
    val goldenSplitFactor = 0.618
    val complementFactor = 1 - goldenSplitFactor

    val colWidth = table.getColumns.map(_.getType.getType).map {
      case TypeTiny => 1
      case TypeShort => 2
      case TypeInt24 => 3
      case TypeLong => 4
      case TypeLonglong => 8
      case TypeFloat => 4
      case TypeDouble => 8
      case TypeDecimal => 10
      case TypeNewDecimal => 10
      case TypeNull => 1
      case TypeTimestamp => 4
      case TypeDate => 3
      case TypeYear => 1
      case TypeDatetime => 8
      case TypeDuration => 3
      case TypeString => 255 * goldenSplitFactor
      case TypeVarchar => 255 * goldenSplitFactor
      case TypeVarString => 255 * goldenSplitFactor
      case TypeTinyBlob => 1 << (8 * goldenSplitFactor).toInt
      case TypeBlob => 1 << (16 * goldenSplitFactor).toInt
      case TypeMediumBlob => 1 << (24 * goldenSplitFactor).toInt
      case TypeLongBlob => 1 << (32 * goldenSplitFactor).toInt
      case TypeEnum => 2
      case TypeSet => 8
      case TypeBit => 8 * goldenSplitFactor
      case TypeJSON => 1 << (10 * goldenSplitFactor).toInt
      case _ => complementFactor * Int.MaxValue // for other types we just estimate as complementFactor * Int.MaxValue
    }.sum

    val tblCount = getTableCount(table.getId)
    if (Long.MaxValue / colWidth > tblCount) {
      (colWidth * tblCount).toLong
    } else {
      Long.MaxValue
    }
  }
}

object StatisticsManager {
  private var manager: StatisticsManager = _

  def initStatisticsManager(tiSession: TiSession, session: SparkSession): Unit = {
    if (manager == null) {
      synchronized {
        if (manager == null) {
          manager = new StatisticsManager(tiSession)
        }
      }
    }
  }

  def reset(): Unit = manager = null

  def getInstance(): StatisticsManager = {
    if (manager == null) {
      throw new RuntimeException("StatisticsManager has not been initialized properly.")
    }
    manager
  }
}
