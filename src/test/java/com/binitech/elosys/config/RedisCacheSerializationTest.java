package com.binitech.elosys.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

class RedisCacheSerializationTest {
  private final RedisSerializer<Object> serializer = RedisCacheConfig.valueSerializer();

  private Object roundTrip(Object value) {
    return serializer.deserialize(serializer.serialize(value));
  }

  private static Map<String, Object> row() {
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("sourceName", "TSE");
    provenance.put("legalBasis", null);
    provenance.put("sha256", "abc");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 7L);
    row.put("year", 2022L);
    row.put("round", null);
    row.put("share", 12.5);
    row.put("big", new BigDecimal("123456789012345678901234567890"));
    row.put("elected", true);
    row.put("office", "DEPUTADO ESTADUAL");
    row.put("tags", new ArrayList<>(List.of("a", "b")));
    row.put("provenance", provenance);
    return row;
  }

  @Test
  void longComesBackAsLong() {
    assertThat(roundTrip(42L)).isInstanceOf(Long.class).isEqualTo(42L);
  }

  @Test
  void listOfLongsKeepsLongs() {
    assertThat((List<?>) roundTrip(new ArrayList<>(List.of(2026L, 2024L))))
        .allSatisfy(y -> assertThat(y).isInstanceOf(Long.class));
  }

  @Test
  void rowWithNestedProvenanceRoundTrips() {
    assertThat(roundTrip(row())).isEqualTo(row());
  }

  @Test
  void listOfRowsRoundTrips() {
    List<Map<String, Object>> rows = new ArrayList<>(List.of(row(), row()));
    assertThat(roundTrip(rows)).isEqualTo(rows);
  }

  @Test
  void documentWithListOfRowsRoundTrips() {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("rows", new ArrayList<>(List.of(row())));
    doc.put("total", 1L);
    assertThat(roundTrip(doc)).isEqualTo(doc);
  }

  @Test
  void immutableListFromStreamRoundTrips() {
    List<Map<String, Object>> rows = java.util.stream.Stream.of(row(), row()).toList();
    assertThat(roundTrip(rows)).isEqualTo(rows);
  }

  @Test
  void immutableCollectionsNestedInDocumentRoundTrip() {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("rows", List.of(row()));
    doc.put("summary", Map.of("total", 1L));
    doc.put("empty", List.of());
    assertThat(roundTrip(doc)).isEqualTo(doc);
  }
}
