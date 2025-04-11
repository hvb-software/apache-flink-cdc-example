package software.hvb.apache.flink.cdc.example;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.cdc.connectors.base.options.StartupOptions;
import org.apache.flink.cdc.connectors.mongodb.source.MongoDBSource;
import org.apache.flink.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions.JdbcConnectionOptionsBuilder;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import software.hvb.apache.flink.cdc.example.extract.MongoOperation;
import software.hvb.apache.flink.cdc.example.extract.MongoOperationExtractor;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;


public class SyncMongoToPostgresJob {

    public static void main(String[] args) throws Exception {

        var mongoDatabase = "mongo_database";
        var mongoCollections = List.of("collectionABC", "collectionDEF");

        var collectionToDocumentKeyPath = Map.of(
                mongoCollections.get(0), "_id",
                mongoCollections.get(1), "_id.value"
        );

        var collectionToTable = Map.of(
                mongoCollections.get(0), "table_abc_cdc",
                mongoCollections.get(1), "table_def_cdc"
        );

        var extractor = new MongoOperationExtractor();

        try (StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment()) {

            var stream = environment
                    .enableCheckpointing(5000)
                    .fromSource(
                            createSource(mongoDatabase, mongoCollections),
                            WatermarkStrategy.noWatermarks(),
                            "Source-MongoDB"
                    )
                    .setParallelism(2)
                    .process(new ProcessFunction<String, String>() {

                        @Override
                        public void processElement(
                                String value,
                                ProcessFunction<String, String>.Context ctx,
                                Collector<String> out
                        ) {
                            try {
                                // add tag with collection name to split the stream
                                ctx.output(createTag(extractor.extractCollectionName(value)), value);
                            } catch (Exception ex) {
                                // route to default sink
                                out.collect(value);
                            }
                        }
                    });

            mongoCollections.forEach(
                    collection -> {
                        String documentKeyPath = collectionToDocumentKeyPath.get(collection);
                        String postgresTable = collectionToTable.get(collection);
                        stream
                                // connect sink to stream based on collection name
                                .getSideOutput(createTag(collection))
                                .map(operation -> extractor.extract(operation, documentKeyPath))
                                .sinkTo(createSink(postgresTable))
                                .name("Sink-PostgreSQL: " + postgresTable)
                                .setParallelism(1);
                    }
            );

            // default sink
            stream.print();

            environment.execute("Syncing collections from MongoDB to PostgreSQL tables: " + mongoCollections);
        }
    }

    private static MongoDBSource<String> createSource(String database, List<String> collections) {
        return MongoDBSource.<String>builder()
                .hosts("host.docker.internal:27017")
                .username("root")
                .password("password")
                .connectionOptions("authSource=admin")
                .collectionList(
                        collections.stream()
                                .map(collection -> database + "." + collection)
                                .toArray(String[]::new)
                )
                .deserializer(new JsonDebeziumDeserializationSchema())
                .startupOptions(StartupOptions.initial())
                .build();
    }

    private static OutputTag<String> createTag(String id) {
        return new OutputTag<>(id) {
            // empty by design
        };
    }

    private static Sink<MongoOperation> createSink(String tableName) {
        return JdbcSink.<MongoOperation>builder()
                .withQueryStatement(
                        "INSERT INTO " + tableName +
                                " (document_key, operation_time, operation_type, document) " +
                                "VALUES ( ?, ?, ?, ?::JSONB) " +
                                "ON CONFLICT (document_key, operation_time, operation_type) " +
                                "   DO UPDATE SET document = EXCLUDED.document::JSONB",
                        (statement, operation) -> {
                            statement.setString(1, operation.getDocumentKey());
                            statement.setTimestamp(2, new Timestamp(
                                    operation.getTimestampMs() != null ? operation.getTimestampMs() : 0
                            ));
                            statement.setString(3, operation.getType());
                            statement.setObject(4, operation.getDocument());
                        }
                )
                .buildAtLeastOnce(
                        new JdbcConnectionOptionsBuilder()
                                .withUrl("jdbc:postgresql://host.docker.internal:5433/postgres")
                                .withDriverName("org.postgresql.Driver")
                                .withUsername("postgres")
                                .withPassword("mysecretpassword")
                                .build()
                );
    }
}
