package com.qoder.tcc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** TCC 上下文 JSON 序列化。 */
public final class TccContextUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TccContextUtil() {
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("TCC 上下文序列化失败", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("TCC 上下文反序列化失败: " + type.getSimpleName(), e);
        }
    }

    public static <T> java.util.List<T> fromJsonList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, elementType));
        } catch (Exception e) {
            throw new IllegalStateException("TCC 上下文列表反序列化失败: " + elementType.getSimpleName(), e);
        }
    }
}