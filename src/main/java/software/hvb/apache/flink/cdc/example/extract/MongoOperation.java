package software.hvb.apache.flink.cdc.example.extract;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@EqualsAndHashCode
@ToString
@Getter
@Builder
public class MongoOperation {
    private String database;
    private String collection;
    private String documentKey;
    private Long timestampMs;
    private String type;
    private String document;
}
