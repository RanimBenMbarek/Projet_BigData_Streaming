package org.example;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;
import scala.Tuple2;


import java.io.IOException;
import java.util.*;

public class SparkKafka {

    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.err.println("Usage: SparkKafka <bootstrap-servers> <subscribe-topics> <group-id>");
            System.exit(1);
        }

        String bootstrapServers = args[0];
        String topics = args[1];
        String groupId = args[2];
        String hbaseTable = "Fatalities";
        String columnFamily = "Fatalities";

        SparkSession spark = SparkSession
                .builder()
                .appName("SparkKafka")
                .getOrCreate();

        // Create DataFrame representing the stream of input lines from Kafka
        Dataset<Row> df = spark
                .readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", bootstrapServers)
                .option("subscribe", topics)
                .option("kafka.group.id", groupId)
                .load();
        Iterator<Tuple2<String, Integer>> nullIterator = new Iterator<Tuple2<String, Integer>>() {
            @Override
            public boolean hasNext() {
                return false; // Always return false to indicate no more elements
            }

            @Override
            public Tuple2<String, Integer> next() {
                return null; // Return null or any other placeholder value
            }
        };

        Dataset<Row> locationCount = df.selectExpr("CAST(value AS STRING)")
                .as(Encoders.STRING())
                .flatMap((String value) -> {
                    try {
                        String[] parts = value.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                        if (parts.length > 10) {
                            String location = parts[2].replaceAll("\"", "").trim();
                            if (!location.isEmpty()) {
                                int fatalities = Integer.parseInt(parts[10].trim());
                                return Collections.singletonList(new Tuple2<>(location, fatalities)).iterator();
                            }
                        }
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                        // Log the exception, handle it, or ignore depending on requirement
                    }
                    return Collections.emptyIterator();
                }, Encoders.tuple(Encoders.STRING(), Encoders.INT()))
                .toDF("location", "fatalities")
                .groupBy("location").sum("fatalities")
                .withColumnRenamed("sum(fatalities)", "total_fatalities");

        // Define HBase configuration
        Configuration hbaseConfig = HBaseConfiguration.create();

// Create HBase table if it does not exist
        try (Connection connection = ConnectionFactory.createConnection(hbaseConfig);
             Admin admin = connection.getAdmin()) {
            TableName tableName = TableName.valueOf(hbaseTable);
            if (!admin.tableExists(tableName)) {
                TableDescriptor tableDescriptor = TableDescriptorBuilder.newBuilder(tableName)
                        .setColumnFamily(ColumnFamilyDescriptorBuilder.of(columnFamily))
                        .build();
                admin.createTable(tableDescriptor);
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("An error occurred while creating HBase table: " + e.getMessage());
        }

// Define the StreamingQuery with foreachBatch to write to HBase
        StreamingQuery hbaseQuery = locationCount.writeStream()
                .outputMode("complete")
                .foreachBatch((batchDF, batchId) -> {
                    System.out.println("Processing batch DF: " + batchDF);
                    System.out.println("Processing batch ID: " + batchId);
                    try (Connection connection = ConnectionFactory.createConnection(hbaseConfig);
                         Table table = connection.getTable(TableName.valueOf(hbaseTable))) {

                        List<Row> rows = batchDF.collectAsList();
                        for (Row row : rows) {
                            String location = row.getString(row.fieldIndex("location"));
                            long totalFatalities = row.getLong(row.fieldIndex("total_fatalities"));
                            Put put = new Put(Bytes.toBytes(location));
                            put.addColumn(Bytes.toBytes(columnFamily), Bytes.toBytes("total_fatalities"), Bytes.toBytes(Long.toString(totalFatalities)));
                            table.put(put);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.err.println("An error occurred while accessing HBase table: " + e.getMessage());
                    }
                })
                .start();

// Await termination
        hbaseQuery.awaitTermination();

    }
}
