package software.hvb.apache.flink.cdc.example.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Serializable;

import static org.apache.commons.lang3.ObjectUtils.firstNonNull;
import static org.apache.commons.lang3.ObjectUtils.isEmpty;

@Slf4j
public class MongoOperationExtractor implements Serializable {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Worked on Mongo 5.0.6 change events
    // https://www.mongodb.com/docs/v5.0/reference/change-events/
    public MongoOperation extract(String json, String documentKeyPath) throws IOException {

        log.info("Raw JSON to extract from: {}", json);

        JsonNode rootNode = objectMapper.readTree(json);

        JsonNode clusterTimeTextNode = rootNode.get("clusterTime");
        Long clusterTimeMs = (clusterTimeTextNode != NullNode.instance)
                ? objectMapper.readTree(clusterTimeTextNode.asText()).get("$timestamp").get("t").asLong() * 1000
                : null;
        Long eventProcessedTimeMs = rootNode.get("ts_ms").asLong();

        JsonNode documentTextNode = rootNode.get("fullDocument");
        String document = (documentTextNode != NullNode.instance)
                ? objectMapper.readTree(documentTextNode.asText()).toString()
                : null;

        JsonNode documentKeyNode = objectMapper.readTree(rootNode.get("documentKey").asText());
        for (String s : documentKeyPath.split("\\.")) {
            documentKeyNode = documentKeyNode.get(s);
        }
        String documentKey = documentKeyNode != null ? documentKeyNode.asText() : null;

        return MongoOperation.builder()
                .database(rootNode.get("ns").get("db").asText())
                .collection(extractCollectionName(rootNode))
                .documentKey(documentKey)
                .timestampMs(firstNonNull(clusterTimeMs, eventProcessedTimeMs))
                .type(isEmpty(clusterTimeMs) ? "snapshot" : rootNode.get("operationType").asText())
                .document(document)
                .build();
    }

    public String extractCollectionName(String json) throws Exception {
        return extractCollectionName(objectMapper.readTree(json));
    }

    private static String extractCollectionName(JsonNode node) {
        return node.get("ns").get("coll").asText();
    }
}
