package com.codemind.review.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * checkpoint 专用的 ObjectMapper（M7a）。
 *
 * <p>节点结果以 {@code Object} 存取，必须把具体类型一起写进 JSON，否则读回来是
 * {@code LinkedHashMap}，{@code DagOrchestrator} 跨阶段转型就失败——
 * {@code DagScheduler.execute} 每次开头都 {@code loadCheckpoint}，而编排器调它三次，
 * 第三次会读回第二次写的 {@code ChunkReduceResult}（M3 技术债，M6 的验证点实际被它堵死）。
 *
 * <p><b>绝不能注册成 Spring bean</b>：容器里只要出现一个 {@code ObjectMapper} bean，
 * Boot 的 {@code JacksonAutoConfiguration} 就按 {@code @ConditionalOnMissingBean} 退让，
 * 这个开了 default typing 的实例会变成全局唯一 mapper，被 Feign 编码器、Spring AI 的
 * HTTP 请求体、RocketMQ 事件、LLM 输出解析器一起拿去用，JSON 里全是类型数组。
 * 加 {@code @Qualifier} 只管注入点，管不住自动配置的退让。所以只由
 * {@link JdbcNodeRunRepository} 自己持有。
 *
 * <p>类型白名单只放本项目包与 java.util：反序列化任意类名是已知的攻击面，
 * 即便这里的数据是自己写的也不开口子。
 */
final class CheckpointObjectMapperFactory {

    private CheckpointObjectMapperFactory() {
    }

    static ObjectMapper create() {
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("com.codemind.")
                .allowIfSubType("java.util.")
                .build();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // checkpoint 只存 record 组件。派生的 isXxx()（如 ChunkSummary.isComplete()）默认会被当
        // is-getter 写成字段，读回时 record 构造器不认而失败。record 的 boolean 组件走组件访问器
        // degraded() 而非 isDegraded()，不受影响。在这里关掉而不是给 domain 加 @JsonIgnore：domain 不碰 Jackson 注解。
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        // 必须是 EVERYTHING 而非 NON_FINAL：节点结果（ChunkReduceResult / GlobalReduceResult /
        // ChangeMap / ReviewChunk）全是 record，record 是 final 类，NON_FINAL 按定义不给它们
        // 写类型 id，顶层读回来就还是 LinkedHashMap。
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
