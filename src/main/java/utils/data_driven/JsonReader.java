package utils.data_driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.Map;

public class JsonReader {

    private static final String DATA_DIR =
            System.getProperty("user.dir")
                    + File.separator + "src"
                    + File.separator + "main"
                    + File.separator + "resources"
                    + File.separator + "data"
                    + File.separator;

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Reads a SINGLE JSON object body and returns it as a Map.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getSingleData(String fileName, String nodeKey) {
        try {
            File jsonFile = resolveFile(fileName);
            JsonNode rootNode = mapper.readTree(jsonFile);
            JsonNode targetNode = (nodeKey == null || nodeKey.isBlank())
                    ? rootNode
                    : rootNode.path(nodeKey);

            if (targetNode.isMissingNode()) {
                throw new RuntimeException("Node '" + nodeKey + "' not found in " + fileName);
            }

            return mapper.convertValue(targetNode, Map.class);

        } catch (Exception e) {
            throw new RuntimeException("Failed to read JSON file: " + fileName + " -> " + e.getMessage(), e);
        }
    }

    /**
     * Reads an ARRAY of JSON objects for TestNG DataProvider.
     * Automatically handles both wrapped arrays { "key": [...] } and root arrays [ ... ].
     */
    public static Object[][] getIterativeData(String fileName, String arrayKey) {
        try {
            File jsonFile = resolveFile(fileName);
            JsonNode rootNode = mapper.readTree(jsonFile);

            JsonNode arrayNode;
            if (rootNode.isArray()) {
                arrayNode = rootNode;
            } else if (arrayKey != null && !arrayKey.isBlank() && rootNode.has(arrayKey)) {
                arrayNode = rootNode.path(arrayKey);
            } else if (rootNode.isObject() && rootNode.size() == 1 && rootNode.elements().next().isArray()) {
                arrayNode = rootNode.elements().next();
            } else {
                throw new RuntimeException(
                        "Array Node '" + arrayKey + "' not found or is not an array in " + fileName
                );
            }

            Map<?, ?>[] rows = mapper.convertValue(arrayNode, Map[].class);
            Object[][] testData = new Object[rows.length][1];

            for (int i = 0; i < rows.length; i++) {
                testData[i][0] = rows[i];
            }

            return testData;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to read iterative data from JSON file: " + fileName + " -> " + e.getMessage(), e
            );
        }
    }

    /**
     * Overloaded helper when the JSON file is a root array [ {...}, {...} ] or has a single array key.
     */
    public static Object[][] getIterativeData(String fileName) {
        return getIterativeData(fileName, null);
    }

    /**
     * Generates a 2D index array (0, 1, 2... totalIterations - 1) for index-based DataProviders.
     */
    public static Object[][] getIndexData(int totalIterations) {
        Object[][] data = new Object[totalIterations][1];
        for (int i = 0; i < totalIterations; i++) {
            data[i][0] = i;
        }
        return data;
    }

    /**
     * Finds the JSON file inside src/main/resources/data/ even if casing or .json extension differs.
     */
    private static File resolveFile(String fileName) {
        String normalized = fileName.endsWith(".json") ? fileName : fileName + ".json";
        File file = new File(DATA_DIR + normalized);
        if (file.exists()) {
            return file;
        }

        File dir = new File(DATA_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().equalsIgnoreCase(normalized)) {
                        return f;
                    }
                }
            }
        }
        throw new RuntimeException("File not found in " + DATA_DIR + ": " + normalized);
    }
}