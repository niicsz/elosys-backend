package com.binitech.elosys.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisCacheConfig.class);

  @Bean
  public RedisCacheManager cacheManager(
      RedisConnectionFactory connectionFactory, ElosysProperties properties) {
    RedisCacheConfiguration defaults =
        RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(properties.cache().defaultTtl())
            .computePrefixWith(name -> properties.cache().keyPrefix() + name + "::")
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer()))
            .disableCachingNullValues();
    return RedisCacheManager.builder(connectionFactory).cacheDefaults(defaults).build();
  }

  static RedisSerializer<Object> valueSerializer() {
    RedisSerializer<Object> json =
        GenericJacksonJsonRedisSerializer.builder()
            .enableDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                    .allowIfSubType("com.binitech.elosys.")
                    .allowIfSubType("java.")
                    .build())
            .build();
    return new RedisSerializer<>() {
      @Override
      public byte[] serialize(Object value) {
        return json.serialize(mutableCopy(value));
      }

      @Override
      public Object deserialize(byte[] bytes) {
        return integersAsLongs(json.deserialize(bytes));
      }
    };
  }

  private static Object mutableCopy(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> copy = new LinkedHashMap<>();
      map.forEach((k, v) -> copy.put(k, mutableCopy(v)));
      return copy;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> copy = new ArrayList<>(collection.size());
      collection.forEach(v -> copy.add(mutableCopy(v)));
      return copy;
    }
    return value;
  }

  private static Object integersAsLongs(Object value) {
    if (value instanceof Integer n) return n.longValue();
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> copy = new LinkedHashMap<>();
      map.forEach((k, v) -> copy.put(k, integersAsLongs(v)));
      return copy;
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      list.forEach(v -> copy.add(integersAsLongs(v)));
      return copy;
    }
    return value;
  }

  @Override
  public CacheErrorHandler errorHandler() {
    return new CacheErrorHandler() {
      @Override
      public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        LOGGER.warn(
            "cache GET falhou — tratando como miss. cache='{}' key='{}': {}",
            cache.getName(),
            key,
            exception.getMessage());
      }

      @Override
      public void handleCachePutError(
          RuntimeException exception, Cache cache, Object key, Object value) {
        LOGGER.warn(
            "cache PUT falhou. cache='{}' key='{}': {}",
            cache.getName(),
            key,
            exception.getMessage());
      }

      @Override
      public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        LOGGER.warn(
            "cache EVICT falhou. cache='{}' key='{}': {}",
            cache.getName(),
            key,
            exception.getMessage());
      }

      @Override
      public void handleCacheClearError(RuntimeException exception, Cache cache) {
        LOGGER.warn("cache CLEAR falhou. cache='{}': {}", cache.getName(), exception.getMessage());
      }
    };
  }
}
