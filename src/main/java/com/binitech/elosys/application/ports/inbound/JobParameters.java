package com.binitech.elosys.application.ports.inbound;

import com.binitech.elosys.domain.exception.BusinessException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record JobParameters(Map<String, String> values) {
  public JobParameters {
    values = Map.copyOf(values);
  }

  public static JobParameters empty() {
    return new JobParameters(Map.of());
  }

  public static JobParameters of(Map<String, String> values) {
    Map<String, String> normalized = new LinkedHashMap<>();
    values.forEach(
        (k, v) -> {
          if (k != null && v != null) normalized.put(k.strip().replace('_', '-'), v.strip());
        });
    return new JobParameters(normalized);
  }

  public String string(String key, String fallback) {
    String v = values.get(key);
    return v == null || v.isEmpty() ? fallback : v;
  }

  public int integer(String key, int fallback) {
    String v = values.get(key);
    if (v == null || v.isEmpty()) return fallback;
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new BusinessException("--" + key + " precisa ser inteiro: " + v);
    }
  }

  public Integer optionalInteger(String key) {
    String v = values.get(key);
    return v == null || v.isEmpty() ? null : integer(key, 0);
  }

  public double decimal(String key, double fallback) {
    String v = values.get(key);
    if (v == null || v.isEmpty()) return fallback;
    try {
      return Double.parseDouble(v);
    } catch (NumberFormatException e) {
      throw new BusinessException("--" + key + " precisa ser número: " + v);
    }
  }

  public boolean flag(String key) {
    String v = values.get(key);
    return v != null && (v.isEmpty() || v.equalsIgnoreCase("true") || v.equals("1"));
  }

  public List<String> list(String key) {
    String v = values.get(key);
    if (v == null || v.isBlank()) return List.of();
    return Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  public List<Integer> intList(String key) {
    return list(key).stream()
        .map(
            s -> {
              try {
                return Integer.parseInt(s);
              } catch (NumberFormatException e) {
                throw new BusinessException("--" + key + " precisa ser lista de inteiros: " + s);
              }
            })
        .toList();
  }

  public List<Long> longList(String key) {
    return intList(key).stream().map(Integer::longValue).toList();
  }
}
