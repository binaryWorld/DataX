package com.alibaba.datax.plugin.writer.hbasebulkwriter;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.writer.hbasebulkwriter.column.DynamicHBaseColumn;
import com.alibaba.datax.plugin.writer.hbasebulkwriter.column.FixedHBaseColumn;
import com.alibaba.datax.plugin.writer.hbasebulkwriter.column.HBaseColumn;

import java.io.IOException;
import java.util.List;

public class HBaseBulker {

  private static final Logger LOG = LoggerFactory.getLogger(HBaseBulker.class);
  private org.apache.hadoop.conf.Configuration hbaseConf;
  private String hdfsConfPath;
  private String hbaseConfPath;
  // diamond cluster id, use diamond to save hbase-site.xml and hdfs-site.xml, diamond is a service
  @Deprecated
  private String diamondClusterId;
  private boolean isDynamicQualifier;
  @Deprecated
  private boolean isTruncateTable;
  private HBaseColumn.HBaseDataType rowkeyType;
  private String table;
  private int bucketNum;
  private int timeCol;
  private long startTs;
  private String nullMode;
  private HTable htable;
  @Deprecated
  private String encoding;
  /**
   * HDFS path where to put temperate HFiles
   */
  private String hdfsOutputDir;
  private List<? extends HBaseColumn> columnList;
  private List<FixedHBaseColumn> rowkeyList;
  private HFileWriter hfileWriter;

  static {
    HBaseHelper.loadNativeLibrary();
  }

  public HBaseBulker() {
  }
  
  // For test
  public int init(String table, org.apache.hadoop.conf.Configuration hbaseConf,
                  List<FixedHBaseColumn> rowkeyList,
                  List<FixedHBaseColumn> columnList)
      throws IOException {
    this.table = table;
    this.htable = new HTable(hbaseConf, table);
    this.hbaseConf = hbaseConf;
    this.rowkeyList = rowkeyList;
    this.columnList = columnList;
    return 0;
  }

  public int init(Configuration dataxConf) throws IOException {
    if (dataxConf.get(PluginKeys.PREFIX_FIXED) != null) {
      LOG.info("bulkwriter in fixed column mode");
      loadFixedColumnConfig(dataxConf);
    } else if (dataxConf.get(PluginKeys.PREFIX_DYNAMIC) != null) {
      LOG.info("bulkwriter in dynamic column mode");
      loadDynamicColumnConfig(dataxConf);
    } else {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "can not find fixedcolumn or dynamiccolumn in conf");
    }

    if (this.hbaseConf == null) {
      this.hbaseConf = HBaseHelper.getConfiguration(hdfsConfPath,
          hbaseConfPath, diamondClusterId);
    }
    HBaseHelper.checkConf(this.hbaseConf);
    HBaseHelper.checkHdfsVersion(this.hbaseConf);
    HBaseHelper.checkNativeLzoLibrary(this.hbaseConf);

    try {
      htable = new HTable(this.hbaseConf, table);
    } catch (IOException e) {
      throw DataXException.asDataXException(BulkWriterError.HBASE_ERROR,
          "Create HTable Failed.", e);
    }

    return 0;
  }

  public void loadDynamicColumnConfig(Configuration conf) {
    this.table = conf.getString(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.TABLE);
    if(this.table == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hbase_table.");
    }
    String rowkeyTypeStr = conf
        .getString(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.ROWKEY_TYPE);
    if(rowkeyTypeStr == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING, "Missing conif rowkey_type");
    }
    this.rowkeyType = HBaseColumn.HBaseDataType.parseStr(rowkeyTypeStr);
    if(this.rowkeyType == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config rowkey_type.");
    }
    this.columnList = DynamicHBaseColumn.parseColumnStr(conf
        .getConfiguration(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.COLUMN));
    this.hbaseConfPath = conf.getString(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.HBASE_CONFIG);
    if(this.hbaseConfPath == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hbase_config.");
    }
    this.hdfsConfPath = conf.getString(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.HDFS_CONFIG);
    if(this.hdfsConfPath == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hdfs_config.");
    }
    this.diamondClusterId = conf.getString(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.CLUSTER_ID);
    this.isTruncateTable = conf.getBool(
        PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.TRUNCATE, false);
    this.encoding = conf.getString(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.ENCODING, "utf-8");
    this.hdfsOutputDir = conf.getString(PluginKeys.PREFIX_DYNAMIC + "." + PluginKeys.OUTPUT);
    if(this.hdfsOutputDir == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hbase_output.");
    }
    this.isDynamicQualifier = true;
  }

  public void loadFixedColumnConfig(Configuration conf) {
    this.table = conf.getString(PluginKeys.PREFIX_FIXED + "." + PluginKeys.TABLE);
    if(this.table == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hbase_table.");
    }
    this.rowkeyList = FixedHBaseColumn.parseRowkeySchema(conf
        .getListConfiguration(PluginKeys.PREFIX_FIXED + "." + PluginKeys.ROWKEY));
    if(this.rowkeyList == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hbase_rowkey.");
    }
    this.columnList = FixedHBaseColumn.parseColumnSchema(conf
        .getListConfiguration(PluginKeys.PREFIX_FIXED + "." + PluginKeys.COLUMN));
    this.hbaseConfPath = conf.getString(PluginKeys.PREFIX_FIXED + "." + PluginKeys.HBASE_CONFIG);
    if(this.hbaseConfPath == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hbase_config.");
    }
    this.hdfsConfPath = conf.getString(PluginKeys.PREFIX_FIXED + "." + PluginKeys.HDFS_CONFIG);
    if(this.hdfsConfPath == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hdfs_config.");
    }
    this.diamondClusterId = conf.getString(PluginKeys.PREFIX_FIXED + "." + PluginKeys.CLUSTER_ID);
    this.nullMode = conf.getString(PluginKeys.PREFIX_FIXED + "." + PluginKeys.NULL_MODE,
        "EMPTY_BYTES");
    this.bucketNum = conf.getInt(PluginKeys.PREFIX_FIXED + "." + PluginKeys.BUCKET_NUM, -1);
    this.startTs = conf.getLong(PluginKeys.PREFIX_FIXED + "." + PluginKeys.START_TS, -1);
    this.timeCol = conf.getInt(PluginKeys.PREFIX_FIXED + "." + PluginKeys.TIME_COL, -1);
    if (timeCol != -1 && startTs != -1) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_ILLEGAL,
          "can not set time_col and start_ts at the same time.");
    }
    this.isTruncateTable = conf.getBool(PluginKeys.PREFIX_FIXED + "." + PluginKeys.TRUNCATE,
        false);
    this.encoding = conf.getString(PluginKeys.PREFIX_FIXED + "." + PluginKeys.ENCODING, "utf-8");
    this.hdfsOutputDir = conf.getString(PluginKeys.PREFIX_FIXED + "." + PluginKeys.OUTPUT);
    if(this.hdfsOutputDir == null) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_MISSING,
          "Missing config hbase_output.");
    }
    this.isDynamicQualifier = false;
  }

  public int prepare() {
    try {
      HBaseHelper.checkTmpOutputDir(hbaseConf, hdfsOutputDir);
      HBaseColumn.checkColumnList(htable, columnList);
    } catch (Exception e) {
      throw DataXException.asDataXException(BulkWriterError.CONFIG_ILLEGAL, e);
    }

    return 0;
  }

  @SuppressWarnings("unchecked")
  public int startWrite(HBaseLineReceiver receiver) {
    try {
      hfileWriter = HBaseHelper.createHFileWriter(htable, hbaseConf, hdfsOutputDir);
      if (!isDynamicQualifier) {
        WritePolicer.fixed(hfileWriter, receiver, bucketNum, rowkeyList,
            (List<FixedHBaseColumn>) columnList, encoding, timeCol, startTs,
            nullMode);
      } else {
        WritePolicer.dynamic(hfileWriter, receiver, rowkeyType,
            (List<DynamicHBaseColumn>) columnList);
      }
      return 0;
    } catch (Throwable e) {
      HBaseHelper.clearTmpOutputDir(hbaseConf, hdfsOutputDir);
      throw DataXException.asDataXException(BulkWriterError.RUNTIME, e);
    }
  }

  public int finish() {
    try {
      if (hfileWriter != null) {
        hfileWriter.close();
      }
    } catch (IOException e) {
      LOG.error("Close writer failed.", e);
    } catch (InterruptedException e) {
      LOG.error("Close writer failed.", e);
    }
    return 0;
  }

  public int post() {
    try {
      String uri = hbaseConf.get("fs.default.name");
      if (isTruncateTable) {
        HBaseAdmin hadmin = new HBaseAdmin(hbaseConf);
        if (hadmin.isTableEnabled(table)) {
          hadmin.disableTable(table);
        }
        hadmin.truncateTable(table);
        hadmin.close();
      }

      LoadIncrementalHFiles loader = new LoadIncrementalHFiles(hbaseConf);
      /**
       * {@link LoadIncrementalHFiles#doBulkLoad(Path, HTable)} must pass a Path
       * param with uri, because the bug @see
       * <ahref="https://issues.apache.org/jira/browse/HBASE-9537"
       * >HBASE-9537</a>
       */
      loader.doBulkLoad(new Path(uri + "/" + hdfsOutputDir), htable);
    } catch (Exception e) {
      LOG.error("BulkLoad error.", e);
      throw DataXException.asDataXException(BulkWriterError.HBASE_ERROR, e);
    } finally {
      HBaseHelper.clearTmpOutputDir(hbaseConf, hdfsOutputDir);
    }
    return 0;
  }
}
