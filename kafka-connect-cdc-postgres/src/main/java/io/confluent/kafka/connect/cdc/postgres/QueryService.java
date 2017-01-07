package io.confluent.kafka.connect.cdc.postgres;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.RateLimiter;
import io.confluent.kafka.connect.cdc.ChangeWriter;
import io.confluent.kafka.connect.cdc.JdbcUtils;
import io.confluent.kafka.connect.cdc.TableMetadataProvider;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.PooledConnection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

class QueryService extends AbstractExecutionThreadService {
  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  final Time time;
  final TableMetadataProvider tableMetadataProvider;
  final PostgreSqlSourceConnectorConfig config;
  final RateLimiter queryRateLimiter;
  final PostgreSqlChange.Builder changeBuilder;
  final ChangeWriter changeWriter;

  QueryService(Time time, TableMetadataProvider tableMetadataProvider, PostgreSqlSourceConnectorConfig config, ChangeWriter changeWriter) {
    this.time = time;
    this.tableMetadataProvider = tableMetadataProvider;
    this.config = config;
    this.changeWriter = changeWriter;
    this.queryRateLimiter = RateLimiter.create(10);
    this.changeBuilder = new PostgreSqlChange.Builder(this.config, this.time, this.tableMetadataProvider);
  }

  @Override
  protected void run() throws Exception {
    while (isRunning()) {
      try {
        this.queryRateLimiter.acquire();
        query();
      } catch (Exception ex) {
        if (log.isErrorEnabled()) {
          log.error("Exception thrown", ex);
        }
      }
    }
  }

  void query() throws SQLException {
    PooledConnection pooledConnection = null;
    try {
      pooledConnection = JdbcUtils.openPooledConnection(this.config, null);
      final String SQL = "SELECT * FROM pg_logical_slot_get_changes(?, ?, ?, 'skip-empty-xacts', '1', 'force-binary', '0', 'include-timestamp', '1', 'include-xids', '1')";
      try (PreparedStatement statement = pooledConnection.getConnection().prepareStatement(SQL)) {
        statement.setString(1, this.config.replicationSlotName);
        statement.setObject(2, null);
        statement.setInt(3, 1024);//Number of changes to stream

        try (ResultSet results = statement.executeQuery()) {
          while (results.next()) {
            PostgreSqlChange change = this.changeBuilder.build(results);
            this.changeWriter.addChange(change);
          }
        }
      }
    } finally {
      JdbcUtils.closeConnection(pooledConnection);
    }
  }
}
