package com.binitech.elosys.adapters.outbound.json;

import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.domain.exception.BusinessException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonJsonAdapter implements JsonPort {
  private final JsonMapper mapper =
      JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();
  private final JsonMapper pretty =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .build();

  @Override
  public Object readFile(Path path) {
    try {
      return mapper.readValue(path.toFile(), Object.class);
    } catch (JacksonException e) {
      throw new BusinessException("JSON inválido em " + path + ": " + e.getOriginalMessage(), e);
    }
  }

  @Override
  public Object parse(String json) {
    try {
      return mapper.readValue(json, Object.class);
    } catch (JacksonException e) {
      throw new BusinessException("JSON inválido: " + e.getOriginalMessage(), e);
    }
  }

  @Override
  public String canonical(Object value) {
    return mapper.writeValueAsString(sorted(value));
  }

  @Override
  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  @Override
  public String writePretty(Object value) {
    return pretty.writeValueAsString(value);
  }

  private static Object sorted(Object value) {
    if (value instanceof Map<?, ?> map) {
      TreeMap<String, Object> out = new TreeMap<>();
      map.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
      return out;
    }
    if (value instanceof Collection<?> list) {
      var out = new ArrayList<>(list.size());
      for (Object v : list) out.add(sorted(v));
      return out;
    }
    return value;
  }
}
