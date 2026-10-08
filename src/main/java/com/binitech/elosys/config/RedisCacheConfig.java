package com.binitech.elosys.config;

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
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJacksonJsonRedisSerializer.builder()
                        .enableDefaultTyping(
                            BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType("com.binitech.elosys.")
                                .allowIfSubType("java.")
                                .build())
                        .build()))
            .disableCachingNullValues();
    return RedisCacheManager.builder(connectionFactory).cacheDefaults(defaults).build();
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
